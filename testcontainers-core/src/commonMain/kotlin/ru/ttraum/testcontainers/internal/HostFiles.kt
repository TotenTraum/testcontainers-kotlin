package ru.ttraum.testcontainers.internal

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import ru.ttraum.testcontainers.DockerException

/** 0755 for scripts (`#!`), else 0644: kotlinx-io cannot read file permissions. */
internal fun guessMode(content: ByteArray): Int =
    if (content.size >= 2 && content[0] == '#'.code.toByte() && content[1] == '!'.code.toByte()) "755".toInt(8) else "644".toInt(8)

/** A host file, or every file under a host directory, as tar entries placed under [targetPath]. */
internal fun readHostTree(hostPath: String, targetPath: String, mode: Int? = null): List<TarEntry> {
    val result = mutableListOf<TarEntry>()
    fun walk(path: Path, target: String) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: throw DockerException("No such file or directory: $path")
        if (metadata.isDirectory) {
            for (child in SystemFileSystem.list(path)) walk(child, if (target.isEmpty()) child.name else "$target/${child.name}")
        } else {
            val bytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
            result += TarEntry(target.ifEmpty { path.name }, bytes, mode ?: guessMode(bytes))
        }
    }
    walk(Path(hostPath), targetPath.trimEnd('/'))
    return result
}

/** A stable 128-bit hex digest (two FNV-1a 64 passes); not cryptographic, good enough for reuse hashes. */
internal fun stableHash(vararg parts: ByteArray): String {
    var a = 0xcbf29ce484222325uL
    var b = 0x84222325cbf29ce4uL
    val prime = 0x100000001b3uL
    for (part in parts) {
        for (byte in part) {
            a = (a xor (byte.toUByte().toULong())) * prime
            b = (b xor ((byte.toInt() xor 0x5A).toUByte().toULong())) * prime
        }
        a = (a xor 0xFFuL) * prime // part separator
        b = (b xor 0xA5uL) * prime
    }
    return a.toString(16).padStart(16, '0') + b.toString(16).padStart(16, '0')
}
