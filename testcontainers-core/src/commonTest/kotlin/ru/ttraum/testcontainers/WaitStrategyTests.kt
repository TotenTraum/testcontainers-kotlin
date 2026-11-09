package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.images.ImageFromDockerfile
import ru.ttraum.testcontainers.wait.Wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WaitStrategyTests {
    /** busybox httpd: /health says UP, /secret needs basic auth (tc:pw). */
    private fun web(configure: GenericContainer.() -> Unit = {}) = GenericContainer("busybox:1.37") {
        withExposedPorts(80)
        withCopyToContainer("UP\n", "/w/health")
        withCopyToContainer("hidden\n", "/w/secret/x.txt")
        withCopyToContainer("/secret:tc:pw\n", "/etc/httpd.conf")
        withCommand("httpd", "-f", "-p", "80", "-h", "/w", "-c", "/etc/httpd.conf")
        configure()
    }

    @Test
    fun listeningPortWithoutShellInImage() = linuxContainerTest {
        // No /bin/sh: the in-container check cannot run, the host-side one decides.
        val image = ImageFromDockerfile().withDockerfileFromString(
            """
            FROM busybox:1.37-musl AS tools
            FROM scratch
            COPY --from=tools /bin/busybox /busybox
            CMD ["/busybox", "httpd", "-f", "-p", "8080", "-h", "/"]
            """.trimIndent(),
        )
        val started = TimeSource.Monotonic.markNow()
        GenericContainer(image) { withExposedPorts(8080); waitingFor(Wait.forListeningPort()) }.use { c ->
            assertTrue(c.isRunning)
            assertTrue(started.elapsedNow() < 30.seconds)
        }
    }

    @Test
    fun httpBuilder() = linuxContainerTest {
        web {
            waitingFor(
                Wait.forHttp("/health")
                    .forPort(80)
                    .forStatusCodeMatching { it in 200..299 }
                    .forResponsePredicate { "UP" in it }
                    .withHeader("X-Test", "1"),
            )
        }.use { assertTrue(it.isRunning) }
    }

    @Test
    fun httpBasicCredentialsAndStatusCodes() = linuxContainerTest {
        web { waitingFor(Wait.forHttp("/secret/x.txt").withBasicCredentials("tc", "pw").forResponsePredicate { "hidden" in it }) }
            .use { assertTrue(it.isRunning) }
        web { waitingFor(Wait.forHttp("/secret/x.txt").forStatusCode(401)) }.use { assertTrue(it.isRunning) }
    }

    @Test
    fun httpTimeoutExplainsTheMismatch() = linuxContainerTest {
        val e = assertFailsWith<ContainerLaunchException> {
            web {
                waitingFor(Wait.forHttp("/health").forResponsePredicate { "DOWN" in it })
                withStartupTimeout(3.seconds)
            }.start()
        }
        assertTrue("the body did not match: UP" in e.message.orEmpty(), e.message)
    }

    @Test
    fun successfulCommand() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withCommand("sh", "-c", "sleep 2; touch /ready; sleep 300")
            waitingFor(Wait.forSuccessfulCommand("test", "-f", "/ready"))
        }.use { c -> assertEquals(0, c.execInContainer("test", "-f", "/ready").exitCode) }

        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sleep", "300")
                waitingFor(Wait.forSuccessfulCommand("sh", "-c", "echo not-yet >&2; exit 2"))
                withStartupTimeout(2.seconds)
            }.start()
        }
        assertTrue("exit code 2: not-yet" in e.message.orEmpty(), e.message)
    }

    @Test
    fun perStrategyTimeout() = linuxContainerTest {
        val started = TimeSource.Monotonic.markNow()
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sh", "-c", "echo first; sleep 300")
                waitingFor(Wait.allOf(Wait.forLogMessage("first"), Wait.forLogMessage("never").withStartupTimeout(2.seconds)))
                withStartupTimeout(60.seconds)
            }.start()
        }
        assertTrue(started.elapsedNow() < 30.seconds, "took ${started.elapsedNow()}")
        assertTrue("Timed out after 2s waiting for log message 'never'" in e.message.orEmpty(), e.message)
    }

    /** The blocking API outside any coroutine (what Java and plain JUnit code use). */
    @Test
    fun blockingApi() {
        if (!Docker.isAvailableBlocking() || Docker.isWindowsDaemonBlocking()) return
        val consumer = ToStringConsumer()
        val c = GenericContainer("alpine:latest") {
            withCommand("sh", "-c", "echo blocking-ready; sleep 300")
            withLogConsumer(consumer)
            waitingFor(Wait.forLogMessage("blocking-ready"))
        }
        c.useBlocking {
            assertEquals("hi", it.execInContainerBlocking("echo", "hi").stdout.trim())
            assertEquals("piped", it.execInContainerBlocking(listOf("cat"), stdin = "piped").stdout)
            it.copyFileToContainerBlocking(MountableFile.forString("x"), "/x.txt")
            assertEquals("x", it.copyFileFromContainerBlocking("/x.txt").decodeToString())
            assertTrue("blocking-ready" in it.logsBlocking().stdout)
        }
        assertTrue(!c.isRunning)
        assertTrue("blocking-ready" in consumer.stdout())
    }
}
