@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.ttraum.testcontainers.internal

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import ru.ttraum.testcontainers.DockerException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.windows.CancelIoEx
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.ERROR_PIPE_BUSY
import platform.windows.ERROR_PIPE_NOT_CONNECTED
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.WaitNamedPipeW
import platform.windows.WriteFile
import kotlin.concurrent.AtomicInt

internal actual suspend fun namedPipeExchange(path: String, request: ByteArray): Exchange {
    val handle = withContext(Dispatchers.IO) { openPipe(path) }
    val closed = AtomicInt(0)
    fun closeHandle() {
        if (closed.compareAndSet(0, 1)) CloseHandle(handle)
    }

    try {
        withContext(Dispatchers.IO) { writeAll(handle, request) }
    } catch (e: Throwable) {
        closeHandle()
        throw e
    }

    val channel = ByteChannel()
    CoroutineScope(Dispatchers.IO).launch {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = readSome(handle, buffer)
                if (n <= 0) break
                channel.writeFully(buffer, 0, n)
                channel.flush()
            }
            channel.close()
        } catch (e: Throwable) {
            channel.close(e)
        } finally {
            closeHandle()
        }
    }

    return object : Exchange {
        override val input: ByteReadChannel = channel
        override fun close() {
            // Unblock a pending ReadFile; the reader then closes the handle.
            if (closed.value == 0) CancelIoEx(handle, null)
        }
    }
}

private fun openPipe(path: String): HANDLE {
    repeat(50) {
        val handle = CreateFileW(
            path,
            (GENERIC_READ or GENERIC_WRITE.toUInt()),
            0u,
            null,
            OPEN_EXISTING.toUInt(),
            0u,
            null,
        )
        if (handle != null && handle != INVALID_HANDLE_VALUE) return handle
        val error = GetLastError()
        if (error.toInt() != ERROR_PIPE_BUSY) {
            throw DockerException("Cannot open named pipe $path (Win32 error $error). Is Docker Desktop running?")
        }
        WaitNamedPipeW(path, 2000u)
    }
    throw DockerException("Named pipe $path stays busy")
}

private fun writeAll(handle: HANDLE, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    var offset = 0
    bytes.usePinned { pinned ->
        memScoped {
            val written = alloc<DWORDVar>()
            while (offset < bytes.size) {
                val ok = WriteFile(handle, pinned.addressOf(offset), (bytes.size - offset).toUInt(), written.ptr, null)
                if (ok == 0) throw DockerException("WriteFile to named pipe failed (Win32 error ${GetLastError()})")
                offset += written.value.toInt()
            }
        }
    }
}

/** Returns bytes read, or -1 at end of stream (server closed the pipe). */
private fun readSome(handle: HANDLE, buffer: ByteArray): Int = buffer.usePinned { pinned ->
    memScoped {
        val read = alloc<DWORDVar>()
        val ok = ReadFile(handle, pinned.addressOf(0), buffer.size.toUInt(), read.ptr, null)
        if (ok == 0) {
            val error = GetLastError().toInt()
            // The daemon closing its end shows up as BROKEN_PIPE or PIPE_NOT_CONNECTED: both mean EOF.
            if (error == ERROR_BROKEN_PIPE || error == ERROR_PIPE_NOT_CONNECTED) return@memScoped -1
            throw DockerException("ReadFile from named pipe failed (Win32 error $error)")
        }
        val n = read.value.toInt()
        if (n == 0) -1 else n
    }
}
