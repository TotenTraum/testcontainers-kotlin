package ru.ttraum.testcontainers

import ru.ttraum.testcontainers.internal.TarEntry
import ru.ttraum.testcontainers.internal.guessMode
import ru.ttraum.testcontainers.internal.readHostTree

/**
 * Content to copy into a container: a host file or directory, or bytes.
 * On the JVM also `MountableFile.forClasspathResource(...)`.
 */
public class MountableFile private constructor(
    private val describe: String,
    private val entries: (containerPath: String) -> List<TarEntry>,
) {
    /** Tar entries placing this content at [containerPath] (for a directory: its contents under that path). */
    internal fun toTarEntries(containerPath: String): List<TarEntry> = entries(containerPath)

    override fun toString(): String = "MountableFile($describe)"

    public companion object {
        /** A host file or directory; [mode] (e.g. `"755".toInt(8)`) overrides the guess (0755 for `#!` scripts, else 0644). */
        public fun forHostPath(path: String, mode: Int? = null): MountableFile =
            MountableFile(path) { target -> readHostTree(path, target, mode) }

        public fun forBytes(content: ByteArray, mode: Int? = null): MountableFile =
            MountableFile("${content.size} bytes") { target -> listOf(TarEntry(target, content, mode ?: guessMode(content))) }

        public fun forString(content: String, mode: Int? = null): MountableFile = forBytes(content.encodeToByteArray(), mode)
    }
}
