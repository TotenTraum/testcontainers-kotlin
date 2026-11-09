package ru.ttraum.testcontainers

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal expect fun getEnv(name: String): String?

internal expect val isWindows: Boolean

internal class ProcessResult(val exitCode: Int, val stdout: String)

/**
 * Runs an external program (Docker credential helpers, `docker compose`) and returns its exit code and stdout,
 * or null if it could not be started (or, on the JVM, did not finish within [timeout]). [stdin] is passed to
 * the program, [env] is added to the inherited environment, and with [mergeStderr] the error output is part
 * of `stdout` (otherwise it is discarded).
 */
internal expect suspend fun runProcess(
    command: List<String>,
    stdin: String = "",
    env: Map<String, String> = emptyMap(),
    mergeStderr: Boolean = false,
    timeout: Duration = 30.seconds,
): ProcessResult?
