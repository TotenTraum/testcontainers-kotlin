package ru.ttraum.testcontainers

import kotlin.test.Test
import kotlin.test.assertEquals

class ClasspathResourceTest {
    @Test
    fun copiesClasspathResource() = linuxContainerTest {
        GenericContainer("alpine:latest") {
            withCommand("sleep", "300")
            withCopyFileToContainer(MountableFile.forClasspathResource("tc-resource.txt"), "/etc/res.txt")
        }.use { c ->
            assertEquals("from-the-classpath", c.execInContainer("cat", "/etc/res.txt").stdout.trim())
        }
    }
}
