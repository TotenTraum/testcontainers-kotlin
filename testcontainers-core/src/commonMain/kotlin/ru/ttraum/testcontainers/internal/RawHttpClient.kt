package ru.ttraum.testcontainers.internal

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/** Where a socket connects to: a unix domain socket or a TCP host/port. */
internal sealed interface SocketTarget {
    data class Unix(val path: String) : SocketTarget
    data class Tcp(val host: String, val port: Int) : SocketTarget
    /** Windows named pipe, e.g. `\\.\pipe\docker_engine`. */
    data class NamedPipe(val path: String) : SocketTarget
}

internal object Sockets {
    val selector: SelectorManager by lazy { SelectorManager(Dispatchers.IO) }

    suspend fun connect(target: SocketTarget): Socket = when (target) {
        is SocketTarget.Unix -> aSocket(selector).tcp().connect(UnixSocketAddress(target.path))
        is SocketTarget.Tcp -> aSocket(selector).tcp().connect(target.host, target.port)
        is SocketTarget.NamedPipe -> throw IllegalArgumentException("Use exchange() for named pipes")
    }

    /** Writes [request] and returns the response stream, for any transport. */
    suspend fun exchange(target: SocketTarget, request: ByteArray): Exchange {
        if (target is SocketTarget.NamedPipe) return namedPipeExchange(target.path, request)
        val socket = connect(target)
        try {
            val write = socket.openWriteChannel(autoFlush = false)
            val read = socket.openReadChannel()
            write.writeFully(request)
            write.flush()
            return object : Exchange {
                override val input: ByteReadChannel = read
                override fun close() = socket.close()
            }
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }
}

internal class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = status in 200..299 || status == 101
    fun bodyText(): String = body.decodeToString()
}

/**
 * A deliberately tiny HTTP/1.1 client: one connection per request, `Connection: close`,
 * supports Content-Length, chunked and read-until-EOF bodies (the latter also covers
 * Docker's hijacked/upgraded streams such as `exec start`).
 */
internal class RawHttpClient(
    private val target: SocketTarget,
    private val hostHeader: String = "docker",
) {
    suspend fun execute(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = withContext(Dispatchers.IO) {
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: testcontainers-kmp\r\n")
            if (headers.keys.none { it.equals("Connection", ignoreCase = true) }) {
                append("Connection: close\r\n")
            }
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            if (body != null) {
                append("Content-Length: ").append(body.size).append("\r\n")
            } else if (method == "POST" || method == "PUT") {
                append("Content-Length: 0\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        val exchange = Sockets.exchange(target, if (body != null) head + body else head)
        try {
            val read = exchange.input
            val statusLine = read.readLine() ?: throw IOException("Empty HTTP response from $target")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Malformed HTTP status line: $statusLine")

            val responseHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = read.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) responseHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val responseBody = when {
                method == "HEAD" || status == 204 || status == 304 -> ByteArray(0)
                responseHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(read)
                responseHeaders["content-length"] != null -> {
                    val length = responseHeaders.getValue("content-length").toInt()
                    if (length == 0) ByteArray(0) else read.readByteArray(length)
                }
                else -> readToEnd(read)
            }
            HttpResponse(status, responseHeaders, responseBody)
        } finally {
            exchange.close()
        }
    }

    private suspend fun readToEnd(read: ByteReadChannel): ByteArray {
        val out = ByteArrayBuilder()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n > 0) out.append(buffer.copyOf(n))
        }
        return out.toByteArray()
    }

    private suspend fun readChunked(read: ByteReadChannel): ByteArray {
        val out = ByteArrayBuilder()
        while (true) {
            val sizeLine = read.readLine() ?: break
            val size = sizeLine.substringBefore(';').trim().toInt(16)
            if (size == 0) {
                // trailers
                while (true) {
                    val trailer = read.readLine() ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.append(read.readByteArray(size))
            read.readLine() // CRLF after chunk
        }
        return out.toByteArray()
    }
}

internal class ByteArrayBuilder {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun append(bytes: ByteArray) {
        if (size + bytes.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + bytes.size))
        }
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

internal fun urlEncode(value: String): String = buildString {
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            append(ch)
        } else {
            append('%')
            append("0123456789ABCDEF"[c shr 4])
            append("0123456789ABCDEF"[c and 0xF])
        }
    }
}
