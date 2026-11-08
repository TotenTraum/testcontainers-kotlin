package ru.ttraum.testcontainers.internal

import io.ktor.utils.io.ByteReadChannel

/** A request/response exchange over a duplex stream: the request is fully written, then [input] is read. */
internal interface Exchange {
    val input: ByteReadChannel
    fun close()
}

/**
 * Opens a Windows named pipe (e.g. `\\.\pipe\docker_engine`), writes [request] and returns the response stream.
 *
 * Writing completes before reading starts: with synchronous (non-overlapped) handles Windows serialises
 * I/O on one handle, so a pending read would block the write.
 */
internal expect suspend fun namedPipeExchange(path: String, request: ByteArray): Exchange
