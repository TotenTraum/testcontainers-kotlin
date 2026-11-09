package ru.ttraum.testcontainers

import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.core.toByteArray
import io.ktor.network.sockets.Datagram
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.ttraum.testcontainers.internal.Sockets
import ru.ttraum.testcontainers.wait.Wait
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ContainerApiTests {
    private fun unique() = Random.nextLong().toULong().toString(16)

    @Test
    fun logConsumersAndStreams() = linuxContainerTest {
        val consumer = ToStringConsumer()
        GenericContainer("alpine:latest") {
            withCommand("sh", "-c", "for i in 1 2 3; do echo out-\$i; echo err-\$i >&2; sleep 0.2; done; sleep 300")
            withLogConsumer(consumer)
            waitingFor(Wait.forLogMessage("out-3"))
        }.use { c ->
            val lines = c.logLines(follow = false).toList()
            assertTrue(LogLine(OutputType.STDOUT, "out-2") in lines, lines.toString())
            assertTrue(LogLine(OutputType.STDERR, "err-2") in lines, lines.toString())

            val late = ToStringConsumer()
            val job = c.followOutput(late)
            c.execInContainer("sh", "-c", "echo later > /proc/1/fd/1")
            pollFor { "later" in late.stdout() }
            assertTrue(job.isActive)
        }
        assertEquals("out-1\nout-2\nout-3\n", consumer.stdout().lineSequence().filter { it.startsWith("out") }.joinToString("\n") + "\n")
        assertTrue("err-3" in consumer.stderr())
    }

    @Test
    fun oneShotContainers() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withCommand("sh", "-c", "echo computed-42")
            withStartupCheckStrategy(StartupCheckStrategy.oneShot())
        }.use { c ->
            assertEquals(0, c.exitCode)
            assertEquals("computed-42", c.logs().stdout.trim())
        }
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sh", "-c", "echo broken >&2; exit 3")
                withStartupCheckStrategy(StartupCheckStrategy.oneShot())
            }.start()
        }
        assertTrue("exited with code 3" in e.message.orEmpty() && "broken" in e.message.orEmpty(), e.message)
    }

    @Test
    fun minimumDuration() = linuxContainerTest {
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sh", "-c", "sleep 1")
                withStartupCheckStrategy(StartupCheckStrategy.minimumDuration(4.seconds))
            }.start()
        }
        assertTrue("exited" in e.message.orEmpty() || "stopped" in e.message.orEmpty(), e.message)
        GenericContainer("alpine:latest") {
            withCommand("sleep", "300")
            withStartupCheckStrategy(StartupCheckStrategy.minimumDuration(1.seconds))
        }.use { assertTrue(it.isRunning) }
    }

    @Test
    fun awaitExit() = linuxContainerTest {
        GenericContainer("alpine:latest") { withCommand("sh", "-c", "sleep 1; exit 7") }.use { c ->
            assertEquals(7, c.awaitExit(30.seconds))
        }
    }

    /** The first attempt fails, the second succeeds; a named volume carries the state between attempts. */
    @Test
    fun startupAttemptsAndNamedVolumes() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withNamedVolume("tc-state-${unique()}", "/state")
            withCommand("sh", "-c", "if [ -f /state/tried ]; then echo ready; sleep 300; else touch /state/tried; exit 1; fi")
            waitingFor(Wait.forLogMessage("ready"))
            withStartupAttempts(2)
        }.use { c ->
            assertEquals(0, c.execInContainer("test", "-f", "/state/tried").exitCode)
        }
    }

    @Test
    fun dependenciesAndParallelStart() = linuxContainerTest {
        val network = Network()
        val server = GenericContainer("busybox:1.37") {
            withNetwork(network)
            withNetworkAliases("server")
            withCommand("sh", "-c", "mkdir /w && echo pong > /w/ping && httpd -f -p 80 -h /w")
        }
        fun client() = GenericContainer("alpine:latest") {
            withNetwork(network)
            withCommand("sleep", "300")
            dependsOn(server)
        }
        val a = client()
        val b = client()
        try {
            startAll(a, b)
            assertTrue(server.isRunning)
            val serverId = server.containerId
            assertEquals("pong", a.execInContainer("wget", "-qO-", "http://server/ping").stdout.trim())
            assertEquals("pong", b.execInContainer("wget", "-qO-", "http://server/ping").stdout.trim())
            b.start() // no-op
            assertEquals(serverId, server.containerId)
        } finally {
            stopAll(a, b, server)
            network.remove()
        }
    }

    @Test
    fun runParameters() = linuxContainerTest {
        val marker = unique()
        GenericContainer("alpine:latest") {
            withCommand("sleep", "300")
            withMemoryLimit(64L * 1024 * 1024)
            withCpus(0.5)
            withSharedMemorySize(128L * 1024 * 1024)
            withUlimit("nofile", 1024, 2048)
            withCapabilities("NET_ADMIN")
            withoutCapabilities("CHOWN")
            withExtraHost("example.internal", "10.1.2.3")
            withDns("1.1.1.1")
            withReadOnlyRootFilesystem()
            withTmpFs("/tmp")
            withInit()
            withCreateContainerModifier { body ->
                val labels = (body["Labels"] as JsonObject) + ("tc.test.marker" to JsonPrimitive(marker))
                JsonObject(body + ("Labels" to JsonObject(labels)))
            }
        }.use { c ->
            fun sh(cmd: String) = cmd
            assertEquals("67108864", c.execInContainer("cat", "/sys/fs/cgroup/memory.max").stdout.trim())
            assertEquals("50000 100000", c.execInContainer("cat", "/sys/fs/cgroup/cpu.max").stdout.trim())
            val shm = c.execInContainer("sh", "-c", sh("df -k /dev/shm | tail -1 | awk '{print \$2}'")).stdout.trim()
            assertEquals("131072", shm)
            assertEquals("1024", c.execInContainer("sh", "-c", "ulimit -n").stdout.trim())
            assertTrue("10.1.2.3\texample.internal" in c.execInContainer("cat", "/etc/hosts").stdout)
            assertTrue("nameserver 1.1.1.1" in c.execInContainer("cat", "/etc/resolv.conf").stdout)
            assertTrue(c.execInContainer("touch", "/root-is-read-only").exitCode != 0)
            assertEquals(0, c.execInContainer("touch", "/tmp/tmpfs-is-writable").exitCode)
            assertTrue(c.execInContainer("cat", "/proc/1/comm").stdout.trim() in setOf("docker-init", "tini", "init"))

            val capEff = c.execInContainer("sh", "-c", "grep CapEff /proc/1/status | awk '{print \$2}'").stdout.trim().toULong(16)
            assertTrue(capEff and (1uL shl 12) != 0uL, "NET_ADMIN missing: $capEff")
            assertTrue(capEff and 1uL == 0uL, "CHOWN not dropped: $capEff")

            val labelled = Docker.client.listContainers(mapOf("label" to listOf("tc.test.marker=$marker")))
            assertEquals(listOf(c.containerId), labelled)
        }
    }

    @Test
    fun platform() = linuxContainerTest {
        GenericContainer("alpine:3.19") {
            withPlatform("linux/amd64")
            withCommand("sleep", "300")
        }.use { c ->
            assertEquals("x86_64", c.execInContainer("uname", "-m").stdout.trim())
        }
    }

    @Test
    fun udpPorts() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withExposedUdpPorts(9999)
            withCommand("sh", "-c", "nc -u -l -p 9999 > /tmp/got")
        }.use { c ->
            val port = c.getMappedUdpPort(9999)
            assertTrue(port > 0)
            val socket = aSocket(Sockets.selector).udp().connect(InetSocketAddress("127.0.0.1", port))
            try {
                pollFor {
                    socket.send(Datagram(Buffer().apply { write("hello-udp\n".toByteArray()) }, InetSocketAddress("127.0.0.1", port)))
                    "hello-udp" in c.execInContainer("cat", "/tmp/got").stdout
                }
            } finally {
                socket.close()
            }
        }
    }

    @Test
    fun hostNetworkMode() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withNetworkMode("host")
            withExposedPorts(18080)
            withCommand("sleep", "300")
            waitingFor(Wait.noWait())
        }.use { c ->
            assertEquals(18080, c.getMappedPort(18080))
        }
    }

    @Test
    fun accessToHost() = linuxContainerTest {
        val server = aSocket(Sockets.selector).tcp().bind("0.0.0.0", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            coroutineScope {
                val serving = launch {
                    val socket = server.accept()
                    val read = socket.openReadChannel()
                    val write = socket.openWriteChannel(autoFlush = true)
                    while (true) {
                        val line = read.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    write.writeStringUtf8("HTTP/1.0 200 OK\r\nContent-Length: 15\r\n\r\nhello-from-host")
                    socket.close()
                }
                GenericContainer("alpine:latest") {
                    withAccessToHost()
                    withCommand("sleep", "300")
                }.use { c ->
                    val result = c.execInContainer("wget", "-qO-", "-T", "10", "http://${GenericContainer.HOST_INTERNAL}:$port/")
                    assertEquals("hello-from-host", result.stdout, result.stderr)
                }
                serving.cancel()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun mountableFiles() = linuxContainerTest {
        val dir = Path(SystemTemporaryDirectory, "tc-mount-${unique()}")
        fun write(relative: String, text: String) {
            val p = Path(dir, relative)
            p.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(p).buffered().use { it.writeString(text) }
        }
        write("conf/a.conf", "a=1\n")
        write("bin/tool.sh", "#!/bin/sh\necho tool-ran\n")
        write("single.txt", "single\n")

        GenericContainer("alpine:latest") {
            withCommand("sleep", "300")
            withCopyFileToContainer(MountableFile.forHostPath(dir.toString()), "/opt/app")
            withCopyFileToContainer(MountableFile.forHostPath(Path(dir, "single.txt").toString()), "/etc/single.txt")
            withCopyFileToContainer(MountableFile.forString("inline\n", "600".toInt(8)), "/etc/inline.txt")
        }.use { c ->
            assertEquals("a=1", c.execInContainer("cat", "/opt/app/conf/a.conf").stdout.trim())
            assertEquals("tool-ran", c.execInContainer("/opt/app/bin/tool.sh").stdout.trim())
            assertEquals("single", c.execInContainer("cat", "/etc/single.txt").stdout.trim())
            assertEquals("600", c.execInContainer("stat", "-c", "%a", "/etc/inline.txt").stdout.trim())
            c.copyFileToContainer(MountableFile.forString("late\n"), "/late.txt")
            assertEquals("late", c.execInContainer("cat", "/late.txt").stdout.trim())
        }
    }

    private class HookedContainer : GenericContainer("alpine:latest") {
        val events = mutableListOf<String>()

        init {
            withCommand("sleep", "300")
        }

        override suspend fun containerIsCreated(containerId: String) { events += "created" }
        override suspend fun containerIsStarting(reused: Boolean) { events += "starting(reused=$reused)" }
        override suspend fun containerIsStarted() { events += "started" }
        override suspend fun containerIsStopping() { events += "stopping" }
        override suspend fun containerIsStopped() { events += "stopped" }
    }

    @Test
    fun lifecycleHooks() = linuxContainerTest {
        val c = HookedContainer()
        c.use { }
        assertEquals(listOf("created", "starting(reused=false)", "started", "stopping", "stopped"), c.events)
    }

    @Test
    fun reuse() = linuxContainerTest {
        val marker = unique()
        fun reusable() = GenericContainer("alpine:latest") {
            withCommand("sleep", "300")
            withLabel("tc.test.reuse", marker)
            withReuse()
        }
        // Not enabled on the machine: a normal, removed container.
        TestcontainersConfig.set("testcontainers.reuse.enable", "false")
        try {
            val plain = reusable()
            plain.use { assertFalse(it.isReuseActive) }
            assertFalse(plain.isRunning)

            TestcontainersConfig.set("testcontainers.reuse.enable", "true")
            val first = reusable()
            first.use { assertFalse(it.isReused) }
            assertTrue(first.isRunning, "use {} must keep a reusable container")
            val second = reusable()
            second.use { assertTrue(it.isReused) }
            assertEquals(first.containerId, second.containerId)

            val different = GenericContainer("alpine:latest") {
                withCommand("sleep", "301")
                withLabel("tc.test.reuse", marker)
                withReuse()
            }
            different.use { assertFalse(it.isReused) }
            different.stop()
            second.stop()
            assertFalse(second.isRunning)
        } finally {
            TestcontainersConfig.reset("testcontainers.reuse.enable")
        }
        assertEquals(emptyList(), Docker.client.listContainers(mapOf("label" to listOf("tc.test.reuse=$marker"))))
    }
}
