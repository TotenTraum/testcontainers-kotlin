package ru.ttraum.testcontainers.images

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import ru.ttraum.testcontainers.Docker
import ru.ttraum.testcontainers.DockerException
import ru.ttraum.testcontainers.ResourceReaper
import ru.ttraum.testcontainers.internal.TarEntry
import ru.ttraum.testcontainers.internal.buildTar
import ru.ttraum.testcontainers.internal.guessMode
import ru.ttraum.testcontainers.internal.readHostTree
import ru.ttraum.testcontainers.internal.urlEncode
import kotlin.uuid.Uuid

/**
 * An image built from a Dockerfile on first use. Pass it to [ru.ttraum.testcontainers.GenericContainer]:
 *
 * ```
 * val image = ImageFromDockerfile()
 *     .withDockerfileFromString("FROM alpine:3.20\nCOPY app.sh /app.sh\nCMD [\"/app.sh\"]")
 *     .withFileFromString("app.sh", "#!/bin/sh\necho hi\nsleep 300")
 * GenericContainer(image).use { ... }
 * ```
 *
 * The image is labelled with the session id, so Ryuk removes it when the process ends.
 * Files read from the host are executable (0755) if they start with `#!`, else 0644.
 *
 * A `.dockerignore` in the context root (or `<dockerfile>.dockerignore` next to the Dockerfile, which takes
 * precedence) filters the whole context, as `docker build` does; the Dockerfile and the ignore file are always sent.
 */
public class ImageFromDockerfile(
    public val imageName: String = "localhost/testcontainers/${Uuid.random().toHexString().take(16)}:latest",
) {
    private val files = linkedMapOf<String, TarEntry>()
    private val buildArgs = linkedMapOf<String, String>()
    private var dockerfilePath = "Dockerfile"
    private var target: String? = null
    private var pull = false
    private val mutex = Mutex()
    private var builtId: String? = null

    public fun withDockerfileFromString(dockerfile: String, path: String = "Dockerfile"): ImageFromDockerfile = apply {
        dockerfilePath = path
        withFileFromString(path, dockerfile)
    }

    /** Uses a Dockerfile that is already part of the build context (default `Dockerfile`). */
    public fun withDockerfilePath(path: String): ImageFromDockerfile = apply { dockerfilePath = path }

    public fun withFileFromString(contextPath: String, content: String, mode: Int = guessMode(content.encodeToByteArray())): ImageFromDockerfile =
        withFileFromBytes(contextPath, content.encodeToByteArray(), mode)

    public fun withFileFromBytes(contextPath: String, content: ByteArray, mode: Int = guessMode(content)): ImageFromDockerfile = apply {
        val path = contextPath.trimStart('/')
        files[path] = TarEntry(path, content, mode)
    }

    /** Adds a host file, or a whole host directory, at [contextPath] in the build context ("" or "." for the root). */
    public fun withFileFromPath(contextPath: String, hostPath: String): ImageFromDockerfile = apply {
        val base = contextPath.trim('/').removePrefix(".").trim('/')
        readHostTree(hostPath, base).forEach { files[it.path] = it }
    }

    public fun withBuildArg(key: String, value: String): ImageFromDockerfile = apply { buildArgs[key] = value }

    /** Builds only up to this stage of a multi-stage Dockerfile. */
    public fun withTarget(stage: String): ImageFromDockerfile = apply { target = stage }

    /** Always pulls newer versions of the base images. */
    public fun withPullBaseImages(enabled: Boolean = true): ImageFromDockerfile = apply { pull = enabled }

    /** Builds the image (once; later calls return the same id). Returns the image id. */
    public suspend fun build(): String = mutex.withLock {
        builtId?.let { return it }
        if (dockerfilePath !in files.keys && files.keys.none { it == dockerfilePath }) {
            throw DockerException("No $dockerfilePath in the build context (use withDockerfileFromString or withFileFromPath)")
        }
        ResourceReaper.ensureStarted()

        val labels = JsonObject(Docker.defaultLabels.mapValues { JsonPrimitive(it.value) })
        val args = JsonObject(buildArgs.mapValues { JsonPrimitive(it.value) })
        val query = buildString {
            append("t=").append(urlEncode(imageName))
            append("&dockerfile=").append(urlEncode(dockerfilePath))
            append("&labels=").append(urlEncode(labels.toString()))
            if (buildArgs.isNotEmpty()) append("&buildargs=").append(urlEncode(args.toString()))
            target?.let { append("&target=").append(urlEncode(it)) }
            if (pull) append("&pull=true")
            append("&rm=true&forcerm=true")
        }
        val credentials = baseImages().map(::registryOf).distinct()
            .mapNotNull { registry -> Docker.registryCredentials.credentialsFor(registry)?.let { registry to it } }
            .toMap()
        val id = Docker.client.buildImage(
            buildTar(contextEntries()),
            query,
            credentials.takeIf { it.isNotEmpty() }?.let(::registryConfigHeader),
        )
        builtId = id
        id
    }

    /** The context after `.dockerignore`. */
    internal fun contextEntries(): List<TarEntry> {
        val ignoreFile = files["$dockerfilePath.dockerignore"] ?: files[".dockerignore"] ?: return files.values.toList()
        val ignore = DockerIgnore(ignoreFile.content.decodeToString())
        if (ignore.isEmpty) return files.values.toList()
        val alwaysSent = setOf(dockerfilePath, ignoreFile.path)
        return files.values.filter { it.path in alwaysSent || !ignore.isExcluded(it.path) }
    }

    /** Base images named in `FROM` lines (stage names and `scratch` excluded), for registry credentials. */
    private fun baseImages(): List<String> {
        val dockerfile = files[dockerfilePath]?.content?.decodeToString() ?: return emptyList()
        val stages = mutableSetOf<String>()
        val images = mutableListOf<String>()
        for (line in dockerfile.lines()) {
            val words = line.trim().split(Regex("\\s+"))
            if (words.firstOrNull()?.equals("FROM", ignoreCase = true) != true) continue
            val rest = words.drop(1).filterNot { it.startsWith("--") }
            val image = rest.firstOrNull() ?: continue
            if (image !in stages && image != "scratch" && !image.contains('$')) images += image
            if (rest.size >= 3 && rest[1].equals("AS", ignoreCase = true)) stages += rest[2]
        }
        return images
    }

    override fun toString(): String = "ImageFromDockerfile($imageName)"
}
