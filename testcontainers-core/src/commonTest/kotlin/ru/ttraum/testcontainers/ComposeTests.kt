package ru.ttraum.testcontainers

import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import ru.ttraum.testcontainers.internal.SocketTarget
import ru.ttraum.testcontainers.internal.Sockets
import ru.ttraum.testcontainers.wait.Wait
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ComposeTests {
    private fun composeFile(content: String): String {
        val dir = Path(SystemTemporaryDirectory, "tc-compose-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "compose.yaml")
        SystemFileSystem.sink(file).buffered().use { it.writeString(content.trimIndent()) }
        return file.toString()
    }

    /** Skips when no compose CLI is installed. */
    private fun composeTest(block: suspend () -> Unit) = linuxContainerTest {
        if (runCatching { ComposeContainer.detectCommand() }.isFailure) {
            println("SKIPPED: Docker Compose is not installed")
            return@linuxContainerTest
        }
        block()
    }

    private suspend fun httpGet(host: String, port: Int, path: String = "/"): String {
        val socket = Sockets.connect(SocketTarget.Tcp(host, port))
        try {
            val w = socket.openWriteChannel(autoFlush = true)
            val r = socket.openReadChannel()
            w.writeStringUtf8("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n")
            return buildString { while (true) appendLine(r.readLine() ?: break) }
        } finally {
            socket.close()
        }
    }

    private val webService = """
        services:
          web:
            image: busybox:1.37
            command: ["sh", "-c", "mkdir -p /www && echo ${'$'}{GREETING} > /www/index.html && httpd -f -p 8080 -h /www"]
            environment:
              GREETING: ${'$'}{GREETING:-unset}
          published:
            image: busybox:1.37
            command: ["sh", "-c", "mkdir -p /www && echo published > /www/index.html && httpd -f -p 8081 -h /www"]
            ports: ["8081"]
          worker:
            image: busybox:1.37
            command: ["sleep", "300"]
            deploy:
              replicas: 2
    """

    @Test
    fun startsProjectAndReachesServices() = composeTest {
        val compose = ComposeContainer(composeFile(webService))
            .withEnv("GREETING", "hello-compose")
            .withExposedService("web", 8080, Wait.forHttp("/"))
            .withExposedService("published", 8081)
        val project = compose.projectName
        compose.use {
            // Not published in the file: reached through an ambassador.
            val web = httpGet(it.getServiceHost("web", 8080), it.getServicePort("web", 8080))
            assertTrue(web.contains("hello-compose"), web)
            // Published in the file: the port Compose mapped.
            val published = httpGet(it.getServiceHost("published", 8081), it.getServicePort("published", 8081))
            assertTrue(published.contains("published"), published)

            assertEquals(setOf("web-1", "published-1", "worker-1", "worker-2"), it.serviceContainers)
            assertEquals("hi\n", it.getContainer("worker-2").execInContainer("echo", "hi").stdout)
            assertFailsWith<IllegalArgumentException> { it.getServicePort("worker", 80) }
        }
        val left = Docker.client.listContainers(mapOf("label" to listOf("${ComposeContainer.LABEL_PROJECT}=$project")))
        assertEquals(emptyList(), left, "down removes the project")
        val ambassadors = Docker.client.listContainers(mapOf("label" to listOf("${Docker.LABEL_SESSION_ID}=${Docker.sessionId}"), "ancestor" to listOf("alpine/socat:1.8.0.3")))
        assertEquals(emptyList(), ambassadors, "ambassadors are removed")
    }

    @Test
    fun failingServiceReportsItsLogs() = composeTest {
        val compose = ComposeContainer(
            composeFile(
                """
                services:
                  broken:
                    image: busybox:1.37
                    command: ["sh", "-c", "echo boom-from-service; exit 3"]
                """,
            ),
        ).withExposedService("broken", 80).withStartupTimeout(20.seconds)
        val e = assertFailsWith<ContainerLaunchException> { compose.start() }
        assertTrue(e.message!!.contains("boom-from-service"), e.message)
        assertTrue(!compose.isRunning)
    }

    @Test
    fun invalidComposeFileReportsCliOutput() = composeTest {
        val compose = ComposeContainer(composeFile("services:\n  x:\n    image: [not, a, string]\n"))
        val e = assertFailsWith<ContainerLaunchException> { compose.start() }
        assertTrue(e.message!!.contains("exited with"), e.message)
    }

    @Test
    fun unknownServiceIsReported() = composeTest {
        val compose = ComposeContainer(composeFile("services:\n  a:\n    image: busybox:1.37\n    command: [\"sleep\", \"300\"]\n"))
            .withExposedService("nope", 80)
        val e = assertFailsWith<ContainerLaunchException> { compose.start() }
        assertTrue(e.message!!.contains("nope-1") && e.message!!.contains("a-1"), e.message)
    }
}
