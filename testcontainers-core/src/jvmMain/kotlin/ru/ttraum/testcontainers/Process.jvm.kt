package ru.ttraum.testcontainers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

internal actual suspend fun runProcess(command: List<String>, stdin: String): ProcessResult? = withContext(Dispatchers.IO) {
    val process = try {
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (_: IOException) {
        return@withContext null // not installed / not on PATH
    }
    try {
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext null
        }
        ProcessResult(process.exitValue(), out)
    } finally {
        process.destroy()
    }
}
