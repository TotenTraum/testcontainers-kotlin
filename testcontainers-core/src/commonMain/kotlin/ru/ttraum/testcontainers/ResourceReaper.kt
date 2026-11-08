package ru.ttraum.testcontainers

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import ru.ttraum.testcontainers.internal.ContainerCreateRequest
import ru.ttraum.testcontainers.internal.HostConfig
import ru.ttraum.testcontainers.internal.PortBinding
import ru.ttraum.testcontainers.internal.SocketTarget
import ru.ttraum.testcontainers.internal.Sockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the Ryuk sidecar which removes every container/network labelled with our session id
 * once this process dies (and its TCP connection to Ryuk drops). Works the same on JVM and Native,
 * because there are no shutdown hooks in Kotlin/Native.
 *
 * Disable with `TESTCONTAINERS_RYUK_DISABLED=true`. Skipped automatically for Windows-containers daemons.
 */
internal object ResourceReaper {
    private const val RYUK_PORT = 8080
    private val image: String
        get() = Docker.imageNameSubstitutor.substitute(TestcontainersConfig.get("ryuk.container.image") ?: "testcontainers/ryuk:0.14.0")

    private val mutex = Mutex()
    private var started = false

    // Keep strong references: the connection must stay open for the lifetime of the process.
    private var socket: Socket? = null
    private var read: ByteReadChannel? = null
    private var write: ByteWriteChannel? = null

    val disabled: Boolean
        get() = TestcontainersConfig.getBoolean("ryuk.disabled")

    suspend fun ensureStarted() {
        if (disabled) return
        mutex.withLock {
            if (started) return
            // Ryuk is a Linux image: a Windows-containers daemon cannot run it. Resources are then
            // removed only by stop()/use {}; leftovers can be found by the org.testcontainers label.
            if (!Docker.isWindowsDaemon()) start()
            started = true
        }
    }

    private suspend fun start() {
        val client = Docker.client
        Docker.ensureImage(image)
        val port = "$RYUK_PORT/tcp"
        val id = client.createContainer(
            ContainerCreateRequest(
                image = image,
                labels = mapOf(Docker.LABEL_BASE to "true", "org.testcontainers.ryuk" to "true"),
                exposedPorts = mapOf(port to JsonObject(emptyMap())),
                hostConfig = HostConfig(
                    portBindings = mapOf(port to listOf(PortBinding(hostPort = ""))),
                    binds = listOf("${Docker.endpoint.socketPathForMount()}:/var/run/docker.sock"),
                    autoRemove = true,
                    privileged = TestcontainersConfig.getBoolean("ryuk.container.privileged"),
                ),
            ),
            name = "testcontainers-ryuk-${Docker.sessionId}",
        )
        client.startContainer(id)
        val mapped = client.inspectContainer(id).networkSettings?.ports?.get(port)
            ?.firstOrNull { !it.hostPort.isNullOrEmpty() }?.hostPort?.toInt()
            ?: throw DockerException("Ryuk port $port is not mapped")

        val filter = "label=${Docker.LABEL_SESSION_ID}=${Docker.sessionId}"
        val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        var lastError: Throwable? = null
        while (deadline.hasNotPassedNow()) {
            try {
                val s = Sockets.connect(SocketTarget.Tcp(Docker.host, mapped))
                val w = s.openWriteChannel(autoFlush = true)
                val r = s.openReadChannel()
                w.writeStringUtf8("$filter\n")
                val ack = r.readLine()
                if (ack == "ACK") {
                    socket = s; read = r; write = w
                    return
                }
                s.close()
                lastError = DockerException("Unexpected Ryuk response: $ack")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(200.milliseconds)
        }
        throw DockerException("Could not connect to Ryuk on ${Docker.host}:$mapped", cause = lastError)
    }
}
