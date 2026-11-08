@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.RawHttpClient
import ru.ttraum.testcontainers.internal.SocketTarget
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import platform.windows.CloseHandle
import platform.windows.ConnectNamedPipe
import platform.windows.CreateNamedPipeW
import platform.windows.DWORDVar
import platform.windows.DisconnectNamedPipe
import platform.windows.FlushFileBuffers
import platform.windows.PIPE_ACCESS_DUPLEX
import platform.windows.PIPE_READMODE_BYTE
import platform.windows.PIPE_TYPE_BYTE
import platform.windows.PIPE_WAIT
import platform.windows.ReadFile
import platform.windows.WriteFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the Win32 named-pipe transport against an in-process fake Docker daemon. */
class NamedPipeTest {
    private fun pipeName() = "\\\\.\\pipe\\tc-kmp-test-${Random.nextLong().toULong()}"

    /** Serves one connection: reads the request headers, writes [response], disconnects. Returns the request. */
    private suspend fun serveOnce(pipe: String, ready: CompletableDeferred<Unit>, response: String): String {
        val handle = CreateNamedPipeW(
            pipe, PIPE_ACCESS_DUPLEX.toUInt(), (PIPE_TYPE_BYTE or PIPE_READMODE_BYTE or PIPE_WAIT).toUInt(),
            1u, 65536u, 65536u, 0u, null,
        )
        ready.complete(Unit)
        ConnectNamedPipe(handle, null)
        val request = StringBuilder()
        val buffer = ByteArray(4096)
        memScoped {
            val n = alloc<DWORDVar>()
            while (!request.contains("\r\n\r\n")) {
                val ok = buffer.usePinned { ReadFile(handle, it.addressOf(0), buffer.size.toUInt(), n.ptr, null) }
                if (ok == 0 || n.value == 0u) break
                request.append(buffer.decodeToString(0, n.value.toInt()))
            }
            val bytes = response.encodeToByteArray()
            bytes.usePinned { WriteFile(handle, it.addressOf(0), bytes.size.toUInt(), n.ptr, null) }
        }
        FlushFileBuffers(handle)
        DisconnectNamedPipe(handle)
        CloseHandle(handle)
        return request.toString()
    }

    private suspend fun roundTrip(response: String): Pair<String, ru.ttraum.testcontainers.internal.HttpResponse> =
        coroutineScope {
            val pipe = pipeName()
            val ready = CompletableDeferred<Unit>()
            val server = async(Dispatchers.IO) { serveOnce(pipe, ready, response) }
            ready.await()
            val result = RawHttpClient(SocketTarget.NamedPipe(pipe)).execute("GET", "/_ping")
            server.await() to result
        }

    @Test
    fun chunkedResponse() = dockerTest {
        val (request, response) = roundTrip(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
        )
        assertTrue(request.startsWith("GET /_ping HTTP/1.1\r\n"), request)
        assertEquals(200, response.status)
        assertEquals("hello world", response.bodyText())
    }

    @Test
    fun bodyUntilPipeCloses() = dockerTest {
        // Like Docker's hijacked exec stream: no length, data ends when the server disconnects.
        val (_, response) = roundTrip("HTTP/1.1 101 UPGRADED\r\nUpgrade: tcp\r\n\r\nraw-stream-data")
        assertEquals(101, response.status)
        assertEquals("raw-stream-data", response.bodyText())
    }

    @Test
    fun contentLengthResponse() = dockerTest {
        val (_, response) = roundTrip("HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\n{}")
        assertEquals(404, response.status)
        assertEquals("{}", response.bodyText())
    }
}
