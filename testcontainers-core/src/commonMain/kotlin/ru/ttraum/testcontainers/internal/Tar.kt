package ru.ttraum.testcontainers.internal

/** Minimal ustar writer, enough for `PUT /containers/{id}/archive`. */
internal class TarEntry(val path: String, val content: ByteArray, val mode: Int)

internal fun buildTar(entries: List<TarEntry>): ByteArray {
    val out = ByteArrayBuilder()
    for (entry in entries) {
        val path = entry.path.trimStart('/')
        val (prefix, name) = splitTarPath(path)
        val header = ByteArray(512)
        fun put(offset: Int, length: Int, value: String) {
            val bytes = value.encodeToByteArray()
            require(bytes.size <= length) { "Tar header field too long: $value" }
            bytes.copyInto(header, offset)
        }
        fun octal(value: Long, length: Int) = value.toString(8).padStart(length - 1, '0')

        put(0, 100, name)
        put(100, 8, octal(entry.mode.toLong(), 8))
        put(108, 8, octal(0, 8))
        put(116, 8, octal(0, 8))
        put(124, 12, octal(entry.content.size.toLong(), 12))
        put(136, 12, octal(0, 12))
        header[156] = '0'.code.toByte()
        put(257, 6, "ustar")
        put(263, 2, "00")
        put(345, 155, prefix)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        val checksum = header.sumOf { it.toInt() and 0xFF }
        put(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")

        out.append(header)
        out.append(entry.content)
        val padding = (512 - entry.content.size % 512) % 512
        if (padding > 0) out.append(ByteArray(padding))
    }
    out.append(ByteArray(1024))
    return out.toByteArray()
}

private fun splitTarPath(path: String): Pair<String, String> {
    if (path.encodeToByteArray().size <= 100) return "" to path
    val idx = path.lastIndexOf('/', startIndex = path.length - 1)
    require(idx > 0 && path.length - idx - 1 <= 100 && idx <= 155) { "Path too long for tar: $path" }
    return path.substring(0, idx) to path.substring(idx + 1)
}
