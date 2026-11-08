package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.wait.Wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a real Windows container through `\\.\pipe\docker_engine`.
 *
 * Needs a Docker engine in Windows-containers mode (e.g. GitHub's `windows-2025` runner, or Docker Desktop
 * switched to Windows containers); skipped otherwise. The nanoserver tag must match the host's Windows
 * build for process isolation: override it with `TC_WINDOWS_TEST_IMAGE` (e.g. `...:ltsc2022`).
 */
class WindowsContainerTest {
    private val image = getEnv("TC_WINDOWS_TEST_IMAGE")
        ?: "mcr.microsoft.com/windows/nanoserver:ltsc2025"

    @Test
    fun runsNanoserverContainer() = dockerTest {
        if (!Docker.isAvailable() || !Docker.isWindowsDaemon()) {
            println("SKIPPED: no Docker daemon in Windows-containers mode")
            return@dockerTest
        }
        val container = GenericContainer(image) {
            withCommand("cmd", "/c", "echo hello-from-windows && ping -n 600 127.0.0.1 >NUL")
            waitingFor(Wait.forLogMessage(".*hello-from-windows.*"))
            withStartupTimeout(5.minutes) // first pull of nanoserver is slow
        }
        container.use { c ->
            assertTrue(c.logs().stdout.contains("hello-from-windows"))
            val result = c.execInContainer("cmd", "/c", "echo out& echo err 1>&2& exit /b 3")
            assertEquals(3, result.exitCode)
            assertEquals("out", result.stdout.trim())
            assertEquals("err", result.stderr.trim())
        }
        assertFalse(container.isRunning)
    }
}
