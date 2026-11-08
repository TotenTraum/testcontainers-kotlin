package ru.ttraum.testcontainers.internal

import ru.ttraum.testcontainers.DockerEndpoint
import ru.ttraum.testcontainers.DockerException
import ru.ttraum.testcontainers.toSocketTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val DockerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Output of a multiplexed docker stream (logs / exec). */
internal class StreamOutput(val stdout: String, val stderr: String)

/** Thin wrapper over the Docker Engine REST API. */
internal class DockerClient(val endpoint: DockerEndpoint) {
    private val http = RawHttpClient(endpoint.toSocketTarget())

    private suspend fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        allow: Set<Int> = emptySet(),
    ): HttpResponse {
        val allHeaders = if (body != null) headers + ("Content-Type" to contentType) else headers
        val response = http.execute(method, path, body, allHeaders)
        if (!response.isSuccess && response.status !in allow) {
            val message = runCatching { DockerJson.decodeFromString<ErrorResponse>(response.bodyText()).message }
                .getOrNull() ?: response.bodyText()
            throw DockerException("Docker API $method $path failed (${response.status}): $message", response.status)
        }
        return response
    }

    private suspend inline fun <reified T> callJson(method: String, path: String, body: ByteArray? = null): T =
        DockerJson.decodeFromString(call(method, path, body).bodyText())

    private inline fun <reified T> encode(value: T): ByteArray = DockerJson.encodeToString(value).encodeToByteArray()

    suspend fun ping(): String = call("GET", "/_ping").bodyText()

    /** Daemon OS: `linux` or `windows` (Windows containers mode). */
    suspend fun serverOs(): String = callJson<VersionResponse>("GET", "/version").os

    suspend fun imageExists(image: String): Boolean =
        call("GET", "/images/$image/json", allow = setOf(404)).status != 404

    suspend fun pullImage(image: String) {
        val (repo, tag) = splitImage(image)
        val response = call("POST", "/images/create?fromImage=${urlEncode(repo)}&tag=${urlEncode(tag)}")
        // The body is a stream of JSON progress objects; errors are reported in-band.
        response.bodyText().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val obj = runCatching { DockerJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
            obj["error"]?.let { throw DockerException("Failed to pull $image: ${it.jsonPrimitive.content}") }
        }
    }

    suspend fun createContainer(request: ContainerCreateRequest, name: String? = null): String {
        val query = name?.let { "?name=${urlEncode(it)}" } ?: ""
        return callJson<IdResponse>("POST", "/containers/create$query", encode(request)).id
    }

    suspend fun startContainer(id: String) {
        call("POST", "/containers/$id/start", allow = setOf(304))
    }

    suspend fun inspectContainer(id: String): ContainerInspect = callJson("GET", "/containers/$id/json")

    suspend fun stopContainer(id: String, timeoutSeconds: Int = 10) {
        call("POST", "/containers/$id/stop?t=$timeoutSeconds", allow = setOf(304, 404))
    }

    suspend fun removeContainer(id: String, force: Boolean = true, volumes: Boolean = true) {
        call("DELETE", "/containers/$id?force=$force&v=$volumes", allow = setOf(404))
    }

    suspend fun logs(id: String): StreamOutput {
        val response = call("GET", "/containers/$id/logs?stdout=true&stderr=true")
        return demultiplex(response.body)
    }

    suspend fun putArchive(id: String, path: String, tar: ByteArray) {
        call("PUT", "/containers/$id/archive?path=${urlEncode(path)}", tar, contentType = "application/x-tar")
    }

    suspend fun exec(id: String, request: ExecCreateRequest): Triple<Int, String, String> {
        val execId = callJson<IdResponse>("POST", "/containers/$id/exec", encode(request)).id
        val response = call(
            "POST", "/exec/$execId/start", encode(ExecStartRequest()),
            headers = mapOf("Connection" to "Upgrade", "Upgrade" to "tcp"),
        )
        val output = demultiplex(response.body)
        var inspect = callJson<ExecInspect>("GET", "/exec/$execId/json")
        var attempts = 0
        while ((inspect.running || inspect.exitCode == null) && attempts++ < 50) {
            kotlinx.coroutines.delay(100)
            inspect = callJson("GET", "/exec/$execId/json")
        }
        return Triple(inspect.exitCode ?: -1, output.stdout, output.stderr)
    }

    suspend fun createNetwork(request: NetworkCreateRequest): String =
        callJson<IdResponse>("POST", "/networks/create", encode(request)).id

    suspend fun removeNetwork(id: String) {
        call("DELETE", "/networks/$id", allow = setOf(404))
    }

    companion object {
        fun splitImage(image: String): Pair<String, String> {
            val at = image.indexOf('@')
            if (at >= 0) return image.substring(0, at) to image.substring(at + 1)
            val lastSlash = image.lastIndexOf('/')
            val colon = image.lastIndexOf(':')
            return if (colon > lastSlash) image.substring(0, colon) to image.substring(colon + 1) else image to "latest"
        }

        /** Decodes Docker's stdout/stderr multiplexed stream (8-byte frame headers). */
        fun demultiplex(bytes: ByteArray): StreamOutput {
            val looksMultiplexed = bytes.size >= 8 && bytes[0].toInt() in 0..2 &&
                bytes[1].toInt() == 0 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0
            if (!looksMultiplexed) return StreamOutput(bytes.decodeToString(), "")
            val out = ByteArrayBuilder()
            val err = ByteArrayBuilder()
            var pos = 0
            while (pos + 8 <= bytes.size) {
                val type = bytes[pos].toInt()
                val size = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 8) or (bytes[pos + 7].toInt() and 0xFF)
                pos += 8
                val end = minOf(pos + size, bytes.size)
                val chunk = bytes.copyOfRange(pos, end)
                if (type == 2) err.append(chunk) else out.append(chunk)
                pos = end
            }
            return StreamOutput(out.toByteArray().decodeToString(), err.toByteArray().decodeToString())
        }
    }
}
