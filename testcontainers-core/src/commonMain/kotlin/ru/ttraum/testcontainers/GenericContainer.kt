package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.ContainerCreateRequest
import ru.ttraum.testcontainers.internal.EndpointConfig
import ru.ttraum.testcontainers.internal.ExecCreateRequest
import ru.ttraum.testcontainers.internal.HostConfig
import ru.ttraum.testcontainers.internal.NetworkingConfig
import ru.ttraum.testcontainers.internal.PortBinding
import ru.ttraum.testcontainers.internal.TarEntry
import ru.ttraum.testcontainers.internal.buildTar
import ru.ttraum.testcontainers.internal.readTar
import ru.ttraum.testcontainers.images.ImageFromDockerfile
import ru.ttraum.testcontainers.images.PullPolicy
import ru.ttraum.testcontainers.wait.Wait
import ru.ttraum.testcontainers.wait.WaitStrategy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.ttraum.testcontainers.wait.WaitProgress
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** The command did not finish within the timeout given to [GenericContainer.execInContainer]. */
public class ExecTimeoutException(message: String) : RuntimeException(message)

public data class ContainerLogs(val stdout: String, val stderr: String) {
    val all: String get() = stdout + stderr
}

public class ContainerLaunchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A Docker container for tests.
 *
 * ```
 * val redis = GenericContainer("redis:7-alpine") {
 *     withExposedPorts(6379)
 *     waitingFor(Wait.forLogMessage(".*Ready to accept connections.*"))
 * }
 * redis.use { println("${it.host}:${it.getMappedPort(6379)}") }
 * ```
 */
public open class GenericContainer(
    public val image: String,
    configure: GenericContainer.() -> Unit,
) {
    public constructor(image: String) : this(image, {})

    /** A container from an image built from a Dockerfile; the build runs on [start]. */
    public constructor(image: ImageFromDockerfile, configure: GenericContainer.() -> Unit = {}) : this(image.imageName, configure) {
        imageBuilder = image
    }

    /** The image actually run, after [Docker.imageNameSubstitutor]; known once started. */
    public var resolvedImage: String? = null
        private set

    private val exposedPorts = linkedSetOf<Int>()
    private val fixedPorts = linkedMapOf<Int, Int>()
    private val env = linkedMapOf<String, String>()
    private val labels = linkedMapOf<String, String>()
    private val binds = mutableListOf<String>()
    private val tmpfs = linkedMapOf<String, String>()
    private val filesToCopy = mutableListOf<TarEntry>()
    private val networkAliases = mutableListOf<String>()
    private var command: List<String>? = null
    private var entrypoint: List<String>? = null
    private var workingDir: String? = null
    private var user: String? = null
    private var hostname: String? = null
    private var privileged: Boolean = false
    private var network: Network? = null
    private var name: String? = null
    private var pullPolicy: PullPolicy = PullPolicy.defaultPolicy()
    private var imageBuilder: ImageFromDockerfile? = null
    private var waitStrategy: WaitStrategy? = null
    private var startupTimeout: Duration = 60.seconds

    public var containerId: String? = null
        private set
    private var mappedPorts: Map<Int, Int> = emptyMap()

    init {
        configure()
    }

    // ---------------------------------------------------------------- configuration

    public fun withExposedPorts(vararg ports: Int): GenericContainer = apply { exposedPorts += ports.toList() }

    /** Binds a container port to a fixed host port. Prefer [withExposedPorts] with random ports. */
    public fun withFixedExposedPort(hostPort: Int, containerPort: Int): GenericContainer = apply {
        exposedPorts += containerPort
        fixedPorts[containerPort] = hostPort
    }

    public fun withEnv(key: String, value: String): GenericContainer = apply { env[key] = value }
    public fun withEnv(values: Map<String, String>): GenericContainer = apply { env += values }
    public fun withLabel(key: String, value: String): GenericContainer = apply { labels[key] = value }
    public fun withCommand(vararg cmd: String): GenericContainer = apply { command = cmd.toList() }
    public fun withEntrypoint(vararg cmd: String): GenericContainer = apply { entrypoint = cmd.toList() }
    public fun withWorkingDirectory(dir: String): GenericContainer = apply { workingDir = dir }
    public fun withUser(user: String): GenericContainer = apply { this.user = user }

    /** Hostname inside the container (what `hostname` prints, and the name other containers on the network may use). */
    public fun withHostname(hostname: String): GenericContainer = apply { this.hostname = hostname }
    public fun withPrivilegedMode(enabled: Boolean = true): GenericContainer = apply { privileged = enabled }
    public fun withName(name: String): GenericContainer = apply { this.name = name }
    public fun withAlwaysPull(enabled: Boolean = true): GenericContainer =
        withImagePullPolicy(if (enabled) PullPolicy.alwaysPull() else PullPolicy.defaultPolicy())

    public fun withImagePullPolicy(policy: PullPolicy): GenericContainer = apply { pullPolicy = policy }
    public fun withNetwork(network: Network): GenericContainer = apply { this.network = network }
    public fun withNetworkAliases(vararg aliases: String): GenericContainer = apply { networkAliases += aliases }
    public fun withTmpFs(path: String, options: String = "rw"): GenericContainer = apply { tmpfs[path] = options }
    public fun waitingFor(strategy: WaitStrategy): GenericContainer = apply { waitStrategy = strategy }
    public fun withStartupTimeout(timeout: Duration): GenericContainer = apply { startupTimeout = timeout }

    /** Bind-mounts a host path: `mode` is `rw` or `ro`. */
    public fun withFileSystemBind(hostPath: String, containerPath: String, mode: String = "rw"): GenericContainer =
        apply { binds += "$hostPath:$containerPath:$mode" }

    /** Copies content into the container before it starts. */
    public fun withCopyToContainer(content: ByteArray, containerPath: String, mode: Int = "644".toInt(8)): GenericContainer =
        apply { filesToCopy += TarEntry(containerPath, content, mode) }

    public fun withCopyToContainer(content: String, containerPath: String, mode: Int = "644".toInt(8)): GenericContainer =
        withCopyToContainer(content.encodeToByteArray(), containerPath, mode)

    // ---------------------------------------------------------------- runtime info

    public val isRunning: Boolean get() = containerId != null

    public val host: String get() = Docker.host

    public fun getExposedPorts(): List<Int> = exposedPorts.toList()

    public fun getMappedPort(containerPort: Int): Int {
        check(containerId != null) { "Container is not started" }
        return mappedPorts[containerPort]
            ?: throw IllegalArgumentException("Port $containerPort is not exposed. Exposed: $exposedPorts")
    }

    public val firstMappedPort: Int get() = getMappedPort(exposedPorts.first())

    private fun requireId(): String = containerId ?: error("Container is not started")

    // ---------------------------------------------------------------- lifecycle

    /** Hook for subclasses to adjust configuration right before the container is created. */
    protected open fun beforeStart() {}

    /** Hook for subclasses, called once the container is ready. */
    protected open suspend fun containerIsStarted() {}

    public suspend fun start() {
        if (containerId != null) return
        beforeStart()
        val client = Docker.client
        ResourceReaper.ensureStarted()
        val runImage = imageBuilder?.let { it.build(); image } ?: Docker.imageNameSubstitutor.substitute(image).also {
            Docker.ensureImage(it, pullPolicy)
        }
        resolvedImage = runImage

        val ports = exposedPorts.map { "$it/tcp" }
        val networkName = network?.let { it.ensureCreated(); it.name }
        val request = ContainerCreateRequest(
            image = runImage,
            cmd = command,
            entrypoint = entrypoint,
            env = env.map { (k, v) -> "$k=$v" }.ifEmpty { null },
            labels = labels + Docker.defaultLabels,
            exposedPorts = ports.associateWith { JsonObject(emptyMap()) }.ifEmpty { null },
            workingDir = workingDir,
            user = user,
            hostname = hostname,
            hostConfig = HostConfig(
                portBindings = exposedPorts.associate {
                    // "0" (like `docker run -p 0:5000`), not "": Docker Desktop then also publishes the port inside its
                    // VM, so the daemon itself can reach it (e.g. pushing to a registry started by the test).
                    "$it/tcp" to listOf(PortBinding(hostPort = fixedPorts[it]?.toString() ?: "0"))
                }.ifEmpty { null },
                binds = binds.ifEmpty { null },
                privileged = privileged.takeIf { it },
                networkMode = networkName,
                tmpfs = tmpfs.ifEmpty { null },
            ),
            networkingConfig = networkName?.let {
                NetworkingConfig(mapOf(it to EndpointConfig(aliases = networkAliases.ifEmpty { null })))
            },
        )

        val id = client.createContainer(request, name)
        containerId = id
        try {
            if (filesToCopy.isNotEmpty()) client.putArchive(id, "/", buildTar(filesToCopy))
            client.startContainer(id)
            mappedPorts = resolveMappedPorts(id)
            waitUntilReady()
            containerIsStarted()
        } catch (e: Throwable) {
            val logs = runCatching { client.logs(id).let { it.stdout + it.stderr } }.getOrDefault("<no logs>")
            withContext(NonCancellable) { runCatching { client.removeContainer(id) } }
            containerId = null
            throw ContainerLaunchException("Container $image failed to start: ${e.message}\n--- container logs ---\n$logs", e)
        }
    }

    private suspend fun resolveMappedPorts(id: String): Map<Int, Int> {
        // Port bindings may appear slightly after start; retry a few times.
        repeat(20) {
            val ports = Docker.client.inspectContainer(id).networkSettings?.ports.orEmpty()
            val result = exposedPorts.mapNotNull { port ->
                val bindings = ports["$port/tcp"].orEmpty().filter { !it.hostPort.isNullOrEmpty() }
                val binding = bindings.firstOrNull { it.hostIp == "0.0.0.0" } ?: bindings.firstOrNull()
                binding?.hostPort?.toInt()?.let { port to it }
            }.toMap()
            if (result.size == exposedPorts.size) return result
            delay(100)
        }
        throw DockerException("Not all exposed ports were mapped for container $id")
    }

    private suspend fun waitUntilReady() {
        val strategy = waitStrategy ?: if (exposedPorts.isNotEmpty()) Wait.forListeningPort() else Wait.noWait()
        val progress = WaitProgress()
        val ready = withTimeoutOrNull(startupTimeout) {
            withContext(progress) {
                coroutineScope {
                    // Fail fast if the container exits while we are waiting.
                    val watcher = launch {
                        while (true) {
                            delay(500)
                            val state = Docker.client.inspectContainer(requireId()).state
                            if (state != null && !state.running && state.status in setOf("exited", "dead")) {
                                throw ContainerLaunchException(
                                    "Container exited with code ${state.exitCode} ${state.error.orEmpty()}".trim() +
                                        " while waiting for ${progress.describe()}",
                                )
                            }
                        }
                    }
                    strategy.waitUntilReady(this@GenericContainer)
                    watcher.cancel()
                }
            }
            true
        }
        if (ready == null) {
            throw ContainerLaunchException(
                "Timed out after $startupTimeout waiting for ${progress.describe()}",
                progress.lastFailure,
            )
        }
    }

    public suspend fun stop() {
        val id = containerId ?: return
        withContext(NonCancellable) {
            Docker.client.removeContainer(id, force = true)
        }
        containerId = null
        mappedPorts = emptyMap()
    }

    // ---------------------------------------------------------------- interaction

    /**
     * Runs [command] in the running container and waits for it to finish, however long that takes
     * (bounded by [timeout] when given).
     *
     * @param user user (`name`, `uid`, `uid:gid`) to run as; defaults to the container's user.
     * @param env extra environment variables.
     * @param workingDir working directory of the command.
     * @param stdin input fed to the command; the stream is closed afterwards, so the command sees EOF.
     *   Over a Windows named pipe it is passed through a temporary file instead (needs `/bin/sh`).
     * @throws ExecTimeoutException when [timeout] passes first (the command is not killed inside the container).
     */
    public suspend fun execInContainer(
        command: List<String>,
        user: String? = null,
        env: Map<String, String> = emptyMap(),
        workingDir: String? = null,
        stdin: ByteArray? = null,
        timeout: Duration = Duration.INFINITE,
    ): ExecResult {
        val request = ExecCreateRequest(
            cmd = command,
            env = env.map { (k, v) -> "$k=$v" }.ifEmpty { null },
            user = user,
            workingDir = workingDir,
        )
        val id = requireId()
        val output = withTimeoutOrNull(timeout) { Docker.client.exec(id, request, stdin) }
            ?: throw ExecTimeoutException("Command $command did not finish within $timeout")
        return ExecResult(output.exitCode, output.stdout, output.stderr)
    }

    public suspend fun execInContainer(vararg command: String): ExecResult = execInContainer(command.toList())

    public suspend fun execInContainer(
        command: List<String>,
        stdin: String,
        user: String? = null,
        env: Map<String, String> = emptyMap(),
        workingDir: String? = null,
        timeout: Duration = Duration.INFINITE,
    ): ExecResult = execInContainer(command, user, env, workingDir, stdin.encodeToByteArray(), timeout)

    /** Reads a regular file from the container (binary-safe). */
    public suspend fun copyFileFromContainer(containerPath: String): ByteArray {
        val entries = readTar(Docker.client.getArchive(requireId(), containerPath))
        val file = entries.firstOrNull { it.type == '0' || it.type == '7' }
            ?: throw DockerException("$containerPath is not a regular file (archive entries: ${entries.map { "${it.path} [${it.type}]" }})")
        return file.content
    }

    /** Reads every regular file under [containerPath] (a file or a directory), keyed by its path in the archive. */
    public suspend fun copyFilesFromContainer(containerPath: String): Map<String, ByteArray> =
        readTar(Docker.client.getArchive(requireId(), containerPath))
            .filter { it.type == '0' || it.type == '7' }
            .associate { it.path to it.content }

    public suspend fun logs(): ContainerLogs {
        val output = Docker.client.logs(requireId())
        return ContainerLogs(output.stdout, output.stderr)
    }

    /** Copies content into a running container. */
    public suspend fun copyToContainer(content: ByteArray, containerPath: String, mode: Int = "644".toInt(8)) {
        Docker.client.putArchive(requireId(), "/", buildTar(listOf(TarEntry(containerPath, content, mode))))
    }

    /** Whether the container's HEALTHCHECK reports `healthy`. */
    public suspend fun isHealthy(): Boolean = healthStatus() == "healthy"

    /** HEALTHCHECK status (`starting`, `healthy`, `unhealthy`), or null when the image defines none. */
    public suspend fun healthStatus(): String? = Docker.client.inspectContainer(requireId()).state?.health?.status

    /** IP address of the container inside its (first) network. */
    public suspend fun containerIpAddress(): String? =
        Docker.client.inspectContainer(requireId()).networkSettings?.networks?.values?.firstOrNull()?.ipAddress

    override fun toString(): String = "GenericContainer(image=$image, id=${containerId?.take(12)})"
}

/** Starts the container, runs [block] and always removes the container afterwards. */
public suspend inline fun <C : GenericContainer, R> C.use(block: (C) -> R): R {
    start()
    try {
        return block(this)
    } finally {
        stop()
    }
}
