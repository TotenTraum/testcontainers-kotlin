package ru.ttraum.testcontainers

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.ttraum.testcontainers.wait.Wait
import ru.ttraum.testcontainers.wait.WaitStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A Docker Compose project, run with the `docker compose` CLI (or `docker-compose`, see [withComposeCommand])
 * against the daemon this library uses:
 *
 * ```
 * ComposeContainer("src/test/resources/compose.yaml")
 *     .withExposedService("db", 5432, Wait.forLogMessage(".*ready to accept connections.*", times = 2))
 *     .withExposedService("web", 80, Wait.forHttp("/health"))
 *     .use { compose ->
 *         val url = "http://${compose.getServiceHost("web", 80)}:${compose.getServicePort("web", 80)}"
 *     }
 * ```
 *
 * A service port does not have to be published in the compose file: if it is not, a small socat
 * ambassador container on the service's network forwards a random host port to it (Linux daemons only).
 * The project gets a random name (see [withProjectName]); Ryuk removes its containers, networks and
 * volumes if the process dies before [stop].
 */
public class ComposeContainer(composeFiles: List<String>, configure: ComposeContainer.() -> Unit = {}) {
    public constructor(vararg composeFiles: String) : this(composeFiles.toList())

    private val files = composeFiles.toList().also { require(it.isNotEmpty()) { "At least one compose file is required" } }

    /** The Compose project name (`-p`); every resource of the project is labelled with it. */
    public var projectName: String = "tc-" + Uuid.random().toHexString().take(12)
        private set

    private class ServiceSpec {
        val ports = linkedSetOf<Int>()
        var waitStrategy: WaitStrategy? = null
        val logConsumers = mutableListOf<LogConsumer>()
    }

    private val specs = linkedMapOf<String, ServiceSpec>()
    private val env = linkedMapOf<String, String>()
    private val profiles = mutableListOf<String>()
    private val options = mutableListOf<String>()
    private var build = false
    private var pull: ComposePull? = null
    private var removeVolumes = true
    private var removeImages: ComposeRemoveImages? = null
    private var startupTimeout: Duration = 60.seconds
    private var commandTimeout: Duration = 10.minutes
    private var composeCommand: List<String>? = null

    private val mutex = Mutex()
    private var running = false
    private val containers = linkedMapOf<String, GenericContainer>()
    private val ambassadors = mutableListOf<GenericContainer>()

    init {
        configure()
    }

    // ---------------------------------------------------------------- configuration

    public fun withProjectName(name: String): ComposeContainer = apply {
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(name)) { "Invalid project name '$name': use lowercase letters, digits, '-' and '_'" }
        projectName = name
    }

    /** A variable for the compose CLI's environment, e.g. for `${TAG}` substitution in the compose file. */
    public fun withEnv(key: String, value: String): ComposeContainer = apply { env[key] = value }

    public fun withEnv(variables: Map<String, String>): ComposeContainer = apply { env.putAll(variables) }

    /**
     * Makes [port] of [service] reachable from the host ([getServicePort]) and waits for it with
     * [waitStrategy] (default: the port listens). [service] is a Compose service name, or `name-2` for
     * another replica.
     */
    public fun withExposedService(service: String, port: Int, waitStrategy: WaitStrategy? = null): ComposeContainer = apply {
        spec(service).ports += port
        waitStrategy?.let { spec(service).waitStrategy = it }
    }

    /** How to wait for [service]; replaces the default port check. */
    public fun waitingFor(service: String, strategy: WaitStrategy): ComposeContainer = apply { spec(service).waitStrategy = strategy }

    public fun withLogConsumer(service: String, consumer: LogConsumer): ComposeContainer = apply { spec(service).logConsumers += consumer }

    public fun withProfiles(vararg profiles: String): ComposeContainer = apply { this.profiles += profiles }

    /** Builds images before starting (`up --build`). */
    public fun withBuild(enabled: Boolean = true): ComposeContainer = apply { build = enabled }

    /** `up --pull`; by default Compose pulls only missing images. */
    public fun withPull(policy: ComposePull): ComposeContainer = apply { pull = policy }

    /** Removes named and anonymous volumes on [stop] (`down -v`); on by default. */
    public fun withRemoveVolumes(enabled: Boolean): ComposeContainer = apply { removeVolumes = enabled }

    /** Removes images on [stop] (`down --rmi`). */
    public fun withRemoveImages(images: ComposeRemoveImages): ComposeContainer = apply { removeImages = images }

    /** Extra global options for the compose CLI, e.g. `--compatibility`. */
    public fun withOptions(vararg options: String): ComposeContainer = apply { this.options += options }

    /** How long each service may take to become ready once `up` has returned (default 60s). */
    public fun withStartupTimeout(timeout: Duration): ComposeContainer = apply { startupTimeout = timeout }

    /** Limit for one compose CLI call, including pulls and builds (default 10 minutes). */
    public fun withCommandTimeout(timeout: Duration): ComposeContainer = apply { commandTimeout = timeout }

    /** The compose executable, e.g. `docker-compose`; by default `docker compose`, else `docker-compose`. */
    public fun withComposeCommand(vararg command: String): ComposeContainer = apply {
        require(command.isNotEmpty())
        composeCommand = command.toList()
    }

    private fun spec(service: String) = specs.getOrPut(service) { ServiceSpec() }

    // ---------------------------------------------------------------- runtime

    public val isRunning: Boolean get() = running

    /** Host on which service ports are reachable. */
    public fun getServiceHost(service: String, port: Int): String = container(service).host

    /** Host port for [port] of [service], which must have been declared with [withExposedService]. */
    public fun getServicePort(service: String, port: Int): Int = container(service).getMappedPort(port)

    /**
     * The running container of [service] (`name` is the first replica, `name-2` the second), for exec,
     * logs and file copies. Removing it is up to [stop].
     */
    public fun getContainer(service: String): GenericContainer {
        check(running) { "Compose project is not started" }
        return containers[key(service)]
            ?: throw IllegalArgumentException("No container for service '$service'. Running: ${containers.keys}")
    }

    private fun container(service: String) = getContainer(service)

    /** Services that have a container, as `name-N`. */
    public val serviceContainers: Set<String> get() = containers.keys.toSet()

    // ---------------------------------------------------------------- lifecycle

    /** Runs `up -d`, then waits for the declared services; does nothing if already started. */
    public suspend fun start(): Unit = mutex.withLock {
        if (running) return
        ResourceReaper.registerFilter("label=$LABEL_PROJECT=$projectName")
        val up = buildList {
            add("up"); add("-d"); add("--remove-orphans")
            if (build) add("--build")
            pull?.let { add("--pull"); add(it.value) }
        }
        try {
            compose(up, "start")
            running = true
            attachAll()
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { down() } }
            throw if (e is ContainerLaunchException || e is kotlinx.coroutines.CancellationException) e
            else ContainerLaunchException("Compose project $projectName failed to start: ${e.message}", e)
        }
    }

    /** Runs `down` (with `-v` and `--rmi` as configured) and removes the ambassadors. */
    public suspend fun stop(): Unit = mutex.withLock {
        if (!running) return
        withContext(NonCancellable) { down() }
    }

    private suspend fun down() {
        containers.values.forEach { runCatching { it.detach() } }
        containers.clear()
        ambassadors.forEach { runCatching { it.stop() } }
        ambassadors.clear()
        running = false
        val args = buildList {
            add("down"); add("--remove-orphans")
            if (removeVolumes) add("-v")
            removeImages?.let { add("--rmi"); add(it.value) }
        }
        runCatching { compose(args, "stop") }.onFailure { e ->
            logWarn("docker compose down failed for $projectName (${e.message}); removing its containers directly")
            Docker.client.listContainerSummaries(mapOf("label" to listOf("$LABEL_PROJECT=$projectName")), all = true)
                .forEach { runCatching { Docker.client.removeContainer(it.id, force = true) } }
        }
    }

    private suspend fun attachAll() {
        val found = Docker.client.listContainerSummaries(mapOf("label" to listOf("$LABEL_PROJECT=$projectName")), all = true)
        val byKey = found.associateBy { c ->
            val labels = c.labels.orEmpty()
            "${labels[LABEL_SERVICE]}-${labels[LABEL_NUMBER] ?: "1"}"
        }
        val missing = specs.keys.map(::key).filter { it !in byKey }
        if (missing.isNotEmpty()) {
            throw ContainerLaunchException("No container for ${missing.joinToString()} in project $projectName. Found: ${byKey.keys.sorted()}")
        }
        coroutineScope {
            byKey.map { (key, summary) ->
                async {
                    val spec = specs.entries.firstOrNull { key(it.key) == key }?.value
                    val handle = GenericContainer(summary.image ?: key) {
                        spec?.ports?.let { withExposedPorts(*it.toIntArray()) }
                        spec?.waitStrategy?.let { waitingFor(it) }
                        spec?.logConsumers?.forEach { withLogConsumer(it) }
                        withStartupTimeout(startupTimeout)
                    }
                    if (spec != null && summary.state != "running") {
                        // An ambassador would accept connections for a dead service, so check this first.
                        val inspect = Docker.client.inspectContainer(summary.id)
                        throw ContainerLaunchException(
                            "Service $key of project $projectName is ${summary.state} (exit code ${inspect.state?.exitCode})" + logsOf(key, summary.id),
                        )
                    }
                    val overrides = if (spec == null || spec.ports.isEmpty()) emptyMap() else ambassadorPorts(key, summary.id, spec.ports)
                    try {
                        handle.attach(summary.id, overrides)
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        throw ContainerLaunchException("Service $key of project $projectName is not ready: ${e.message}" + logsOf(key, summary.id), e)
                    }
                    key to handle
                }
            }.awaitAll().forEach { (key, handle) -> containers[key] = handle }
        }
    }

    private suspend fun logsOf(key: String, id: String): String =
        runCatching { Docker.client.logs(id).let { it.stdout + it.stderr } }.getOrNull()
            ?.let { "\n--- $key logs ---\n${it.takeLast(4000)}" }.orEmpty()

    /** Host ports for [ports] that the compose file does not publish, through a socat ambassador. */
    private suspend fun ambassadorPorts(key: String, id: String, ports: Set<Int>): Map<String, Int> {
        val inspect = Docker.client.inspectContainer(id)
        val published = inspect.networkSettings?.ports.orEmpty()
        val unpublished = ports.filter { port -> published["$port/tcp"].orEmpty().none { !it.hostPort.isNullOrEmpty() } }
        if (unpublished.isEmpty()) return emptyMap()
        if (Docker.isWindowsDaemon()) {
            throw ContainerLaunchException("Ports $unpublished of $key are not published; with Windows containers publish them in the compose file")
        }
        val network = inspect.networkSettings?.networks?.keys?.firstOrNull { it !in setOf("host", "none") }
            ?: throw ContainerLaunchException("Service $key is on no bridge network, so its ports $unpublished cannot be forwarded; publish them in the compose file")
        val target = inspect.name?.removePrefix("/") ?: id
        val image = TestcontainersConfig.get("socat.container.image") ?: "alpine/socat:1.8.0.3"
        val forward = unpublished.joinToString(" & ") { "socat TCP-LISTEN:$it,fork,reuseaddr TCP:$target:$it" } + " & wait"
        val ambassador = GenericContainer(image) {
            withNetworkMode(network)
            withLabel(LABEL_AMBASSADOR_FOR, projectName)
            withExposedPorts(*unpublished.toIntArray())
            withEntrypoint("/bin/sh")
            withCommand("-c", forward)
            waitingFor(Wait.forListeningPort())
        }
        ambassadors += ambassador
        ambassador.start()
        return unpublished.associate { "$it/tcp" to ambassador.getMappedPort(it) }
    }

    private suspend fun compose(args: List<String>, what: String): String {
        val command = (composeCommand ?: detectCommand()) + baseArgs() + args
        val result = runProcess(
            command,
            env = env + ("DOCKER_HOST" to Docker.endpoint.toDockerHost()),
            mergeStderr = true,
            timeout = commandTimeout,
        ) ?: throw ContainerLaunchException("Could not run ${command.joinToString(" ")} (not installed, or no result within $commandTimeout)")
        if (result.exitCode != 0) {
            throw ContainerLaunchException(
                "Could not $what compose project $projectName: ${command.joinToString(" ")} exited with ${result.exitCode}\n${result.stdout.trim()}",
            )
        }
        logDebug(result.stdout.trim())
        return result.stdout
    }

    private fun baseArgs(): List<String> = buildList {
        add("--ansi"); add("never")
        add("-p"); add(projectName)
        files.forEach { add("-f"); add(it) }
        profiles.forEach { add("--profile"); add(it) }
        addAll(options)
    }

    override fun toString(): String = "ComposeContainer($projectName, $files)"

    // ---------------------------------------------------------------- blocking

    public fun startBlocking(): Unit = runBlockingCompat { start() }

    public fun stopBlocking(): Unit = runBlockingCompat { stop() }

    public companion object {
        public const val LABEL_PROJECT: String = "com.docker.compose.project"
        public const val LABEL_SERVICE: String = "com.docker.compose.service"
        public const val LABEL_NUMBER: String = "com.docker.compose.container-number"

        /** Set on socat ambassadors, with the project name (not Compose's own label, or `down` would see orphans). */
        public const val LABEL_AMBASSADOR_FOR: String = "org.testcontainers.compose.ambassador-for"

        private var detected: List<String>? = null
        private val detectMutex = Mutex()

        /** `name` → `name-1`; `name-2` stays. */
        private fun key(service: String): String =
            if (Regex(".+-\\d+").matches(service)) service else "$service-1"

        internal suspend fun detectCommand(): List<String> = detectMutex.withLock {
            detected?.let { return it }
            val candidates = listOf(listOf("docker", "compose"), listOf("docker-compose"))
            val found = candidates.firstOrNull { runProcess(it + "version", mergeStderr = true)?.exitCode == 0 }
                ?: throw ContainerLaunchException("Docker Compose is not installed: neither 'docker compose' nor 'docker-compose' works")
            detected = found
            found
        }
    }
}

/** `up --pull` values. */
public enum class ComposePull(internal val value: String) { ALWAYS("always"), MISSING("missing"), NEVER("never") }

/** `down --rmi` values: images without a custom tag (built ones), or all images of the project. */
public enum class ComposeRemoveImages(internal val value: String) { LOCAL("local"), ALL("all") }

/** Starts the project, runs [block] and stops it afterwards. */
public suspend inline fun <R> ComposeContainer.use(block: (ComposeContainer) -> R): R {
    start()
    try {
        return block(this)
    } finally {
        stop()
    }
}

/** Like [use] for code outside coroutines. */
public inline fun <R> ComposeContainer.useBlocking(block: (ComposeContainer) -> R): R {
    startBlocking()
    try {
        return block(this)
    } finally {
        stopBlocking()
    }
}
