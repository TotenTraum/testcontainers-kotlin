package ru.ttraum.testcontainers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

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
