package ru.ttraum.testcontainers.internal

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import ru.ttraum.testcontainers.DockerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

internal actual suspend fun namedPipeExchange(path: String, request: ByteArray): Exchange {
    val file = withContext(Dispatchers.IO) { openPipe(path) }
    try {
        withContext(Dispatchers.IO) { file.write(request) }
    } catch (e: Throwable) {
        file.close()
        throw e
    }
    val channel = ByteChannel()
    CoroutineScope(Dispatchers.IO).launch {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = try {
                    file.read(buffer)
                } catch (_: IOException) {
                    -1 // the server closed its end of the pipe
                }
                if (n < 0) break
                if (n > 0) {
                    channel.writeFully(buffer, 0, n)
                    channel.flush()
                }
            }
            channel.close()
        } catch (e: Throwable) {
            channel.close(e)
        } finally {
            runCatching { file.close() }
        }
    }
    return object : Exchange {
        override val input: ByteReadChannel = channel
        override fun close() {
            runCatching { file.close() }
        }
    }
}

private suspend fun openPipe(path: String): RandomAccessFile {
    repeat(50) {
        try {
            return RandomAccessFile(path, "rw")
        } catch (e: FileNotFoundException) {
            // "All pipe instances are busy" is reported as FileNotFoundException: retry.
            if (e.message?.contains("busy", ignoreCase = true) != true) {
                throw DockerException("Cannot open named pipe $path: ${e.message}", cause = e)
            }
            delay(100)
        }
    }
    throw DockerException("Named pipe $path stays busy")
}
