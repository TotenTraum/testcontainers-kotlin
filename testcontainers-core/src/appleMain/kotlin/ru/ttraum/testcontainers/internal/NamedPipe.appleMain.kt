package ru.ttraum.testcontainers.internal

import ru.ttraum.testcontainers.DockerException

internal actual suspend fun namedPipeExchange(path: String, request: ByteArray): Exchange =
    throw DockerException("Named pipes ($path) are only available on Windows")
