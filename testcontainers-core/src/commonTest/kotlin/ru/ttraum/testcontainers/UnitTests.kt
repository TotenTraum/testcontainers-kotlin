package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.DockerClient
import ru.ttraum.testcontainers.internal.buildTar
import ru.ttraum.testcontainers.internal.readTar
import ru.ttraum.testcontainers.internal.TarEntry
import ru.ttraum.testcontainers.internal.urlEncode
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitTests {
    @Test
    fun splitsImageReferences() {
        assertEquals("redis" to "latest", DockerClient.splitImage("redis"))
        assertEquals("redis" to "7-alpine", DockerClient.splitImage("redis:7-alpine"))
        assertEquals("localhost:5000/app" to "latest", DockerClient.splitImage("localhost:5000/app"))
        assertEquals("localhost:5000/app" to "1.0", DockerClient.splitImage("localhost:5000/app:1.0"))
        assertEquals("alpine" to "sha256:abc", DockerClient.splitImage("alpine@sha256:abc"))
    }

    @Test
    fun parsesDockerHost() {
        assertEquals(DockerEndpoint.Unix("/var/run/docker.sock"), DockerEndpoint.parse("unix:///var/run/docker.sock"))
        assertEquals(DockerEndpoint.Tcp("10.0.0.1", 2376), DockerEndpoint.parse("tcp://10.0.0.1:2376"))
        assertEquals(DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine"), DockerEndpoint.parse("npipe:////./pipe/docker_engine"))
        assertEquals(DockerEndpoint.DEFAULT_WINDOWS_PIPE, (DockerEndpoint.parse("npipe:////./pipe/docker_engine") as DockerEndpoint.NamedPipe).pipePath)
    }

    @Test
    fun demultiplexesStreams() {
        fun frame(type: Int, text: String): ByteArray {
            val payload = text.encodeToByteArray()
            return byteArrayOf(type.toByte(), 0, 0, 0, 0, 0, 0, payload.size.toByte()) + payload
        }
        val out = DockerClient.demultiplex(frame(1, "hello ") + frame(2, "oops") + frame(1, "world"))
        assertEquals("hello world", out.stdout)
        assertEquals("oops", out.stderr)
    }

    @Test
    fun tarIsBlockAligned() {
        val tar = buildTar(listOf(TarEntry("/etc/app/config.txt", "abc".encodeToByteArray(), "644".toInt(8))))
        assertEquals(512 + 512 + 1024, tar.size)
        assertEquals("etc/app/config.txt", tar.copyOfRange(0, 18).decodeToString())
    }

    @Test
    fun tarRoundTrip() {
        val long = "a/" + "b".repeat(120) + "/c.txt"
        val content = ByteArray(1000) { it.toByte() }
        val entries = readTar(buildTar(listOf(TarEntry("/x.txt", "hi".encodeToByteArray(), 420), TarEntry(long, content, 420))))
        assertEquals(listOf("x.txt", long), entries.map { it.path })
        assertEquals("hi", entries[0].content.decodeToString())
        kotlin.test.assertContentEquals(content, entries[1].content)
    }

    @Test
    fun encodesUrls() {
        assertEquals("a%2Fb%3Ac", urlEncode("a/b:c"))
    }
}
