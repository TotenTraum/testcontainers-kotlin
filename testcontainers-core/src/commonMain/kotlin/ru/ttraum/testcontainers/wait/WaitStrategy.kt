package ru.ttraum.testcontainers.wait

import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.internal.SocketTarget
import ru.ttraum.testcontainers.internal.Sockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.ttraum.testcontainers.ContainerLaunchException
import ru.ttraum.testcontainers.Docker
import kotlinx.coroutines.flow.firstOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides when a started container is ready. The overall limit is the container's startup timeout;
 * [withStartupTimeout] gives one strategy (e.g. inside [Wait.allOf]) its own.
 */
public fun interface WaitStrategy {
    public suspend fun waitUntilReady(container: GenericContainer)

    /** This strategy, failing on its own after [timeout] with what it waited for. */
    public fun withStartupTimeout(timeout: Duration): WaitStrategy {
        val inner = this
        return WaitStrategy { c ->
            val done = withTimeoutOrNull(timeout) { inner.waitUntilReady(c); true }
            if (done == null) {
                val progress = currentCoroutineContext()[WaitProgress]
                throw ContainerLaunchException(
                    "Timed out after $timeout waiting for ${progress?.describe() ?: "the wait strategy"}",
                    progress?.lastFailure,
                )
            }
        }
    }
}

/** Thrown by a check to say why the container is not ready yet; the reason ends up in the timeout message. */
public class NotReadyException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
                externalCheck(c.host, c.getMappedPort(port))
                internalCheck(c, port)
                true
            }
        }
    }

    /** Waits until a log line matching [regex] has appeared [times] times. */
    public fun forLogMessage(regex: String, times: Int = 1): WaitStrategy = WaitStrategy { c ->
        val pattern = Regex(regex)
        val progress = coroutineContext[WaitProgress]
        progress?.apply { what = "log message '$regex' x$times"; attempts = 0; lastFailure = NotReadyException("found 0 of $times matching lines") }
        // Follows the output as it is produced instead of re-reading all logs.
        var found = 0
        val reached = c.logLines(follow = true).firstOrNull { line ->
            if (pattern.containsMatchIn(line.text)) {
                found++
                progress?.lastFailure = NotReadyException("found $found of $times matching lines")
            }
            found >= times
        }
        if (reached == null) throw NotReadyException("the output ended (container stopped) after $found of $times matching lines")
    }

    /** Waits for Docker's HEALTHCHECK status to become `healthy`. */
    public fun forHealthcheck(): WaitStrategy = WaitStrategy { c ->
        pollUntil("healthcheck") {
            val status = c.healthStatus()
            if (status != "healthy") throw NotReadyException("health status: ${status ?: "none (image has no HEALTHCHECK?)"}")
            true
        }
    }

    /**
     * Waits until an HTTP request to [path] answers as expected; refine it with the returned builder
     * (port, status codes, body predicate, method, headers, credentials, TLS).
     */
    public fun forHttp(path: String = "/", port: Int? = null, statusCode: Int? = null): HttpWaitStrategy =
        HttpWaitStrategy(path).also { s ->
            port?.let(s::forPort)
            statusCode?.let(s::forStatusCode)
        }

    /** Like [forHttp] over HTTPS (JVM only). */
    public fun forHttps(path: String = "/", port: Int? = null): HttpWaitStrategy = forHttp(path, port).usingTls()

    /** Waits until [command] exits with code 0 inside the container (re-run every 200 ms). */
    public fun forSuccessfulCommand(vararg command: String): WaitStrategy = WaitStrategy { c ->
        pollUntil("command ${command.joinToString(" ")} to succeed") {
            val result = c.execInContainer(*command)
            if (result.exitCode != 0) {
                val output = (result.stderr.ifBlank { result.stdout }).trim().take(200)
                throw NotReadyException("exit code ${result.exitCode}" + if (output.isNotEmpty()) ": $output" else "")
            }
            true
        }
    }

    /** Waits for all strategies in order. */
    public fun allOf(vararg strategies: WaitStrategy): WaitStrategy = WaitStrategy { c ->
        strategies.forEach { it.waitUntilReady(c) }
    }

    private suspend fun externalCheck(host: String, port: Int) {
        try {
            Sockets.connect(SocketTarget.Tcp(host, port)).close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NotReadyException("cannot connect to $host:$port: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private suspend fun internalCheck(c: GenericContainer, port: Int) {
        val hex = port.toString(16).uppercase().padStart(4, '0')
        val result = try {
            c.execInContainer("/bin/sh", "-c", "grep -qi ':$hex ' /proc/net/tcp /proc/net/tcp6")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stopped container cannot exec either, and a proxy in front of it (Docker Desktop, a Compose
            // ambassador) still accepts connections: never call that ready.
            if (!isRunning(c)) throw NotReadyException("the container is not running", e)
            return // image without a shell: rely on the external check only
        }
        // 0: listening; 1: not yet; anything else: shell/grep/proc unavailable, rely on the external check
        if (result.exitCode == 1) {
            throw NotReadyException("port $port accepts host connections, but nothing listens on it inside the container yet")
        }
        if (result.exitCode != 0 && !isRunning(c)) throw NotReadyException("the container is not running")
    }

    private suspend fun isRunning(c: GenericContainer): Boolean {
        val id = c.containerId ?: return false
        return runCatching { Docker.client.inspectContainer(id).state?.running }.getOrNull() ?: true
    }
}

/**
 * What the current wait is doing; installed by the container so a timeout can say what it waited for
 * and why it did not get it.
 */
internal class WaitProgress : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WaitProgress>

    var what: String? = null
    var attempts: Int = 0
    var lastFailure: Throwable? = null

    fun describe(): String = buildString {
        append(what ?: "the wait strategy")
        if (attempts > 0) append(" ($attempts attempts)")
        val failure = lastFailure
        if (failure == null) {
            if (attempts > 0) append("; the condition was never met")
        } else {
            append("; last error: ")
            append(failure.message ?: failure::class.simpleName)
        }
    }
}

/**
 * Re-runs [condition] until it returns true. A false result or an exception means "not yet";
 * the last exception is kept so a timeout can report it.
 */
internal suspend fun pollUntil(
    what: String,
    interval: Duration = 200.milliseconds,
    condition: suspend () -> Boolean,
) {
    val progress = coroutineContext[WaitProgress]
    progress?.apply { this.what = what; attempts = 0; lastFailure = null }
    while (true) {
        progress?.let { it.attempts++ }
        val ok = try {
            condition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress?.lastFailure = e
            false
        }
        if (ok) return
        delay(interval)
    }
}
