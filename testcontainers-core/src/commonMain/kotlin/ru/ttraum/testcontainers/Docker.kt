package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.DockerClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Process-wide Docker state: the client, the session id and the resource reaper. */
public object Docker {
    /** Unique id of this test session; every resource we create is labelled with it. */
    public val sessionId: String = Uuid.random().toString()

    public const val LABEL_BASE: String = "org.testcontainers"
    public const val LABEL_SESSION_ID: String = "org.testcontainers.sessionId"

    public val endpoint: DockerEndpoint by lazy { DockerEndpoint.resolve() }

    internal val client: DockerClient by lazy { DockerClient(endpoint) }

    /** Host on which mapped ports are reachable. */
    public val host: String get() = endpoint.containerHost()

    internal val defaultLabels: Map<String, String>
        get() = mapOf(LABEL_BASE to "true", LABEL_SESSION_ID to sessionId)

    private val pullMutex = Mutex()

    internal suspend fun ensureImage(image: String, alwaysPull: Boolean = false) = pullMutex.withLock {
        if (alwaysPull || !client.imageExists(image)) client.pullImage(image)
    }

    private var cachedOs: String? = null

    /** OS of the daemon (`linux`, or `windows` for a Docker engine running Windows containers). */
    public suspend fun serverOs(): String = cachedOs ?: client.serverOs().also { cachedOs = it }

    public suspend fun isWindowsDaemon(): Boolean = serverOs().equals("windows", ignoreCase = true)

    /** Checks connectivity with the daemon. */
    public suspend fun isAvailable(): Boolean = runCatching { client.ping() == "OK" }.getOrDefault(false)
}
