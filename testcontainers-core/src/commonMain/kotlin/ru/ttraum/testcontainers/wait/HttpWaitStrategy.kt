package ru.ttraum.testcontainers.wait

import kotlinx.coroutines.withTimeoutOrNull
import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.internal.HttpResponse
import ru.ttraum.testcontainers.internal.RawHttpClient
import ru.ttraum.testcontainers.internal.SocketTarget
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Waits until an HTTP(S) request to the container gets the expected answer. Built by [Wait.forHttp]:
 *
 * ```
 * Wait.forHttp("/health")
 *     .forPort(8080)
 *     .forStatusCodeMatching { it in 200..299 }
 *     .forResponsePredicate { "UP" in it }
 *     .withHeader("Accept", "application/json")
 * ```
 *
 * HTTPS ([usingTls]) is available on the JVM only for now.
 */
public class HttpWaitStrategy internal constructor(private val path: String) : WaitStrategy {
    private var port: Int? = null
    private val statusCodes = linkedSetOf<Int>()
    private var statusPredicate: ((Int) -> Boolean)? = null
    private var responsePredicate: ((String) -> Boolean)? = null
    private var method: String = "GET"
    private val headers = linkedMapOf<String, String>()
    private var body: ByteArray? = null
    private var readTimeout: Duration = 5.seconds
    private var tls: Boolean = false
    private var insecure: Boolean = false

    /** Container port to call (default: the first exposed port). */
    public fun forPort(port: Int): HttpWaitStrategy = apply { this.port = port }

    /** Accepts [code]; may be called several times. Without any, 200 is expected. */
    public fun forStatusCode(code: Int): HttpWaitStrategy = apply { statusCodes += code }

    /** Accepts status codes matching [predicate] (in addition to those given to [forStatusCode]). */
    public fun forStatusCodeMatching(predicate: (Int) -> Boolean): HttpWaitStrategy = apply { statusPredicate = predicate }

    /** Also requires the response body to satisfy [predicate]. */
    public fun forResponsePredicate(predicate: (String) -> Boolean): HttpWaitStrategy = apply { responsePredicate = predicate }

    public fun withMethod(method: String): HttpWaitStrategy = apply { this.method = method.uppercase() }

    public fun withHeader(name: String, value: String): HttpWaitStrategy = apply { headers[name] = value }

    public fun withBasicCredentials(username: String, password: String): HttpWaitStrategy =
        withHeader("Authorization", "Basic " + Base64.encode("$username:$password".encodeToByteArray()))

    public fun withBody(body: String, contentType: String = "application/json"): HttpWaitStrategy = apply {
        this.body = body.encodeToByteArray()
        headers["Content-Type"] = contentType
    }

    /** Limit for one request (default 5 s); a slower answer counts as "not ready". */
    public fun withReadTimeout(timeout: Duration): HttpWaitStrategy = apply { readTimeout = timeout }

    /** Uses HTTPS (JVM only), verifying the certificate unless [allowInsecure] is set. */
    public fun usingTls(): HttpWaitStrategy = apply { tls = true }

    /** Accepts any certificate and host name, e.g. a container's self-signed one. Implies [usingTls]. */
    public fun allowInsecure(): HttpWaitStrategy = apply { tls = true; insecure = true }

    override suspend fun waitUntilReady(container: GenericContainer) {
        val containerPort = port ?: container.getExposedPorts().firstOrNull()
            ?: throw IllegalStateException("forHttp needs a port: expose one or call forPort()")
        val scheme = if (tls) "https" else "http"
        pollUntil("$method $scheme://…:$containerPort$path to answer ${describeExpectation()}") {
            val mapped = container.getMappedPort(containerPort)
            val host = container.host
            val response = withTimeoutOrNull(readTimeout) {
                if (tls) {
                    httpsRequest(host, mapped, method, path, headers, body, insecure, readTimeout)
                } else {
                    RawHttpClient(SocketTarget.Tcp(host, mapped), hostHeader = "$host:$mapped").execute(method, path, body, headers)
                }
            } ?: throw NotReadyException("no answer within $readTimeout")
            if (!statusAccepted(response.status)) throw NotReadyException("got HTTP ${response.status}")
            val predicate = responsePredicate
            if (predicate != null && !predicate(response.bodyText())) {
                throw NotReadyException("HTTP ${response.status}, but the body did not match: ${response.bodyText().take(200)}")
            }
            true
        }
    }

    private fun statusAccepted(status: Int): Boolean {
        val predicate = statusPredicate
        if (statusCodes.isEmpty() && predicate == null) return status == 200
        return status in statusCodes || (predicate != null && predicate(status))
    }

    private fun describeExpectation(): String = buildString {
        append(if (statusCodes.isEmpty() && statusPredicate == null) "200" else statusCodes.joinToString("/"))
        if (statusPredicate != null) append(if (statusCodes.isEmpty()) "a matching status" else " or a matching status")
        if (responsePredicate != null) append(" with a matching body")
    }
}

/** HTTPS request for readiness checks; JVM uses the JDK client, other platforms report it unsupported. */
internal expect suspend fun httpsRequest(
    host: String,
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: ByteArray?,
    insecure: Boolean,
    timeout: Duration,
): HttpResponse
