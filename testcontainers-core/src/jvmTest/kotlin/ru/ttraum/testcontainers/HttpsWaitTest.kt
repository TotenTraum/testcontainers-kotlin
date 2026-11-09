package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.images.ImageFromDockerfile
import ru.ttraum.testcontainers.wait.Wait
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HttpsWaitTest {
    /** nginx with a self-signed certificate made in a first build stage (TLS 1.2 and 1.3). */
    private val tlsServer = ImageFromDockerfile("localhost/testcontainers/tls-server:latest")
        .withDockerfileFromString(
            """
            FROM alpine:latest AS certs
            RUN apk add --no-cache openssl && \
                openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=tc-test -keyout /key.pem -out /cert.pem
            FROM nginx:alpine
            COPY --from=certs /cert.pem /key.pem /etc/nginx/tls/
            COPY tls.conf /etc/nginx/conf.d/default.conf
            """.trimIndent(),
        )
        .withFileFromString(
            "tls.conf",
            """
            server {
                listen 8443 ssl;
                ssl_certificate /etc/nginx/tls/cert.pem;
                ssl_certificate_key /etc/nginx/tls/key.pem;
                location / { return 200 "secure-ok"; }
            }
            """.trimIndent(),
        )

    @Test
    fun httpsWithSelfSignedCertificate() = linuxContainerTest {
        GenericContainer(tlsServer) {
            withExposedPorts(8443)
            waitingFor(Wait.forHttps("/").allowInsecure().forResponsePredicate { it == "secure-ok" })
            withStartupTimeout(2.minutes)
        }.use { assertTrue(it.isRunning) }
    }

    @Test
    fun httpsVerifiesCertificatesByDefault() = linuxContainerTest {
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer(tlsServer) {
                withExposedPorts(8443)
                waitingFor(Wait.forHttps("/"))
                withStartupTimeout(4.seconds)
            }.start()
        }
        val message = e.message.orEmpty()
        assertTrue("Timed out" in message && ("PKIX" in message || "certificate" in message || "SSL" in message), message)
    }
}
