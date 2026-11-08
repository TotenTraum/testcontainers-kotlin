@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.ttraum.testcontainers

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import platform.posix.fgets
import kotlinx.cinterop.CPointer
import platform.posix.FILE
import kotlin.random.Random

/**
 * Kotlin/Native has no process API, so this goes through the shell (`popen`): stdin comes from a temporary
 * file, stdout is read from the pipe. The command words must be plain (no quoting is done), which holds
 * for `docker-credential-<name> get`.
 */
internal actual suspend fun runProcess(command: List<String>, stdin: String): ProcessResult? = withContext(Dispatchers.IO) {
    require(command.all { word -> word.all { it.isLetterOrDigit() || it in "-_./:\\" } }) { "Unsafe command: $command" }
    val input = Path(SystemTemporaryDirectory, "tc-stdin-${Random.nextLong().toULong()}")
    SystemFileSystem.sink(input).buffered().use { it.writeString(stdin) }
    try {
        val redirect = if (isWindows) " 2>NUL" else " 2>/dev/null"
        val line = command.joinToString(" ") + " < \"" + input.toString() + "\"" + redirect
        val pipe = openPipe(line) ?: return@withContext null
        val out = StringBuilder()
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            while (fgets(buffer, 4096, pipe) != null) out.append(buffer.toKString())
        }
        val status = closePipe(pipe)
        // POSIX returns a wait status (exit code in bits 8..15); the Windows CRT returns the exit code itself.
        val exitCode = if (isWindows) status else (status shr 8) and 0xFF
        ProcessResult(exitCode, out.toString())
    } finally {
        runCatching { SystemFileSystem.delete(input) }
    }
}

/** `popen(command, "r")`; `_popen` on Windows. */
internal expect fun openPipe(command: String): CPointer<FILE>?

/** `pclose`; `_pclose` on Windows. */
internal expect fun closePipe(pipe: CPointer<FILE>): Int
