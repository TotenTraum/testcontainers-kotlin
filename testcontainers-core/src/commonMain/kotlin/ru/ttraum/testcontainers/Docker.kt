package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.images.DockerConfigCredentials
import ru.ttraum.testcontainers.images.ImageNameSubstitutor
import ru.ttraum.testcontainers.images.LocalImage
import ru.ttraum.testcontainers.images.PullPolicy
import ru.ttraum.testcontainers.images.RegistryCredentialsProvider
import ru.ttraum.testcontainers.images.registryAuthHeader
import ru.ttraum.testcontainers.images.registryOf
import ru.ttraum.testcontainers.internal.DockerClient
import ru.ttraum.testcontainers.internal.ImageInspect
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Process-wide Docker state: the client, the session id and the resource reaper. */
public object Docker {
    /** Unique id of this test session; every resource we create is labelled with it. */
    public val sessionId: String = Uuid.random().toString()

    public const val LABEL_BASE: String = "org.testcontainers"
    public const val LABEL_SESSION_ID: String = "org.testcontainers.sessionId"

    /** The daemon in use and where it was found ([resolveEndpoint]). */
    public val resolvedEndpoint: ResolvedEndpoint by lazy { resolveEndpoint() }

    public val endpoint: DockerEndpoint get() = resolvedEndpoint.endpoint

    internal val client: DockerClient by lazy { DockerClient(endpoint) }

    /** Host on which mapped ports are reachable. */
    public val host: String get() = endpoint.containerHost()

    internal val defaultLabels: Map<String, String>
        get() = mapOf(LABEL_BASE to "true", LABEL_SESSION_ID to sessionId)

    /** Rewrites image names before use (registry mirrors); see [ImageNameSubstitutor.Default]. */
    public var imageNameSubstitutor: ImageNameSubstitutor = ImageNameSubstitutor.Default

    /** Where registry credentials come from; by default the docker CLI configuration. */
    public var registryCredentials: RegistryCredentialsProvider = DockerConfigCredentials()

    private val pullMutex = Mutex()

    /** Makes sure [image] (already substituted) is available according to [policy]. */
    internal suspend fun ensureImage(image: String, policy: PullPolicy = PullPolicy.defaultPolicy()) = pullMutex.withLock {
        val local = client.inspectImage(image)?.let { LocalImage(it.id, lastUpdated(it)) }
        if (policy.shouldPull(image, local)) pull(image)
    }

    /**
     * Pulls with registry credentials, retrying transient failures (network, 5xx) `pull.retries` times
     * (default 2); "not found" and "denied" fail at once.
     */
    internal suspend fun pull(image: String) {
        val registry = registryOf(image)
        val auth = registryCredentials.credentialsFor(registry)?.let { registryAuthHeader(registry, it) }
        val retries = TestcontainersConfig.get("pull.retries")?.toIntOrNull() ?: 2
        var attempt = 0
        while (true) {
            try {
                client.pullImage(image, auth)
                return
            } catch (e: DockerException) {
                val permanent = e.statusCode in 400..499 || PERMANENT_PULL_ERRORS.any { e.message.orEmpty().contains(it, ignoreCase = true) }
                if (permanent || attempt >= retries) throw e
                attempt++
                delay((attempt * 2).seconds)
            }
        }
    }

    private val PERMANENT_PULL_ERRORS = listOf("not found", "denied", "unauthorized", "manifest unknown", "invalid reference")

    private fun lastUpdated(image: ImageInspect): Instant? {
        fun parse(value: String?) = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.takeIf { it.toEpochMilliseconds() > 0 } // "0001-01-01T00:00:00Z" means never tagged
        return parse(image.metadata?.lastTagTime) ?: parse(image.created)
    }

    private var cachedOs: String? = null

    /** OS of the daemon (`linux`, or `windows` for a Docker engine running Windows containers). */
    public suspend fun serverOs(): String = cachedOs ?: client.serverOs().also { cachedOs = it }

    public suspend fun isWindowsDaemon(): Boolean = serverOs().equals("windows", ignoreCase = true)

    /** Checks connectivity with the daemon. */
    public suspend fun isAvailable(): Boolean = runCatching { client.ping() == "OK" }.getOrDefault(false)
}
