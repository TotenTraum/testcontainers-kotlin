package ru.ttraum.testcontainers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import ru.ttraum.testcontainers.images.ImageFromDockerfile
import ru.ttraum.testcontainers.images.PullPolicy
import ru.ttraum.testcontainers.internal.ContainerCreateRequest
import ru.ttraum.testcontainers.internal.DockerJson
import ru.ttraum.testcontainers.internal.EndpointConfig
import ru.ttraum.testcontainers.internal.ExecCreateRequest
import ru.ttraum.testcontainers.internal.HostConfig
import ru.ttraum.testcontainers.internal.NetworkingConfig
import ru.ttraum.testcontainers.internal.PortBinding
import ru.ttraum.testcontainers.internal.TarEntry
import ru.ttraum.testcontainers.internal.Ulimit
import ru.ttraum.testcontainers.internal.buildTar
import ru.ttraum.testcontainers.internal.readTar
import ru.ttraum.testcontainers.internal.stableHash
import ru.ttraum.testcontainers.wait.Wait
import ru.ttraum.testcontainers.wait.WaitProgress
import ru.ttraum.testcontainers.wait.WaitStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    private val exposedTcp = linkedSetOf<Int>()
    private val exposedUdp = linkedSetOf<Int>()
    private val fixedPorts = linkedMapOf<Int, Int>()
    private val env = linkedMapOf<String, String>()
    private val labels = linkedMapOf<String, String>()
    private val binds = mutableListOf<String>()
    private val namedVolumes = mutableListOf<Triple<String, String, Boolean>>()
    private val volumesFrom = mutableListOf<Pair<GenericContainer, Boolean>>()
    private val tmpfs = linkedMapOf<String, String>()
    private val filesToCopy = mutableListOf<TarEntry>()
    private val networkAliases = mutableListOf<String>()
    private val extraHosts = mutableListOf<String>()
    private val dns = mutableListOf<String>()
    private val ulimits = mutableListOf<Ulimit>()
    private val capAdd = linkedSetOf<String>()
    private val capDrop = linkedSetOf<String>()
    private val createModifiers = mutableListOf<(JsonObject) -> JsonObject>()
    private val dependencies = linkedSetOf<GenericContainer>()
    private val logConsumers = mutableListOf<LogConsumer>()
    private var command: List<String>? = null
    private var entrypoint: List<String>? = null
    private var workingDir: String? = null
    private var user: String? = null
    private var hostname: String? = null
    private var privileged: Boolean = false
    private var readOnlyRootfs: Boolean = false
    private var init: Boolean = false
    private var memory: Long? = null
    private var memorySwap: Long? = null
    private var nanoCpus: Long? = null
    private var shmSize: Long? = null
    private var platform: String? = null
    private var network: Network? = null
    private var networkMode: String? = null
    private var name: String? = null
    private var pullPolicy: PullPolicy = PullPolicy.defaultPolicy()
    private var imageBuilder: ImageFromDockerfile? = null
    private var waitStrategy: WaitStrategy? = null
    private var startupCheck: StartupCheckStrategy = StartupCheckStrategy.IsRunning
    private var startupTimeout: Duration = 60.seconds
    private var startupAttempts: Int = 1
    private var reuse: Boolean = false

    public var containerId: String? = null
        private set

    /** Whether [start] attached to an already running container instead of creating one (see [withReuse]). */
    public var isReused: Boolean = false
        private set

    /** Exit code of a one-shot container ([StartupCheckStrategy.OneShot]) once it has run. */
    public var exitCode: Int? = null
        private set

    private var mappedPorts: Map<String, Int> = emptyMap()
    private val startMutex = Mutex()
    private var logScope: CoroutineScope? = null
    private val logJobs = mutableListOf<Job>()

    init {
        configure()
    }

    // ---------------------------------------------------------------- configuration

    public fun withExposedPorts(vararg ports: Int): GenericContainer = apply { exposedTcp += ports.toList() }

    /** Exposes UDP ports; read their host ports with [getMappedUdpPort]. */
    public fun withExposedUdpPorts(vararg ports: Int): GenericContainer = apply { exposedUdp += ports.toList() }

    /**
     * Binds a container port to a fixed host port. Prefer [withExposedPorts] with random ports. On Docker Desktop
     * a fixed port is also reachable by the daemon itself (e.g. to push to a registry the test started), a random one is not.
     */
    public fun withFixedExposedPort(hostPort: Int, containerPort: Int): GenericContainer = apply {
        exposedTcp += containerPort
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

    /** Platform of the image and container, e.g. `linux/amd64` (pulled for that platform if needed). */
    public fun withPlatform(platform: String): GenericContainer = apply { this.platform = platform }

    public fun withNetwork(network: Network): GenericContainer = apply { this.network = network }
    public fun withNetworkAliases(vararg aliases: String): GenericContainer = apply { networkAliases += aliases }

    /**
     * Docker network mode: `host`, `none`, `bridge` or `container:<id>`. Overrides [withNetwork].
     * With `host`, ports are not mapped: [getMappedPort] returns the container port itself.
     */
    public fun withNetworkMode(mode: String): GenericContainer = apply { networkMode = mode }

    /** Adds `hostname:ip` to the container's `/etc/hosts`; `ip` may be `host-gateway`. */
    public fun withExtraHost(hostname: String, ip: String): GenericContainer = apply { extraHosts += "$hostname:$ip" }

    /**
     * Lets the container reach services on the test host as `host.testcontainers.internal`
     * (through Docker's `host-gateway`). On Docker Desktop this reaches the host's own ports; on Linux the
     * service must listen on an address the Docker bridge can reach (e.g. `0.0.0.0`).
     */
    public fun withAccessToHost(): GenericContainer = withExtraHost(HOST_INTERNAL, "host-gateway")

    public fun withDns(vararg servers: String): GenericContainer = apply { dns += servers }
    public fun withTmpFs(path: String, options: String = "rw"): GenericContainer = apply { tmpfs[path] = options }
    public fun waitingFor(strategy: WaitStrategy): GenericContainer = apply { waitStrategy = strategy }
    public fun withStartupTimeout(timeout: Duration): GenericContainer = apply { startupTimeout = timeout }
    public fun withStartupCheckStrategy(strategy: StartupCheckStrategy): GenericContainer = apply { startupCheck = strategy }

    /** Tries to start the container up to [attempts] times (a failed attempt's container is removed). */
    public fun withStartupAttempts(attempts: Int): GenericContainer = apply {
        require(attempts >= 1) { "attempts must be at least 1" }
        startupAttempts = attempts
    }

    /** Starts [containers] (concurrently) before this one. */
    public fun dependsOn(vararg containers: GenericContainer): GenericContainer = apply { dependencies += containers }

    // resources and security

    /** Memory limit in bytes; [swap] is memory + swap in bytes (`-1` for unlimited swap). */
    public fun withMemoryLimit(bytes: Long, swap: Long? = null): GenericContainer = apply {
        memory = bytes
        memorySwap = swap
    }

    /** CPU limit, e.g. `1.5` CPUs. */
    public fun withCpus(cpus: Double): GenericContainer = apply { nanoCpus = (cpus * 1_000_000_000).toLong() }

    /** Size of `/dev/shm` in bytes (databases such as PostgreSQL may need more than the default 64 MB). */
    public fun withSharedMemorySize(bytes: Long): GenericContainer = apply { shmSize = bytes }

    public fun withUlimit(name: String, soft: Long, hard: Long = soft): GenericContainer = apply { ulimits += Ulimit(name, soft, hard) }
    public fun withCapabilities(vararg add: String): GenericContainer = apply { capAdd += add }
    public fun withoutCapabilities(vararg drop: String): GenericContainer = apply { capDrop += drop }
    public fun withReadOnlyRootFilesystem(enabled: Boolean = true): GenericContainer = apply { readOnlyRootfs = enabled }

    /** Runs an init process (tini) as PID 1, which reaps zombies and forwards signals. */
    public fun withInit(enabled: Boolean = true): GenericContainer = apply { init = enabled }

    /**
     * Edits the raw `POST /containers/create` body before it is sent, for anything this API does not cover
     * (see the Docker Engine API reference). Applied in order of registration.
     */
    public fun withCreateContainerModifier(modifier: (JsonObject) -> JsonObject): GenericContainer = apply { createModifiers += modifier }

    // files and volumes

    /** Bind-mounts a host path: `mode` is `rw` or `ro`. */
    public fun withFileSystemBind(hostPath: String, containerPath: String, mode: String = "rw"): GenericContainer =
        apply { binds += "$hostPath:$containerPath:$mode" }

    /** Mounts a named volume (created if missing, labelled for cleanup with the session). */
    public fun withNamedVolume(volumeName: String, containerPath: String, readOnly: Boolean = false): GenericContainer =
        apply { namedVolumes += Triple(volumeName, containerPath, readOnly) }

    /** Mounts all volumes of [container] (started first). */
    public fun withVolumesFrom(container: GenericContainer, readOnly: Boolean = false): GenericContainer = apply {
        volumesFrom += container to readOnly
        dependencies += container
    }

    /** Copies content into the container before it starts. */
    public fun withCopyToContainer(content: ByteArray, containerPath: String, mode: Int = "644".toInt(8)): GenericContainer =
        apply { filesToCopy += TarEntry(containerPath, content, mode) }

    public fun withCopyToContainer(content: String, containerPath: String, mode: Int = "644".toInt(8)): GenericContainer =
        withCopyToContainer(content.encodeToByteArray(), containerPath, mode)

    /** Copies a host file/directory (or bytes, or on the JVM a classpath resource) to [containerPath] before start. */
    public fun withCopyFileToContainer(file: MountableFile, containerPath: String): GenericContainer =
        apply { filesToCopy += file.toTarEntries(containerPath) }

    // output and reuse

    /** Feeds the container's output to [consumer] from start until the container stops. */
    public fun withLogConsumer(consumer: LogConsumer): GenericContainer = apply { logConsumers += consumer }

    /**
     * Keeps the container after the test and reuses it in later runs with the same configuration.
     * Only active when reuse is enabled on the machine: `testcontainers.reuse.enable=true` in
     * `~/.testcontainers.properties` or `TESTCONTAINERS_REUSE_ENABLE=true`. A reused container is not
     * removed by Ryuk or by [use]; [stop] still removes it.
     */
    public fun withReuse(enabled: Boolean = true): GenericContainer = apply { reuse = enabled }

    /** Whether [withReuse] is requested and reuse is enabled on this machine. */
    public val isReuseActive: Boolean
        get() = reuse && (TestcontainersConfig.getBoolean("testcontainers.reuse.enable") ||
            getEnv("TESTCONTAINERS_REUSE_ENABLE")?.lowercase() in setOf("true", "1", "yes"))

    // ---------------------------------------------------------------- runtime info

    public val isRunning: Boolean get() = containerId != null

    public val host: String get() = Docker.host

    /** Exposed TCP ports. */
    public fun getExposedPorts(): List<Int> = exposedTcp.toList()

    public fun getExposedUdpPorts(): List<Int> = exposedUdp.toList()

    public fun getMappedPort(containerPort: Int): Int = mappedPort(containerPort, "tcp", exposedTcp)

    public fun getMappedUdpPort(containerPort: Int): Int = mappedPort(containerPort, "udp", exposedUdp)

    private fun mappedPort(port: Int, protocol: String, exposed: Set<Int>): Int {
        check(containerId != null) { "Container is not started" }
        if (startupCheck == StartupCheckStrategy.OneShot) error("A one-shot container has no mapped ports")
        return mappedPorts["$port/$protocol"]
            ?: throw IllegalArgumentException("$protocol port $port is not exposed. Exposed: $exposed")
    }

    public val firstMappedPort: Int get() = getMappedPort(exposedTcp.first())

    private fun requireId(): String = containerId ?: error("Container is not started")

    // ---------------------------------------------------------------- lifecycle hooks

    /** Hook for subclasses to adjust configuration right before the container is created. */
    protected open fun beforeStart() {}

    /** Hook: the container exists (files copied) but is not started yet. */
    protected open suspend fun containerIsCreated(containerId: String) {}

    /** Hook: the container process has started; startup checks and the wait strategy follow. */
    protected open suspend fun containerIsStarting(reused: Boolean) {}

    /** Hook, called once the container is ready. */
    protected open suspend fun containerIsStarted() {}

    /** Hook: [stop] is about to remove the container. */
    protected open suspend fun containerIsStopping() {}

    /** Hook: the container has been removed. */
    protected open suspend fun containerIsStopped() {}

    // ---------------------------------------------------------------- start

    /** Starts the container (and its [dependsOn] dependencies); does nothing if already started. */
    public suspend fun start(): Unit = startMutex.withLock {
        if (containerId != null) return
        if (dependencies.isNotEmpty()) startAll(dependencies)
        beforeStart()
        ResourceReaper.ensureStarted()
        val runImage = imageBuilder?.let { it.build(); image } ?: Docker.imageNameSubstitutor.substitute(image).also {
            Docker.ensureImage(it, pullPolicy, platform)
        }
        resolvedImage = runImage

        val reusing = isReuseActive
        val sessionLabels = if (reusing) mapOf(Docker.LABEL_BASE to "true") else Docker.defaultLabels
        network?.takeIf { networkMode == null }?.ensureCreated()
        for ((volume, _, _) in namedVolumes) Docker.client.createVolume(volume, sessionLabels)
        val archive = filesToCopy.takeIf { it.isNotEmpty() }?.let(::buildTar)

        var body = createBody(runImage, labels + sessionLabels)
        var hash: String? = null
        if (reusing) {
            hash = stableHash(body.toString().encodeToByteArray(), archive ?: ByteArray(0), (name ?: "").encodeToByteArray(), (platform ?: "").encodeToByteArray())
            body = withLabel(body, LABEL_HASH, hash)
            val existing = Docker.client.listContainers(mapOf("label" to listOf("$LABEL_HASH=$hash"), "status" to listOf("running")))
            if (existing.isNotEmpty()) {
                adopt(existing.first())
                return
            }
        } else if (reuse) {
            logWarn("withReuse() is ignored: enable it with testcontainers.reuse.enable=true in ~/.testcontainers.properties")
        }

        var failure: Throwable? = null
        for (attempt in 1..startupAttempts) {
            try {
                startOnce(body, archive)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                if (attempt < startupAttempts) logWarn("Container $runImage failed to start (attempt $attempt of $startupAttempts): ${e.message?.lineSequence()?.first()}")
            }
        }
        throw failure!!
    }

    private fun createBody(runImage: String, allLabels: Map<String, String>): JsonObject {
        val hostNetwork = networkMode == "host"
        val portKeys = exposedTcp.map { "$it/tcp" } + exposedUdp.map { "$it/udp" }
        val networkName = if (networkMode == null) network?.name else null
        val request = ContainerCreateRequest(
            image = runImage,
            cmd = command,
            entrypoint = entrypoint,
            env = env.map { (k, v) -> "$k=$v" }.ifEmpty { null },
            labels = allLabels,
            exposedPorts = if (hostNetwork) null else portKeys.associateWith { JsonObject(emptyMap()) }.ifEmpty { null },
            workingDir = workingDir,
            user = user,
            hostname = hostname,
            hostConfig = HostConfig(
                portBindings = if (hostNetwork) null else portKeys.associateWith { key ->
                    val port = key.substringBefore('/').toInt()
                    val fixed = if (key.endsWith("/tcp")) fixedPorts[port] else null
                    // Random ports, as Docker Desktop 28 handles them reliably: "" for TCP ("0" leaves about one container
                    // in ten without a published port), "0" for UDP ("" publishes UDP on port 0, i.e. not at all).
                    val random = if (key.endsWith("/udp")) "0" else ""
                    listOf(PortBinding(hostPort = fixed?.toString() ?: random))
                }.ifEmpty { null },
                binds = (binds + namedVolumes.map { (n, p, ro) -> "$n:$p" + if (ro) ":ro" else "" }).ifEmpty { null },
                privileged = privileged.takeIf { it },
                networkMode = networkMode ?: networkName,
                tmpfs = tmpfs.ifEmpty { null },
                memory = memory,
                memorySwap = memorySwap,
                nanoCpus = nanoCpus,
                shmSize = shmSize,
                ulimits = ulimits.ifEmpty { null },
                capAdd = capAdd.toList().ifEmpty { null },
                capDrop = capDrop.toList().ifEmpty { null },
                extraHosts = extraHosts.ifEmpty { null },
                dns = dns.ifEmpty { null },
                volumesFrom = volumesFrom.map { (c, ro) -> c.requireId() + if (ro) ":ro" else ":rw" }.ifEmpty { null },
                readonlyRootfs = readOnlyRootfs.takeIf { it },
                init = init.takeIf { it },
            ),
            networkingConfig = networkName?.let {
                NetworkingConfig(mapOf(it to EndpointConfig(aliases = networkAliases.ifEmpty { null })))
            },
        )
        val encoded = DockerJson.encodeToJsonElement(request) as JsonObject
        return createModifiers.fold(encoded) { body, modifier -> modifier(body) }
    }

    private fun withLabel(body: JsonObject, key: String, value: String): JsonObject {
        val labels = (body["Labels"] as? JsonObject).orEmpty() + (key to JsonPrimitive(value))
        return JsonObject(body + ("Labels" to JsonObject(labels)))
    }

    private suspend fun startOnce(body: JsonObject, archive: ByteArray?) {
        val client = Docker.client
        val id = client.createContainer(body, name, platform)
        containerId = id
        try {
            if (archive != null) client.putArchive(id, "/", archive)
            containerIsCreated(id)
            client.startContainer(id)
            val startedAt = TimeSource.Monotonic.markNow()
            startLogConsumers(id)
            containerIsStarting(reused = false)
            when (val check = startupCheck) {
                StartupCheckStrategy.OneShot -> {
                    val code = withTimeoutOrNull(startupTimeout) { client.waitContainer(id) }
                        ?: throw ContainerLaunchException("One-shot container did not exit within $startupTimeout")
                    exitCode = code
                    if (code != 0) throw ContainerLaunchException("One-shot container exited with code $code")
                }
                else -> {
                    mappedPorts = resolveMappedPorts(id)
                    waitUntilReady()
                    if (check is StartupCheckStrategy.MinimumDuration) {
                        val remaining = check.duration - startedAt.elapsedNow()
                        if (remaining.isPositive()) delay(remaining)
                        val state = client.inspectContainer(id).state
                        if (state?.running != true) {
                            throw ContainerLaunchException("Container stopped (exit code ${state?.exitCode}) before running for ${check.duration}")
                        }
                    }
                }
            }
            containerIsStarted()
            logInfo("Container ${resolvedImage ?: image} started: ${id.take(12)}")
        } catch (e: Throwable) {
            val logs = withContext(NonCancellable) {
                val logs = runCatching { client.logs(id).let { it.stdout + it.stderr } }.getOrDefault("<no logs>")
                stopLogConsumers()
                runCatching { client.removeContainer(id) }
                logs
            }
            containerId = null
            mappedPorts = emptyMap()
            if (e is CancellationException) throw e
            throw ContainerLaunchException("Container $image failed to start: ${e.message}\n--- container logs ---\n$logs", e)
        }
    }

    /** Attaches to a running container found by its reuse hash. */
    private suspend fun adopt(id: String) {
        containerId = id
        isReused = true
        try {
            mappedPorts = resolveMappedPorts(id)
            startLogConsumers(id)
            containerIsStarting(reused = true)
            waitUntilReady()
            containerIsStarted()
            logInfo("Reusing container ${id.take(12)} for ${resolvedImage ?: image}")
        } catch (e: Throwable) {
            withContext(NonCancellable) { stopLogConsumers() }
            containerId = null
            isReused = false
            if (e is CancellationException) throw e
            throw ContainerLaunchException("Reused container ${id.take(12)} is not ready: ${e.message}", e)
        }
    }

    /**
     * Attaches to a container someone else created (a Compose service): resolves the exposed ports, unless
     * [portOverrides] (`"80/tcp"` to a host port, e.g. through an ambassador) gives them, then waits until ready.
     */
    internal suspend fun attach(id: String, portOverrides: Map<String, Int> = emptyMap()): Unit = startMutex.withLock {
        containerId = id
        try {
            val missing = (exposedTcp.map { "$it/tcp" } + exposedUdp.map { "$it/udp" }).filter { it !in portOverrides }
            mappedPorts = portOverrides + (if (missing.isEmpty()) emptyMap() else resolveMappedPorts(id, missing))
            startLogConsumers(id)
            waitUntilReady()
        } catch (e: Throwable) {
            withContext(NonCancellable) { stopLogConsumers() }
            containerId = null
            mappedPorts = emptyMap()
            throw e
        }
    }

    /** Forgets an [attach]ed container without removing it. */
    internal suspend fun detach() {
        if (containerId == null) return
        withContext(NonCancellable) {
            withTimeoutOrNull(1.seconds) { logJobs.toList().joinAll() }
            stopLogConsumers()
        }
        containerId = null
        mappedPorts = emptyMap()
    }

    private suspend fun resolveMappedPorts(
        id: String,
        keys: List<String> = exposedTcp.map { "$it/tcp" } + exposedUdp.map { "$it/udp" },
    ): Map<String, Int> {
        if (networkMode == "host") return keys.associateWith { it.substringBefore('/').toInt() }
        // Port bindings may appear a while after start, especially on a busy Docker Desktop.
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (true) {
            val inspect = Docker.client.inspectContainer(id)
            val ports = inspect.networkSettings?.ports.orEmpty()
            val result = keys.mapNotNull { key ->
                val bindings = ports[key].orEmpty().filter { !it.hostPort.isNullOrEmpty() }
                val binding = bindings.firstOrNull { it.hostIp == "0.0.0.0" } ?: bindings.firstOrNull()
                binding?.hostPort?.toInt()?.let { key to it }
            }.toMap()
            if (result.size == keys.size) return result
            val state = inspect.state
            if (state != null && !state.running && state.status in setOf("exited", "dead")) {
                throw ContainerLaunchException("Container exited with code ${state.exitCode} before its ports were mapped")
            }
            if (deadline.hasPassedNow()) {
                val seen = ports.entries.joinToString { (k, v) -> "$k=" + v.orEmpty().joinToString(prefix = "[", postfix = "]") { "${it.hostIp}:${it.hostPort}" } }
                throw DockerException(
                    "Ports ${keys - result.keys} of container ${id.take(12)} were not mapped within 10s " +
                        "(state ${state?.status}; daemon reports: ${seen.ifEmpty { "no ports" }})",
                )
            }
            delay(100)
        }
    }

    private suspend fun waitUntilReady() {
        val strategy = waitStrategy ?: if (exposedTcp.isNotEmpty()) Wait.forListeningPort() else Wait.noWait()
        val progress = WaitProgress()
        val ready = withTimeoutOrNull(startupTimeout) {
            withContext(progress) {
                coroutineScope {
                    // Fail fast if the container exits while we are waiting.
                    val watcher = launch {
                        while (true) {
                            val state = Docker.client.inspectContainer(requireId()).state
                            if (state != null && !state.running && state.status in setOf("exited", "dead")) {
                                throw ContainerLaunchException(
                                    "Container exited with code ${state.exitCode} ${state.error.orEmpty()}".trim() +
                                        " while waiting for ${progress.describe()}",
                                )
                            }
                            delay(500)
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

    // ---------------------------------------------------------------- stop

    /** Removes the container (also a reused one). */
    public suspend fun stop() {
        val id = containerId ?: return
        withContext(NonCancellable) {
            runCatching { containerIsStopping() }
            Docker.client.removeContainer(id, force = true)
            // Let consumers receive the last output, then end the streams.
            withTimeoutOrNull(1.seconds) { logJobs.toList().joinAll() }
            stopLogConsumers()
            containerId = null
            isReused = false
            mappedPorts = emptyMap()
            runCatching { containerIsStopped() }
        }
    }

    // ---------------------------------------------------------------- output

    /** The container's output as it is produced; with [follow] the flow ends when the container stops. */
    public fun logStream(follow: Boolean = true): Flow<OutputFrame> = Docker.client.logStream(requireId(), follow)

    /** [logStream] split into lines. */
    public fun logLines(follow: Boolean = true): Flow<LogLine> = logStream(follow).lines()

    /** Starts feeding output to [consumer] until the container stops; cancel the job to stop earlier. */
    public fun followOutput(consumer: LogConsumer): Job {
        val id = requireId()
        val scope = logScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { logScope = it }
        return scope.launch {
            try {
                Docker.client.logStream(id, follow = true).collect { consumer.accept(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the container went away
            }
        }.also { logJobs += it }
    }

    private fun startLogConsumers(id: String) {
        if (logConsumers.isEmpty()) return
        check(containerId == id)
        logConsumers.forEach { followOutput(it) }
    }

    private fun stopLogConsumers() {
        logScope?.cancel()
        logScope = null
        logJobs.clear()
    }

    public suspend fun logs(): ContainerLogs {
        val output = Docker.client.logs(requireId())
        return ContainerLogs(output.stdout, output.stderr)
    }

    /** Waits until the container exits and returns its exit code. */
    public suspend fun awaitExit(timeout: Duration = Duration.INFINITE): Int {
        val id = requireId()
        return withTimeoutOrNull(timeout) { Docker.client.waitContainer(id) }
            ?: throw ContainerLaunchException("Container did not exit within $timeout")
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

    /** Copies content into a running container. */
    public suspend fun copyToContainer(content: ByteArray, containerPath: String, mode: Int = "644".toInt(8)) {
        Docker.client.putArchive(requireId(), "/", buildTar(listOf(TarEntry(containerPath, content, mode))))
    }

    /** Copies a host file/directory (or bytes, or a classpath resource) into a running container. */
    public suspend fun copyFileToContainer(file: MountableFile, containerPath: String) {
        Docker.client.putArchive(requireId(), "/", buildTar(file.toTarEntries(containerPath)))
    }

    /** Whether the container's HEALTHCHECK reports `healthy`. */
    public suspend fun isHealthy(): Boolean = healthStatus() == "healthy"

    /** HEALTHCHECK status (`starting`, `healthy`, `unhealthy`), or null when the image defines none. */
    public suspend fun healthStatus(): String? = Docker.client.inspectContainer(requireId()).state?.health?.status

    /** IP address of the container inside its (first) network. */
    public suspend fun containerIpAddress(): String? =
        Docker.client.inspectContainer(requireId()).networkSettings?.networks?.values?.firstOrNull()?.ipAddress

    // ---------------------------------------------------------------- blocking API
    //
    // For code outside coroutines (plain test functions, main()). Do not call these from a coroutine:
    // they block the thread.

    public fun startBlocking(): Unit = runBlockingCompat { start() }

    public fun stopBlocking(): Unit = runBlockingCompat { stop() }

    public fun execInContainerBlocking(vararg command: String): ExecResult = runBlockingCompat { execInContainer(*command) }

    public fun execInContainerBlocking(
        command: List<String>,
        stdin: String? = null,
        user: String? = null,
        env: Map<String, String> = emptyMap(),
        workingDir: String? = null,
    ): ExecResult = runBlockingCompat { execInContainer(command, user, env, workingDir, stdin?.encodeToByteArray()) }

    public fun logsBlocking(): ContainerLogs = runBlockingCompat { logs() }

    public fun copyFileFromContainerBlocking(containerPath: String): ByteArray = runBlockingCompat { copyFileFromContainer(containerPath) }

    public fun copyFilesFromContainerBlocking(containerPath: String): Map<String, ByteArray> =
        runBlockingCompat { copyFilesFromContainer(containerPath) }

    public fun copyFileToContainerBlocking(file: MountableFile, containerPath: String): Unit =
        runBlockingCompat { copyFileToContainer(file, containerPath) }

    public fun awaitExitBlocking(): Int = runBlockingCompat { awaitExit() }

    public fun isHealthyBlocking(): Boolean = runBlockingCompat { isHealthy() }

    public fun containerIpAddressBlocking(): String? = runBlockingCompat { containerIpAddress() }

    override fun toString(): String = "GenericContainer(image=$image, id=${containerId?.take(12)})"

    public companion object {
        /** Name under which [withAccessToHost] makes the test host reachable. */
        public const val HOST_INTERNAL: String = "host.testcontainers.internal"

        /** Label holding the configuration hash of a reusable container. */
        public const val LABEL_HASH: String = "org.testcontainers.hash"
    }
}

/**
 * Starts the container, runs [block] and removes the container afterwards
 * (unless it is reused: see [GenericContainer.withReuse]).
 */
public suspend inline fun <C : GenericContainer, R> C.use(block: (C) -> R): R {
    start()
    try {
        return block(this)
    } finally {
        if (!isReuseActive) stop()
    }
}

/**
 * Like [use] for code outside coroutines: starts the container (blocking), runs [block] and removes it.
 */
public inline fun <C : GenericContainer, R> C.useBlocking(block: (C) -> R): R {
    startBlocking()
    try {
        return block(this)
    } finally {
        if (!isReuseActive) stopBlocking()
    }
}
