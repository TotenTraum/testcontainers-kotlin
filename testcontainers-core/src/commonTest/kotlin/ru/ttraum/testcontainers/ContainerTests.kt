package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.modules.PostgreSQLContainer
import ru.ttraum.testcontainers.wait.Wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContainerTests {
    @Test
    fun runsCommandAndReadsLogs() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withCommand("sh", "-c", "echo hello-from-kmp && sleep 300")
            waitingFor(Wait.forLogMessage(".*hello-from-kmp.*"))
        }.use { c ->
            assertTrue(c.logs().stdout.contains("hello-from-kmp"))
            val result = c.execInContainer("sh", "-c", "echo out; echo err >&2; exit 3")
            assertEquals(3, result.exitCode)
            assertEquals("out\n", result.stdout)
            assertEquals("err\n", result.stderr)
        }
    }

    @Test
    fun exposesHttpPort() = linuxContainerTest {
        val container = GenericContainer("busybox:1.37") {
            withExposedPorts(8080)
            withCopyToContainer("<h1>kmp</h1>", "/www/index.html")
            withCommand("httpd", "-f", "-p", "8080", "-h", "/www")
            waitingFor(Wait.allOf(Wait.forListeningPort(), Wait.forHttp("/index.html")))
        }
        container.use { c ->
            assertTrue(c.getMappedPort(8080) > 0)
            val body = c.execInContainer("cat", "/www/index.html").stdout
            assertEquals("<h1>kmp</h1>", body)
        }
        assertFalse(container.isRunning)
    }

    @Test
    fun containersTalkOverNetwork() = linuxContainerTest {
        val network = Network()
        try {
            GenericContainer("busybox:1.37") {
                withNetwork(network)
                withNetworkAliases("server")
                withExposedPorts(9000)
                withCommand("sh", "-c", "mkdir /w && echo pong > /w/ping && httpd -f -p 9000 -h /w")
            }.use {
                GenericContainer("alpine:latest") {
                    withNetwork(network)
                    withCommand("sleep", "300")
                }.use { client ->
                    val result = client.execInContainer("wget", "-qO-", "http://server:9000/ping")
                    assertEquals(0, result.exitCode, result.stderr)
                    assertEquals("pong", result.stdout.trim())
                }
            }
        } finally {
            network.remove()
        }
    }

    @Test
    fun postgresModule() = linuxContainerTest {
        PostgreSQLContainer { databaseName = "kmp" }.use { pg ->
            val result = pg.execInContainer("psql", "-U", pg.username, "-d", "kmp", "-tAc", "select 40 + 2")
            assertEquals("42", result.stdout.trim(), result.stderr)
            assertTrue(pg.jdbcUrl.startsWith("jdbc:postgresql://"))
        }
    }

    @Test
    fun failsFastWhenContainerExits() = linuxContainerTest {
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sh", "-c", "echo boom; exit 1")
                waitingFor(Wait.forLogMessage(".*never.*"))
            }.start()
        }
        assertTrue(e.message!!.contains("boom"), e.message)
    }
}
