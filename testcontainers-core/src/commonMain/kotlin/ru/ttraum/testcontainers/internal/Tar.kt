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

internal class TarFile(val path: String, val type: Char, val content: ByteArray)

/**
 * Reads a tar stream as produced by `GET /containers/{id}/archive`: ustar entries, GNU long names (`L`)
 * and pax headers (`x`, path only). Directory and link entries are returned with empty content.
 */
internal fun readTar(bytes: ByteArray): List<TarFile> {
    val result = mutableListOf<TarFile>()
    var pos = 0
    var longName: String? = null
    var paxPath: String? = null
    while (pos + 512 <= bytes.size) {
        val header = bytes.copyOfRange(pos, pos + 512)
        if (header.all { it.toInt() == 0 }) break
        pos += 512

        val size = parseTarNumber(header, 124, 12)
        val type = header[156].toInt().toChar().let { if (it == '\u0000') '0' else it }
        val dataEnd = pos + size.toInt()
        require(dataEnd <= bytes.size) { "Truncated tar entry" }
        val data = bytes.copyOfRange(pos, dataEnd)
        pos += ((size + 511) / 512 * 512).toInt()

        when (type) {
            'L' -> longName = data.decodeToString().trimEnd('\u0000')
            'x' -> paxPath = parsePaxPath(data) ?: paxPath
            'g' -> Unit // global pax header
            else -> {
                val name = paxPath ?: longName ?: run {
                    val prefix = tarString(header, 345, 155)
                    val base = tarString(header, 0, 100)
                    if (prefix.isNotEmpty() && tarString(header, 257, 5) == "ustar") "$prefix/$base" else base
                }
                result += TarFile(name, type, data)
                longName = null
                paxPath = null
            }
        }
    }
    return result
}

private fun tarString(header: ByteArray, offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && header[end].toInt() != 0) end++
    return header.decodeToString(offset, end)
}

private fun parseTarNumber(header: ByteArray, offset: Int, length: Int): Long {
    // Base-256 (GNU) for values that do not fit in octal: high bit of the first byte set.
    if (header[offset].toInt() and 0x80 != 0) {
        var value = (header[offset].toLong() and 0x7F)
        for (i in offset + 1 until offset + length) value = (value shl 8) or (header[i].toLong() and 0xFF)
        return value
    }
    val text = tarString(header, offset, length).trim()
    return if (text.isEmpty()) 0 else text.toLong(8)
}

/** pax records are "<len> key=value\n". */
private fun parsePaxPath(data: ByteArray): String? {
    var pos = 0
    var path: String? = null
    while (pos < data.size) {
        val space = (pos until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
        val length = data.decodeToString(pos, space).toIntOrNull() ?: break
        val record = data.decodeToString(space + 1, minOf(pos + length, data.size)).removeSuffix("\n")
        if (record.startsWith("path=")) path = record.removePrefix("path=")
        pos += length
    }
    return path
}
