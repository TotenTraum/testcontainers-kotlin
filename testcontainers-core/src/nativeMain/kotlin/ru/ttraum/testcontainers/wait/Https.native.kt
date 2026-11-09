package ru.ttraum.testcontainers.wait

import ru.ttraum.testcontainers.internal.HttpResponse
import kotlin.time.Duration

internal actual suspend fun httpsRequest(
    host: String,
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: ByteArray?,
    insecure: Boolean,
    timeout: Duration,
): HttpResponse = throw UnsupportedOperationException(
    "HTTPS wait strategies are available on the JVM only for now; on native use Wait.forListeningPort() " +
        "or Wait.forSuccessfulCommand(\"wget\", \"--no-check-certificate\", ...)",
)
