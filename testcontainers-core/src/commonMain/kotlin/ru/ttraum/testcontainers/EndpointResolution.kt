package ru.ttraum.testcontainers

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.ttraum.testcontainers.internal.DockerJson

/** The daemon endpoint and where it came from (for error messages and diagnostics). */
public data class ResolvedEndpoint(val endpoint: DockerEndpoint, val source: String)

/**
 * Finds the Docker daemon, in the order testcontainers-java and the docker CLI use:
 * 1. `tc.host` (Testcontainers Desktop / Cloud): property or `TC_HOST`;
 * 2. `DOCKER_HOST`;
 * 3. `docker.host` in `~/.testcontainers.properties`;
 * 4. the docker CLI context: `DOCKER_CONTEXT`, else `currentContext` in `~/.docker/config.json`
 *    (`DOCKER_CONFIG` moves that directory);
 * 5. Windows: Docker Desktop's pipe `\\.\pipe\docker_engine`;
 * 6. well-known unix sockets (Docker Desktop, rootless, Colima, OrbStack, Rancher Desktop, Podman).
 *
 * TLS (`DOCKER_TLS_VERIFY`, contexts with TLS material) is detected and reported as unsupported
 * instead of failing later with an obscure handshake error.
 */
public fun resolveEndpoint(): ResolvedEndpoint {
    val tlsRequested = getEnv("DOCKER_TLS_VERIFY")?.let { it.isNotBlank() && it != "0" } == true ||
        TestcontainersConfig.getBoolean("docker.tls.verify")

    fun fromHost(host: String, source: String, tls: Boolean = tlsRequested): ResolvedEndpoint {
        val endpoint = DockerEndpoint.parse(host)
        if (tls && endpoint is DockerEndpoint.Tcp) {
            throw DockerException(
                "The Docker host $host (from $source) requires TLS, which is not supported yet. " +
                    "Use a unix socket, a named pipe or a plain tcp:// endpoint.",
            )
        }
        return ResolvedEndpoint(endpoint, source)
    }

    (TestcontainersConfig.get("tc.host") ?: getEnv("TC_HOST")?.takeIf { it.isNotBlank() })
        ?.let { return fromHost(it, "tc.host", tls = false) }
    getEnv("DOCKER_HOST")?.takeIf { it.isNotBlank() }?.let { return fromHost(it, "DOCKER_HOST") }
    TestcontainersConfig.get("docker.host")?.let { return fromHost(it, "docker.host in ~/.testcontainers.properties") }

    dockerContextEndpoint()?.let { (name, host, tls) -> return fromHost(host, "docker context '$name'", tls) }

    if (isWindows) return ResolvedEndpoint(DockerEndpoint.NamedPipe(DockerEndpoint.DEFAULT_WINDOWS_PIPE), "Windows default pipe")

    val home = userHome()
    val runtimeDir = getEnv("XDG_RUNTIME_DIR")
    val candidates = buildList {
        add("/var/run/docker.sock")
        if (home != null) {
            add("$home/.docker/run/docker.sock")
            add("$home/.docker/desktop/docker.sock")
            add("$home/.colima/default/docker.sock")
            add("$home/.orbstack/run/docker.sock")
            add("$home/.rd/docker.sock")
        }
        if (runtimeDir != null) {
            add("$runtimeDir/docker.sock")
            add("$runtimeDir/podman/podman.sock")
        }
    }
    val found = candidates.firstOrNull { SystemFileSystem.exists(Path(it)) }
        ?: throw DockerException("Could not find a Docker daemon. Set DOCKER_HOST or start Docker. Tried: $candidates")
    return ResolvedEndpoint(DockerEndpoint.Unix(found), "socket $found")
}

internal fun dockerConfigDir(): String? = getEnv("DOCKER_CONFIG")?.takeIf { it.isNotBlank() } ?: userHome()?.let { "$it/.docker" }

internal data class ContextEndpoint(val name: String, val host: String, val tls: Boolean)

/**
 * The endpoint of the current docker CLI context, or null for the built-in `default` context.
 * Context metadata lives in `contexts/meta/<sha256(name)>/meta.json`; instead of hashing the name,
 * the directories are scanned for a matching `Name`. TLS material sits under the same hash in `contexts/tls`.
 */
internal fun dockerContextEndpoint(configDir: String? = dockerConfigDir()): ContextEndpoint? {
    configDir ?: return null
    val name = getEnv("DOCKER_CONTEXT")?.takeIf { it.isNotBlank() }
        ?: readTextFile("$configDir/config.json")?.let { text ->
            runCatching { DockerJson.parseToJsonElement(text).jsonObject["currentContext"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
    if (name.isNullOrBlank() || name == "default") return null

    val metaRoot = Path("$configDir/contexts/meta")
    val dirs = if (SystemFileSystem.exists(metaRoot)) SystemFileSystem.list(metaRoot) else emptyList()
    for (dir in dirs) {
        val meta = readTextFile("$dir/meta.json")?.let { runCatching { DockerJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
        if (meta["Name"]?.jsonPrimitive?.contentOrNull != name) continue
        val docker = (meta["Endpoints"] as? JsonObject)?.get("docker") as? JsonObject
            ?: throw DockerException("docker context '$name' has no docker endpoint ($dir/meta.json)")
        val host = docker["Host"]?.jsonPrimitive?.contentOrNull
            ?: throw DockerException("docker context '$name' has no Host ($dir/meta.json)")
        val skipTls = docker["SkipTLSVerify"]?.jsonPrimitive?.booleanOrNull == true
        val hasTls = SystemFileSystem.exists(Path("$configDir/contexts/tls/${dir.name}/docker"))
        return ContextEndpoint(name, host, hasTls && !skipTls)
    }
    throw DockerException("docker context '$name' is selected but not found in $metaRoot (run `docker context ls`)")
}
