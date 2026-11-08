package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.ExecCreateRequest
import ru.ttraum.testcontainers.modules.PostgreSQLContainer
import ru.ttraum.testcontainers.wait.Wait
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ExecAndCopyTests {
    private fun sleeper() = GenericContainer("alpine:latest") { withCommand("sleep", "300") }

    @Test
    fun execWithUserEnvAndWorkingDir() = linuxContainerTest {
        sleeper().use { c ->
            val result = c.execInContainer(
                listOf("sh", "-c", "id -un; echo \"\$GREETING\"; pwd"),
                user = "nobody",
                env = mapOf("GREETING" to "hi there"),
                workingDir = "/tmp",
            )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(listOf("nobody", "hi there", "/tmp"), result.stdout.trim().lines())
        }
    }

    @Test
    fun execWithStdin() = linuxContainerTest {
        sleeper().use { c ->
            val small = c.execInContainer(listOf("cat"), stdin = "hello\nworld\n")
            assertEquals("hello\nworld\n", small.stdout)

            // Larger than any socket buffer and echoed back: input and output must flow at the same time.
            val big = ByteArray(2_000_000) { ('a' + it % 26).code.toByte() }
            val echoed = c.execInContainer(listOf("cat"), stdin = big, timeout = 60.seconds)
            assertEquals(big.size, echoed.stdout.length)
            assertEquals(big.decodeToString(), echoed.stdout)
        }
    }

    /** The path Windows named pipes take for stdin; its shell wrapper is checked here on any daemon. */
    @Test
    fun stdinThroughTemporaryFile() = linuxContainerTest {
        sleeper().use { c ->
            val id = c.containerId!!
            val request = ExecCreateRequest(cmd = listOf("sh", "-c", "cat; echo \"[\$1]\" >&2; exit 5", "sh", "arg with space"))
            val output = Docker.client.execWithStdinFile(id, request, "line 1\nline 2\n".encodeToByteArray())
            assertEquals(5, output.exitCode)
            assertEquals("line 1\nline 2\n", output.stdout)
            assertEquals("[arg with space]\n", output.stderr)
            // The temporary input file is removed afterwards.
            assertEquals("", c.execInContainer("sh", "-c", "ls -A /tmp | grep tc-stdin || true").stdout)
        }
    }

    @Test
    fun longExecKeepsItsExitCode() = linuxContainerTest {
        sleeper().use { c ->
            val result = c.execInContainer("sh", "-c", "sleep 7; echo done; exit 4")
            assertEquals(4, result.exitCode)
            assertEquals("done", result.stdout.trim())
        }
    }

    @Test
    fun execTimeout() = linuxContainerTest {
        sleeper().use { c ->
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<ExecTimeoutException> { c.execInContainer(listOf("sleep", "60"), timeout = 1.seconds) }
            assertTrue(started.elapsedNow() < 10.seconds, "took ${started.elapsedNow()}")
            // The container is still usable afterwards.
            assertEquals(0, c.execInContainer("true").exitCode)
        }
    }

    @Test
    fun copiesBinaryFilesBothWays() = linuxContainerTest {
        val binary = ByteArray(70_000) { (it % 256).toByte() }
        val longPath = "/data/" + "nested-directory-name/".repeat(6) + "file-with-a-rather-long-name.bin"
        sleeper().use { c ->
            c.copyToContainer(binary, longPath)
            assertContentEquals(binary, c.copyFileFromContainer(longPath))

            val release = c.copyFileFromContainer("/etc/alpine-release").decodeToString()
            assertTrue(release.isNotBlank())

            c.execInContainer("sh", "-c", "mkdir -p /out/sub && echo a > /out/a.txt && echo b > /out/sub/b.txt")
            val files = c.copyFilesFromContainer("/out")
            assertEquals(setOf("out/a.txt", "out/sub/b.txt"), files.keys)
            assertEquals("b\n", files.getValue("out/sub/b.txt").decodeToString())

            assertFailsWith<DockerException> { c.copyFileFromContainer("/does/not/exist") }
        }
    }

    @Test
    fun hostname() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withHostname("db-primary")
            withCommand("sleep", "300")
        }.use { c ->
            assertEquals("db-primary", c.execInContainer("hostname").stdout.trim())
        }
    }

    @Test
    fun timeoutSaysWhatItWaitedFor() = linuxContainerTest {
        val e = assertFailsWith<ContainerLaunchException> {
            GenericContainer("alpine:latest") {
                withCommand("sh", "-c", "echo starting; sleep 300")
                waitingFor(Wait.forLogMessage(".*ready.*"))
                withStartupTimeout(2.seconds)
            }.start()
        }
        val message = e.message.orEmpty()
        assertTrue("Timed out after 2s" in message, message)
        assertTrue("log message '.*ready.*'" in message, message)
        assertTrue("found 0 of 1 matching lines" in message, message)
        assertTrue("starting" in message, message) // container logs are attached
    }

    @Test
    fun psqlFromStdinAsPostgresUser() = linuxContainerTest {
        PostgreSQLContainer().use { pg ->
            val result = pg.execInContainer(
                listOf("psql", "-d", pg.databaseName, "-tA", "-v", "ON_ERROR_STOP=1"),
                stdin = "create table t(x int);\ninsert into t values (40), (2);\nselect sum(x) from t;\n",
                user = "postgres",
                env = mapOf("PGUSER" to pg.username),
            )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("42", result.stdout.trim().lines().last())
        }
    }
}
