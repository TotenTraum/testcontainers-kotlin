package ru.ttraum.testcontainers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal actual suspend fun runProcess(
    command: List<String>,
    stdin: String,
    env: Map<String, String>,
    mergeStderr: Boolean,
    timeout: Duration,
): ProcessResult? = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(command)
    builder.environment().putAll(env)
    if (mergeStderr) builder.redirectErrorStream(true) else builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    val process = try {
        builder.start()
    } catch (_: IOException) {
        return@withContext null // not installed / not on PATH
    }
    try {
        coroutineScope {
            // Read while the program runs, so a full pipe cannot block it.
            val out = async { process.inputStream.readBytes().decodeToString() }
            runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } } // it may exit without reading
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                out.await()
                return@coroutineScope null
            }
            ProcessResult(process.exitValue(), out.await())
        }
    } finally {
        process.destroy()
    }
}
