package ru.ttraum.testcontainers

internal expect fun getEnv(name: String): String?

internal expect val isWindows: Boolean

internal class ProcessResult(val exitCode: Int, val stdout: String)

/**
 * Runs an external program (used for Docker credential helpers) and returns its exit code and stdout,
 * or null if it could not be started. [stdin] is passed to the program.
 */
internal expect suspend fun runProcess(command: List<String>, stdin: String): ProcessResult?
