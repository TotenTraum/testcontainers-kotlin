package ru.ttraum.testcontainers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toDuration

/** runTest uses virtual time; containers need the real clock, so hop to Dispatchers.Default. */
fun dockerTest(block: suspend () -> Unit) = runTest(timeout = 5.minutes) {
    withContext(Dispatchers.Default) { block() }
}

/** Like [dockerTest], but skipped (with a note) when the daemon does not run Linux containers. */
fun linuxContainerTest(block: suspend () -> Unit) = dockerTest {
    if (Docker.isAvailable() && Docker.isWindowsDaemon()) {
        println("SKIPPED: the Docker daemon runs Windows containers, this test needs Linux containers")
        return@dockerTest
    }
    block()
}

/** Re-checks [condition] (real time) until it holds, failing after [seconds]. */
suspend fun pollFor(seconds: Int = 20, condition: suspend () -> Boolean) {
    val deadline = kotlin.time.TimeSource.Monotonic.markNow() + seconds.toDuration(kotlin.time.DurationUnit.SECONDS)
    while (!condition()) {
        if (deadline.hasPassedNow()) throw AssertionError("condition not met within ${seconds}s")
        kotlinx.coroutines.delay(200)
    }
}

/** A TCP port that is free on this host right now. */
fun freeTcpPort(): Int = runBlockingCompat {
    val server = io.ktor.network.sockets.aSocket(ru.ttraum.testcontainers.internal.Sockets.selector).tcp().bind("0.0.0.0", 0)
    try {
        (server.localAddress as io.ktor.network.sockets.InetSocketAddress).port
    } finally {
        server.close()
    }
}
