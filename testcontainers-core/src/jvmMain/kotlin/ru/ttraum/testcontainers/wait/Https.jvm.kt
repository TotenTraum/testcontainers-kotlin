package ru.ttraum.testcontainers.wait

import kotlinx.coroutines.future.await
import ru.ttraum.testcontainers.internal.HttpResponse
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val verifyingClient: HttpClient by lazy { HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build() }

/** Trusts every certificate; as an X509ExtendedTrustManager it also takes over (and skips) the host name check. */
private object TrustAll : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private val insecureClient: HttpClient by lazy {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) }
    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(context).build()
}

internal actual suspend fun httpsRequest(
    host: String,
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: ByteArray?,
    insecure: Boolean,
    timeout: Duration,
): HttpResponse {
    val query = path.substringAfter('?', "").ifEmpty { null }
    val uri = URI("https", null, host, port, path.substringBefore('?'), query, null)
    val request = HttpRequest.newBuilder(uri)
        // The JDK aborts the exchange itself; cancelling the coroutine alone would leave it running.
        .apply { if (timeout.isFinite()) timeout(timeout.toJavaDuration()) }
        .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    val client = if (insecure) insecureClient else verifyingClient
    val response = client.sendAsync(request, BodyHandlers.ofByteArray()).await()
    return HttpResponse(response.statusCode(), response.headers().map().mapValues { it.value.joinToString(",") }, response.body())
}
