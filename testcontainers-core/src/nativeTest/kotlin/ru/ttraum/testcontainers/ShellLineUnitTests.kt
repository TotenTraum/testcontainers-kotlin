package ru.ttraum.testcontainers

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellLineUnitTests {
    @Test
    fun quotesForSh() {
        assertEquals(
            "env DOCKER_HOST=unix:///var/run/docker.sock 'A=b c' docker compose -f '/tmp/my dir/c.yaml' 'it'\\''s' < /tmp/in 2>&1",
            shellLine(
                listOf("docker", "compose", "-f", "/tmp/my dir/c.yaml", "it's"),
                linkedMapOf("DOCKER_HOST" to "unix:///var/run/docker.sock", "A" to "b c"),
                "/tmp/in", mergeStderr = true, windows = false,
            ),
        )
        assertEquals("docker-credential-x get < /tmp/in 2>/dev/null", shellLine(listOf("docker-credential-x", "get"), emptyMap(), "/tmp/in", false, windows = false))
    }

    @Test
    fun quotesForCmd() {
        assertEquals(
            "set \"DOCKER_HOST=npipe:////./pipe/docker_engine\"&& docker compose -f \"C:\\My Files\\c.yaml\" < C:\\Temp\\in 2>NUL",
            shellLine(
                listOf("docker", "compose", "-f", "C:\\My Files\\c.yaml"),
                mapOf("DOCKER_HOST" to "npipe:////./pipe/docker_engine"),
                "C:\\Temp\\in", mergeStderr = false, windows = true,
            ),
        )
    }
}
