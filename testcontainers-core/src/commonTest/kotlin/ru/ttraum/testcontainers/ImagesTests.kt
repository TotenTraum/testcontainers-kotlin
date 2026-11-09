package ru.ttraum.testcontainers

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import ru.ttraum.testcontainers.images.ImageFromDockerfile
import ru.ttraum.testcontainers.images.RegistryCredentials
import ru.ttraum.testcontainers.images.RegistryCredentialsProvider
import ru.ttraum.testcontainers.images.registryAuthHeader
import ru.ttraum.testcontainers.wait.Wait
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class ImagesTests {
    @Test
    fun buildsImageFromDockerfile() = linuxContainerTest {
        val image = ImageFromDockerfile()
            .withDockerfileFromString(
                """
                FROM alpine:latest AS base
                ARG GREETING=unset
                RUN echo "${'$'}GREETING" > /greeting
                COPY app.sh /app.sh
                COPY data/ /data/
                CMD ["/app.sh"]
                """.trimIndent(),
            )
            .withFileFromString("app.sh", "#!/bin/sh\necho app-started\nexec sleep 300\n")
            .withFileFromBytes("data/blob.bin", ByteArray(300) { it.toByte() })
            .withBuildArg("GREETING", "built with args")

        GenericContainer(image) {
            waitingFor(Wait.forLogMessage(".*app-started.*"))
        }.use { c ->
            assertEquals("built with args", c.execInContainer("cat", "/greeting").stdout.trim())
            assertEquals("300", c.execInContainer("sh", "-c", "wc -c < /data/blob.bin").stdout.trim())
            assertEquals(image.imageName, c.resolvedImage)
        }
        // A second container reuses the built image.
        val started = TimeSource.Monotonic.markNow()
        GenericContainer(image).use { assertTrue(it.isRunning) }
        assertTrue(started.elapsedNow() < 30.seconds)
    }

    @Test
    fun buildsImageFromHostDirectory() = linuxContainerTest {
        val dir = Path(SystemTemporaryDirectory, "tc-build-${Random.nextLong().toULong()}")
        fun write(relative: String, text: String) {
            val p = Path(dir, relative)
            p.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(p).buffered().use { it.writeString(text) }
        }
        write("Dockerfile", "FROM alpine:latest\nCOPY . /ctx/\nCMD [\"sleep\", \"300\"]\n")
        write("conf/app.conf", "key=value\n")
        write("run.sh", "#!/bin/sh\necho ok\n")

        GenericContainer(ImageFromDockerfile().withFileFromPath(".", dir.toString())).use { c ->
            assertEquals("key=value", c.execInContainer("cat", "/ctx/conf/app.conf").stdout.trim())
            assertEquals("ok", c.execInContainer("/ctx/run.sh").stdout.trim()) // #! files stay executable
        }
    }

    @Test
    fun brokenDockerfileReportsBuildOutput() = linuxContainerTest {
        val image = ImageFromDockerfile().withDockerfileFromString("FROM alpine:latest\nRUN echo before-failure && exit 3\n")
        val e = assertFailsWith<DockerException> { GenericContainer(image).start() }
        assertTrue("before-failure" in e.message.orEmpty(), e.message)
    }

    /**
     * A private registry with basic auth: images pushed there are pulled with credentials from
     * [Docker.registryCredentials], also through `hub.image.name.prefix`; without credentials the pull fails at once.
     */
    @Test
    fun pullsFromPrivateRegistryWithCredentials() = linuxContainerTest {
        // A fixed port: on Docker Desktop only those are reachable by the daemon itself, which pushes and pulls here.
        val registryPort = freeTcpPort()
        GenericContainer("registry:2") {
            withFixedExposedPort(registryPort, 5000)
            withEnv("REGISTRY_AUTH", "htpasswd")
            withEnv("REGISTRY_AUTH_HTPASSWD_REALM", "tc")
            withEnv("REGISTRY_AUTH_HTPASSWD_PATH", "/auth/htpasswd")
            withCopyToContainer("tcuser:\$2y\$05\$G7ZdRCyHu4lXJYA2n0rkPOdu1a8JRHTL0GUJHBTU3Ns4EMtk07M/u\n", "/auth/htpasswd")
            waitingFor(Wait.forHttp("/v2/", statusCode = 401))
        }.use { registry ->
            // 127.0.0.1, not localhost: Docker Desktop's daemon resolves localhost to ::1 inside its VM.
            val host = "127.0.0.1:$registryPort"
            val creds = RegistryCredentials("tcuser", "tcpass")
            val pushed = "$host/tc/alpine:private"
            Docker.ensureImage("alpine:latest")
            Docker.client.tagImage("alpine:latest", pushed)
            Docker.client.pushImage(pushed, registryAuthHeader(host, creds))
            Docker.client.removeImage(pushed)

            val previous = Docker.registryCredentials
            try {
                // No credentials: the registry answers "unauthorized", which is not retried.
                Docker.registryCredentials = RegistryCredentialsProvider { null }
                val started = TimeSource.Monotonic.markNow()
                val denied = assertFailsWith<DockerException> { Docker.ensureImage(pushed) }
                assertTrue(started.elapsedNow() < 2.seconds, "retried a permanent failure: ${denied.message}")

                Docker.registryCredentials = RegistryCredentialsProvider { if (it == host) creds else null }
                GenericContainer(pushed) { withCommand("sleep", "300") }.use { c ->
                    assertEquals(0, c.execInContainer("true").exitCode)
                }
                Docker.client.removeImage(pushed)

                // The same image through a "mirror" prefix for Docker Hub names.
                TestcontainersConfig.set("hub.image.name.prefix", "$host/")
                GenericContainer("tc/alpine:private") { withCommand("sleep", "300") }.use { c ->
                    assertEquals(pushed, c.resolvedImage)
                }
                Docker.client.removeImage(pushed)
            } finally {
                Docker.registryCredentials = previous
                TestcontainersConfig.reset("hub.image.name.prefix")
            }
        }
    }
}
