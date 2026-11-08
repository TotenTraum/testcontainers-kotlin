package ru.ttraum.testcontainers

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.ttraum.testcontainers.images.DockerConfigCredentials
import ru.ttraum.testcontainers.images.ImageNameSubstitutor
import ru.ttraum.testcontainers.images.LocalImage
import ru.ttraum.testcontainers.images.PullPolicy
import ru.ttraum.testcontainers.images.RegistryCredentials
import ru.ttraum.testcontainers.images.normalizeRegistry
import ru.ttraum.testcontainers.images.registryAuthHeader
import ru.ttraum.testcontainers.images.registryOf
import ru.ttraum.testcontainers.internal.DockerJson
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ConfigAndAuthTests {
    @Test
    fun parsesPropertiesFiles() {
        val props = parseProperties(
            """
            #Modified by Testcontainers
            ! another comment
            docker.client.strategy=org.testcontainers.dockerclient.UnixSocketClientProviderStrategy
            hub.image.name.prefix = registry.corp:5000/mirror/
            tc.host:tcp://127.0.0.1:41234
            path=C\:\\work\\dir
            long=first \
                 second
            unicode=\u0041B
            """.trimIndent(),
        )
        assertEquals("org.testcontainers.dockerclient.UnixSocketClientProviderStrategy", props["docker.client.strategy"])
        assertEquals("registry.corp:5000/mirror/", props["hub.image.name.prefix"])
        assertEquals("tcp://127.0.0.1:41234", props["tc.host"])
        assertEquals("C:\\work\\dir", props["path"])
        assertEquals("first second", props["long"])
        assertEquals("AB", props["unicode"])
    }

    @Test
    fun configOverridesWin() {
        TestcontainersConfig.set("some.test.key", "value")
        try {
            assertEquals("value", TestcontainersConfig.get("some.test.key"))
        } finally {
            TestcontainersConfig.reset("some.test.key")
        }
        assertNull(TestcontainersConfig.get("some.test.key"))
    }

    @Test
    fun findsRegistryOfImages() {
        assertEquals("docker.io", registryOf("redis"))
        assertEquals("docker.io", registryOf("bitnami/redis:7"))
        assertEquals("ghcr.io", registryOf("ghcr.io/org/app:1"))
        assertEquals("localhost:5000", registryOf("localhost:5000/app"))
        assertEquals("localhost", registryOf("localhost/testcontainers/abc:latest"))
        assertEquals("docker.io", normalizeRegistry("https://index.docker.io/v1/"))
        assertEquals("registry.corp:5000", normalizeRegistry("https://registry.corp:5000/v2/"))
    }

    @Test
    fun substitutesHubImagesWithPrefix() {
        TestcontainersConfig.set("hub.image.name.prefix", "mirror.corp/hub/")
        try {
            val s = ImageNameSubstitutor.Default
            assertEquals("mirror.corp/hub/redis:7", s.substitute("redis:7"))
            assertEquals("mirror.corp/hub/bitnami/redis", s.substitute("bitnami/redis"))
            assertEquals("ghcr.io/org/app", s.substitute("ghcr.io/org/app"))
            assertEquals("localhost:5000/app", s.substitute("localhost:5000/app"))
        } finally {
            TestcontainersConfig.reset("hub.image.name.prefix")
        }
        TestcontainersConfig.set("hub.image.name.prefix", null)
        try {
            assertEquals("redis:7", ImageNameSubstitutor.Default.substitute("redis:7"))
        } finally {
            TestcontainersConfig.reset("hub.image.name.prefix")
        }
    }

    @Test
    fun pullPolicies() {
        val fresh = LocalImage("sha256:1", Clock.System.now() - 1.hours)
        val old = LocalImage("sha256:2", Clock.System.now() - 3.days)
        assertTrue(PullPolicy.defaultPolicy().shouldPull("a", null))
        assertFalse(PullPolicy.defaultPolicy().shouldPull("a", old))
        assertTrue(PullPolicy.alwaysPull().shouldPull("a", fresh))
        assertFalse(PullPolicy.ageBased(1.days).shouldPull("a", fresh))
        assertTrue(PullPolicy.ageBased(1.days).shouldPull("a", old))
    }

    @Test
    fun readsCredentialsLikeTheDockerCli() = runTest {
        val helperCalls = mutableListOf<Pair<String, String>>()
        val config = """
            {
              "auths": {
                "https://index.docker.io/v1/": { "auth": "${Base64.encode("hubuser:hub:pass".encodeToByteArray())}" },
                "registry.corp:5000": { "identitytoken": "tok-123" },
                "ghcr.io": {}
              },
              "credHelpers": { "registry.corp:5000": "corp", "gcr.io": "gcloud" },
              "credsStore": "desktop"
            }
        """.trimIndent()
        val creds = DockerConfigCredentials(configJson = { config }) { helper, server ->
            helperCalls += helper to server
            if (helper == "corp") RegistryCredentials("corp-user", "corp-pass") else null
        }
        // inline auth; the password may contain ':'
        assertEquals(RegistryCredentials("hubuser", "hub:pass"), creds.credentialsFor("docker.io"))
        // credHelpers win over auths
        assertEquals(RegistryCredentials("corp-user", "corp-pass"), creds.credentialsFor("registry.corp:5000"))
        // empty auths entry -> credsStore
        assertNull(creds.credentialsFor("ghcr.io"))
        assertNull(creds.credentialsFor("gcr.io"))
        assertEquals(listOf("corp" to "registry.corp:5000", "desktop" to "ghcr.io", "gcloud" to "gcr.io"), helperCalls)
        // cached
        creds.credentialsFor("ghcr.io")
        assertEquals(3, helperCalls.size)

        val noConfig = DockerConfigCredentials(configJson = { null }) { _, _ -> error("not called") }
        assertNull(noConfig.credentialsFor("docker.io"))
    }

    @Test
    fun encodesRegistryAuthHeader() {
        val header = registryAuthHeader("docker.io", RegistryCredentials("u", "p+/="))
        val json = DockerJson.parseToJsonElement(Base64.UrlSafe.decode(header).decodeToString()).jsonObject
        assertEquals("u", json["username"]!!.jsonPrimitive.content)
        assertEquals("p+/=", json["password"]!!.jsonPrimitive.content)
        assertEquals("https://index.docker.io/v1/", json["serveraddress"]!!.jsonPrimitive.content)
    }

    @Test
    fun resolvesDockerContexts() {
        if (getEnv("DOCKER_CONTEXT") != null) return // the environment chooses the context
        val dir = Path(SystemTemporaryDirectory, "tc-docker-config-${Random.nextLong().toULong()}")
        fun write(relative: String, text: String) {
            val p = Path(dir, relative)
            p.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(p).buffered().use { it.writeString(text) }
        }
        write("contexts/meta/aaaa/meta.json", """{"Name":"other","Endpoints":{"docker":{"Host":"unix:///other.sock"}}}""")
        write("contexts/meta/bbbb/meta.json", """{"Name":"remote","Metadata":{},"Endpoints":{"docker":{"Host":"tcp://10.1.2.3:2375","SkipTLSVerify":false}}}""")

        write("config.json", """{"currentContext":"remote"}""")
        assertEquals(ContextEndpoint("remote", "tcp://10.1.2.3:2375", tls = false), dockerContextEndpoint(dir.toString()))

        write("contexts/tls/bbbb/docker/ca.pem", "-----BEGIN CERTIFICATE-----")
        assertEquals(true, dockerContextEndpoint(dir.toString())?.tls)

        write("config.json", """{"currentContext":"default"}""")
        assertNull(dockerContextEndpoint(dir.toString()))

        write("config.json", """{"currentContext":"missing"}""")
        assertFailsWith<DockerException> { dockerContextEndpoint(dir.toString()) }
    }

    @Test
    fun runsExternalPrograms() = dockerTest {
        // `sort` reads stdin on every platform.
        val result = runProcess(listOf("sort"), "b\na\n")!!
        assertEquals(0, result.exitCode)
        assertEquals(listOf("a", "b"), result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val missing = runProcess(listOf("docker-credential-does-not-exist", "get"), "x")
        assertTrue(missing == null || missing.exitCode != 0)
    }
}
