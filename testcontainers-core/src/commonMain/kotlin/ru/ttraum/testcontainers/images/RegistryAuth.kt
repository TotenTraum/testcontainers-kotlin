package ru.ttraum.testcontainers.images

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.ttraum.testcontainers.dockerConfigDir
import ru.ttraum.testcontainers.getEnv
import ru.ttraum.testcontainers.internal.DockerJson
import ru.ttraum.testcontainers.readTextFile
import ru.ttraum.testcontainers.runProcess
import kotlin.io.encoding.Base64

/** Credentials for one registry. Either [username]/[password] or an [identityToken]. */
public data class RegistryCredentials(
    val username: String? = null,
    val password: String? = null,
    val identityToken: String? = null,
) {
    override fun toString(): String = "RegistryCredentials(username=$username, password=***, identityToken=${identityToken?.let { "***" }})"
}

/** Supplies credentials for a registry host (`docker.io`, `ghcr.io`, `localhost:5000`), or null for anonymous access. */
public fun interface RegistryCredentialsProvider {
    public suspend fun credentialsFor(registry: String): RegistryCredentials?
}

/**
 * Reads credentials the way the docker CLI stores them:
 * the JSON in `DOCKER_AUTH_CONFIG` if set, else `config.json` in `DOCKER_CONFIG` (default `~/.docker`).
 * Per registry: `credHelpers` → inline `auths` (`auth` / `identitytoken`) → `credsStore`.
 * Helpers are the `docker-credential-<name>` programs on PATH (`desktop`, `osxkeychain`, `wincred`, `pass`, ...).
 */
public class DockerConfigCredentials(
    private val configJson: () -> String? = {
        getEnv("DOCKER_AUTH_CONFIG")?.takeIf { it.isNotBlank() } ?: dockerConfigDir()?.let { readTextFile("$it/config.json") }
    },
    private val helper: suspend (helper: String, serverUrl: String) -> RegistryCredentials? = ::runCredentialHelper,
) : RegistryCredentialsProvider {
    private val cache = mutableMapOf<String, RegistryCredentials?>()
    private val mutex = Mutex()

    override suspend fun credentialsFor(registry: String): RegistryCredentials? = mutex.withLock {
        if (registry in cache) return cache[registry]
        val found = lookup(normalizeRegistry(registry))
        cache[registry] = found
        found
    }

    private suspend fun lookup(registry: String): RegistryCredentials? {
        val config = configJson()?.let { runCatching { DockerJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val serverUrl = if (registry == "docker.io") DOCKER_HUB_SERVER else registry

        (config["credHelpers"] as? JsonObject)?.entries
            ?.firstOrNull { normalizeRegistry(it.key) == registry }
            ?.value?.jsonPrimitive?.contentOrNull
            ?.let { return helper(it, serverUrl) }

        (config["auths"] as? JsonObject)?.entries
            ?.firstOrNull { normalizeRegistry(it.key) == registry }
            ?.let { (it.value as? JsonObject)?.let(::inlineCredentials) }
            ?.let { return it }

        config["credsStore"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { return helper(it, serverUrl) }
        return null
    }

    private fun inlineCredentials(entry: JsonObject): RegistryCredentials? {
        entry["identitytoken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?.let { return RegistryCredentials(identityToken = it) }
        val auth = entry["auth"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val decoded = runCatching { Base64.decode(auth).decodeToString() }.getOrNull() ?: return null
        val idx = decoded.indexOf(':')
        if (idx < 0) return null
        return RegistryCredentials(username = decoded.substring(0, idx), password = decoded.substring(idx + 1))
    }

    public companion object {
        internal const val DOCKER_HUB_SERVER: String = "https://index.docker.io/v1/"
    }
}

/** `https://index.docker.io/v1/` → `docker.io`, `https://ghcr.io/v2/` → `ghcr.io`. */
internal fun normalizeRegistry(key: String): String {
    val host = key.substringAfter("://").substringBefore('/').lowercase()
    return when (host) {
        "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com", "docker.io" -> "docker.io"
        else -> host
    }
}

/** Runs `docker-credential-<name> get`; "not found" and missing helpers both mean anonymous access. */
internal suspend fun runCredentialHelper(name: String, serverUrl: String): RegistryCredentials? {
    if (!name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return null
    val result = runProcess(listOf("docker-credential-$name", "get"), serverUrl) ?: return null
    if (result.exitCode != 0) return null
    val json = runCatching { DockerJson.parseToJsonElement(result.stdout).jsonObject }.getOrNull() ?: return null
    val username = json["Username"]?.jsonPrimitive?.contentOrNull
    val secret = json["Secret"]?.jsonPrimitive?.contentOrNull ?: return null
    if (secret.isEmpty()) return null
    return if (username == "<token>") RegistryCredentials(identityToken = secret) else RegistryCredentials(username, secret)
}

/** Value of the `X-Registry-Auth` header: base64url JSON of the auth config. */
internal fun registryAuthHeader(registry: String, credentials: RegistryCredentials): String {
    val json = authConfigJson(registry, credentials)
    return Base64.UrlSafe.encode(json.toString().encodeToByteArray())
}

/** Value of the `X-Registry-Config` header for builds: base64url JSON map registry → auth config. */
internal fun registryConfigHeader(credentials: Map<String, RegistryCredentials>): String {
    val json = buildJsonObject {
        credentials.forEach { (registry, creds) ->
            val key = if (registry == "docker.io") DockerConfigCredentials.DOCKER_HUB_SERVER else registry
            put(key, authConfigJson(registry, creds))
        }
    }
    return Base64.UrlSafe.encode(json.toString().encodeToByteArray())
}

private fun authConfigJson(registry: String, credentials: RegistryCredentials): JsonObject = buildJsonObject {
    if (credentials.identityToken != null) {
        put("identitytoken", credentials.identityToken)
    } else {
        credentials.username?.let { put("username", it) }
        credentials.password?.let { put("password", it) }
    }
    put("serveraddress", if (registry == "docker.io") DockerConfigCredentials.DOCKER_HUB_SERVER else registry)
}
