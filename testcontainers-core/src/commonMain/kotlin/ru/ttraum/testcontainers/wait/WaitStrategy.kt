package ru.ttraum.testcontainers.wait

import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.internal.RawHttpClient
import ru.ttraum.testcontainers.internal.SocketTarget
import ru.ttraum.testcontainers.internal.Sockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Decides when a started container is ready. The overall timeout is applied by the container. */
public fun interface WaitStrategy {
    public suspend fun waitUntilReady(container: GenericContainer)
}

public object Wait {
    public fun noWait(): WaitStrategy = WaitStrategy { }

    /**
     * Waits until all exposed ports (or the given ones) accept TCP connections from the host *and*
     * are bound inside the container (checked via `/proc/net/tcp`, which matters on Docker Desktop
     * where the host-side proxy accepts connections before the process is listening).
     */
    public fun forListeningPort(vararg ports: Int): WaitStrategy = WaitStrategy { c ->
        val targets = ports.toList().ifEmpty { c.getExposedPorts() }
        for (port in targets) {
            pollUntil("port $port to listen") {
                externalCheck(c.host, c.getMappedPort(port)) && internalCheck(c, port)
            }
        }
    }

    /** Waits until a log line matching [regex] has appeared [times] times. */
    public fun forLogMessage(regex: String, times: Int = 1): WaitStrategy = WaitStrategy { c ->
        val pattern = Regex(regex)
        pollUntil("log message '$regex'") {
            val lines = c.logs().all.lineSequence()
            lines.count { pattern.matches(it) || pattern.containsMatchIn(it) } >= times
        }
    }

    /** Waits for Docker's HEALTHCHECK status to become `healthy`. */
    public fun forHealthcheck(): WaitStrategy = WaitStrategy { c -> pollUntil("healthcheck") { c.isHealthy() } }

    /** Waits until an HTTP GET to [path] on [port] (default: first exposed) returns [statusCode]. */
    public fun forHttp(path: String = "/", port: Int? = null, statusCode: Int = 200): WaitStrategy =
        WaitStrategy { c ->
            val containerPort = port ?: c.getExposedPorts().first()
            pollUntil("HTTP $statusCode on $path") {
                val mapped = c.getMappedPort(containerPort)
                val client = RawHttpClient(SocketTarget.Tcp(c.host, mapped), hostHeader = "${c.host}:$mapped")
                runCatching { client.execute("GET", path).status == statusCode }.getOrDefault(false)
            }
        }

    /** Waits for all strategies in order. */
    public fun allOf(vararg strategies: WaitStrategy): WaitStrategy = WaitStrategy { c ->
        strategies.forEach { it.waitUntilReady(c) }
    }

    private suspend fun externalCheck(host: String, port: Int): Boolean = try {
        Sockets.connect(SocketTarget.Tcp(host, port)).close()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun internalCheck(c: GenericContainer, port: Int): Boolean {
        val hex = port.toString(16).uppercase().padStart(4, '0')
        val result = try {
            c.execInContainer("/bin/sh", "-c", "grep -qi ':$hex ' /proc/net/tcp /proc/net/tcp6")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true // image without a shell: rely on the external check only
        }
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> true // shell/grep not available or /proc not readable
        }
    }
}

internal suspend fun pollUntil(
    what: String,
    interval: Duration = 200.milliseconds,
    condition: suspend () -> Boolean,
) {
    while (true) {
        val ok = try {
            condition()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) return
        delay(interval)
    }
}
