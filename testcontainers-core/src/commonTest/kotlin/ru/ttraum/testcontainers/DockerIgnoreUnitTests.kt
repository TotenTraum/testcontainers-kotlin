package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.images.DockerIgnore
import ru.ttraum.testcontainers.images.ImageFromDockerfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerIgnoreUnitTests {
    @Test
    fun matchesLikeTheDockerCli() {
        val ignore = DockerIgnore(
            """
            # comment
            *.log
            build
            /secrets/**
            !secrets/keep.txt
            **/tmp
            docs/*.md
            !docs/README.md
            f?le.txt
            [ab].bin
            """.trimIndent(),
        )
        assertTrue(ignore.isExcluded("app.log"))
        assertFalse(ignore.isExcluded("logs/app.log"), "* does not cross /")
        assertTrue(ignore.isExcluded("build/out/a.class"), "a matching directory excludes its contents")
        assertTrue(ignore.isExcluded("secrets/key.pem"))
        assertFalse(ignore.isExcluded("secrets/keep.txt"), "! re-includes, last match wins")
        assertTrue(ignore.isExcluded("tmp/x"))
        assertTrue(ignore.isExcluded("a/b/tmp/x"))
        assertTrue(ignore.isExcluded("docs/guide.md"))
        assertFalse(ignore.isExcluded("docs/README.md"))
        assertFalse(ignore.isExcluded("docs/sub/guide.md"))
        assertTrue(ignore.isExcluded("file.txt"))
        assertFalse(ignore.isExcluded("fiile.txt"))
        assertTrue(ignore.isExcluded("a.bin"))
        assertFalse(ignore.isExcluded("c.bin"))
        assertFalse(ignore.isExcluded("src/Main.kt"))
        assertTrue(ignore.isExcluded("./build"), "paths are cleaned")
    }

    @Test
    fun negatedCharacterClassAndEscapes() {
        val ignore = DockerIgnore("[!a]*.txt\nweird\\*name")
        assertTrue(ignore.isExcluded("b.txt"))
        assertFalse(ignore.isExcluded("a.txt"))
        assertTrue(ignore.isExcluded("weird*name"))
        assertFalse(ignore.isExcluded("weirdXname"))
    }

    @Test
    fun filtersTheBuildContext() {
        fun paths(image: ImageFromDockerfile) = image.contextEntries().map { it.path }.toSet()
        val image = ImageFromDockerfile()
            .withDockerfileFromString("FROM scratch")
            .withFileFromString(".dockerignore", "*\n!keep.txt")
            .withFileFromString("keep.txt", "1")
            .withFileFromString("drop.txt", "2")
        assertEquals(setOf("Dockerfile", ".dockerignore", "keep.txt"), paths(image), "Dockerfile and .dockerignore are always sent")

        // <dockerfile>.dockerignore wins over the root one.
        image.withFileFromString("Dockerfile.dockerignore", "keep.txt")
        assertEquals(setOf("Dockerfile", ".dockerignore", "Dockerfile.dockerignore", "drop.txt"), paths(image))
    }
}
