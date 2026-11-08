package ru.ttraum.testcontainers.internal

import ru.ttraum.testcontainers.DockerEndpoint
import ru.ttraum.testcontainers.DockerException
import ru.ttraum.testcontainers.toSocketTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val DockerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Output of a multiplexed docker stream (logs / exec). */
internal class StreamOutput(val stdout: String, val stderr: String)

internal class ExecOutput(val exitCode: Int, val stdout: String, val stderr: String)

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

    suspend fun imageExists(image: String): Boolean = inspectImage(image) != null

    suspend fun inspectImage(image: String): ImageInspect? {
        val response = call("GET", "/images/$image/json", allow = setOf(404))
        return if (response.status == 404) null else DockerJson.decodeFromString(response.bodyText())
    }

    suspend fun pullImage(image: String, registryAuth: String? = null) {
        val (repo, tag) = splitImage(image)
        val headers = registryAuth?.let { mapOf("X-Registry-Auth" to it) } ?: emptyMap()
        val response = call("POST", "/images/create?fromImage=${urlEncode(repo)}&tag=${urlEncode(tag)}", headers = headers)
        // The body is a stream of JSON progress objects; errors are reported in-band.
        checkStream(response.bodyText()) { "Failed to pull $image: $it" }
    }

    suspend fun tagImage(source: String, target: String) {
        val (repo, tag) = splitImage(target)
        call("POST", "/images/$source/tag?repo=${urlEncode(repo)}&tag=${urlEncode(tag)}")
    }

    suspend fun pushImage(image: String, registryAuth: String) {
        val (repo, tag) = splitImage(image)
        // The daemon requires the header even for registries without authentication.
        val response = call("POST", "/images/$repo/push?tag=${urlEncode(tag)}", headers = mapOf("X-Registry-Auth" to registryAuth))
        checkStream(response.bodyText()) { "Failed to push $image: $it" }
    }

    suspend fun removeImage(image: String) {
        call("DELETE", "/images/$image?force=true", allow = setOf(404, 409))
    }

    /** Builds an image from a tar build context; returns the image id. */
    suspend fun buildImage(context: ByteArray, query: String, registryConfig: String?): String {
        val headers = registryConfig?.let { mapOf("X-Registry-Config" to it) } ?: emptyMap()
        val response = call("POST", "/build?$query", context, contentType = "application/x-tar", headers = headers)
        val text = response.bodyText()
        val output = StringBuilder()
        var imageId: String? = null
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val obj = runCatching { DockerJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
            obj["stream"]?.jsonPrimitive?.content?.let { output.append(it) }
            obj["error"]?.let {
                throw DockerException("Image build failed: ${it.jsonPrimitive.content}\n--- build output ---\n${output.toString().takeLast(4000)}")
            }
            ((obj["aux"] as? JsonObject)?.get("ID"))?.jsonPrimitive?.content?.let { imageId = it }
        }
        return imageId ?: throw DockerException("Image build finished without an image id\n--- build output ---\n${output.toString().takeLast(4000)}")
    }

    /** Throws on the first in-band `error` of a JSON progress stream. */
    private inline fun checkStream(text: String, message: (String) -> String) {
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val obj = runCatching { DockerJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
            obj["error"]?.let { throw DockerException(message(it.jsonPrimitive.content)) }
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

    /** Returns the tar archive of [path] (a file or a directory). */
    suspend fun getArchive(id: String, path: String): ByteArray =
        call("GET", "/containers/$id/archive?path=${urlEncode(path)}").body

    suspend fun putArchive(id: String, path: String, tar: ByteArray) {
        call("PUT", "/containers/$id/archive?path=${urlEncode(path)}", tar, contentType = "application/x-tar")
    }

    /**
     * Runs a command in a running container and returns its exit code and output once it has finished.
     * The output stream ends when the process exits, so long commands simply take long; [stdin], if given,
     * is fed to the process and then closed.
     */
    suspend fun exec(id: String, request: ExecCreateRequest, stdin: ByteArray? = null): ExecOutput {
        if (stdin != null && endpoint is DockerEndpoint.NamedPipe) return execWithStdinFile(id, request, stdin)

        val create = if (stdin != null) ExecCreateRequest(
            cmd = request.cmd, attachStdin = true, env = request.env, user = request.user, workingDir = request.workingDir,
        ) else request
        val execId = callJson<IdResponse>("POST", "/containers/$id/exec", encode(create)).id
        val startBody = encode(ExecStartRequest())
        val startHeaders = mapOf("Content-Type" to "application/json", "Connection" to "Upgrade", "Upgrade" to "tcp")
        val response = if (stdin != null) {
            http.executeWithInput("POST", "/exec/$execId/start", startBody, startHeaders, stdin)
        } else {
            http.execute("POST", "/exec/$execId/start", startBody, startHeaders)
        }
        if (!response.isSuccess) {
            throw DockerException("Docker API POST /exec/$execId/start failed (${response.status}): ${response.bodyText()}", response.status)
        }
        val output = demultiplex(response.body)

        // The stream has ended, so the process is done; Docker may need a moment to record the exit code.
        var inspect = callJson<ExecInspect>("GET", "/exec/$execId/json")
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (inspect.running || inspect.exitCode == null) {
            if (deadline.hasPassedNow()) {
                throw DockerException("exec ${request.cmd} finished its output, but Docker reported no exit code (running=${inspect.running})")
            }
            delay(50)
            inspect = callJson("GET", "/exec/$execId/json")
        }
        return ExecOutput(inspect.exitCode, output.stdout, output.stderr)
    }

    /**
     * Named pipes cannot half-close, so the process would never see the end of its input. Instead the input
     * is copied into the container and redirected by a shell, which removes the file afterwards.
     */
    internal suspend fun execWithStdinFile(id: String, request: ExecCreateRequest, stdin: ByteArray): ExecOutput {
        val file = "/tmp/.tc-stdin-${Uuid.random().toHexString()}"
        putArchive(id, "/", buildTar(listOf(TarEntry(file, stdin, "600".toInt(8)))))
        val script = "f=\$1; shift; \"\$@\" < \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \$rc"
        val wrapped = ExecCreateRequest(
            cmd = listOf("/bin/sh", "-c", script, "sh", file) + request.cmd,
            env = request.env, user = request.user, workingDir = request.workingDir,
        )
        return exec(id, wrapped, stdin = null)
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
