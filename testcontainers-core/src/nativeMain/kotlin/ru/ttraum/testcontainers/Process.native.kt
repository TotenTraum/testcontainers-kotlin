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
import kotlin.time.Duration

/**
 * Kotlin/Native has no process API, so this goes through the shell (`popen`): stdin comes from a temporary
 * file, stdout is read from the pipe. Words are quoted for `/bin/sh` or `cmd.exe`; [timeout] is not enforced.
 */
internal actual suspend fun runProcess(
    command: List<String>,
    stdin: String,
    env: Map<String, String>,
    mergeStderr: Boolean,
    timeout: Duration,
): ProcessResult? = withContext(Dispatchers.IO) {
    val input = Path(SystemTemporaryDirectory, "tc-stdin-${Random.nextLong().toULong()}")
    SystemFileSystem.sink(input).buffered().use { it.writeString(stdin) }
    try {
        val pipe = openPipe(shellLine(command, env, input.toString(), mergeStderr)) ?: return@withContext null
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

internal fun shellLine(command: List<String>, env: Map<String, String>, stdinFile: String, mergeStderr: Boolean, windows: Boolean = isWindows): String {
    val quote: (String) -> String = if (windows) ::quoteCmd else ::quoteSh
    val stderr = if (mergeStderr) " 2>&1" else if (windows) " 2>NUL" else " 2>/dev/null"
    val prefix = if (windows) {
        // `set "K=V"` keeps trailing characters out of the value; the line must not start with a quote.
        env.entries.joinToString("") { (k, v) -> "set \"$k=$v\"&& " }
    } else {
        env.entries.joinToString("") { (k, v) -> quoteSh("$k=$v") + " " }.let { if (it.isEmpty()) it else "env $it" }
    }
    return prefix + command.joinToString(" ") { quote(it) } + " < " + quote(stdinFile) + stderr
}

private val SAFE = Regex("[A-Za-z0-9_./:=@%+,-]+")

private fun quoteSh(word: String): String = if (SAFE.matches(word)) word else "'" + word.replace("'", "'\\''") + "'"

private fun quoteCmd(word: String): String =
    if (SAFE.matches(word.replace('\\', '/'))) word else "\"" + word.replace("\"", "\\\"") + "\""

/** `popen(command, "r")`; `_popen` on Windows. */
internal expect fun openPipe(command: String): CPointer<FILE>?

/** `pclose`; `_pclose` on Windows. */
internal expect fun closePipe(pipe: CPointer<FILE>): Int
