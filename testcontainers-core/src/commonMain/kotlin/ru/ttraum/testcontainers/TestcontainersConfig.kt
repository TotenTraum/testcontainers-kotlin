package ru.ttraum.testcontainers

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Settings, looked up the way testcontainers-java does it:
 * 1. environment variable `TESTCONTAINERS_<KEY>` (`hub.image.name.prefix` → `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`);
 * 2. `~/.testcontainers.properties`.
 *
 * Known keys: `tc.host`, `docker.host`, `docker.tls.verify`, `docker.socket.override`, `host.override`,
 * `hub.image.name.prefix`, `pull.retries`, `ryuk.disabled`, `ryuk.container.image`, `ryuk.container.privileged`.
 */
public object TestcontainersConfig {
    /** Values set from code win over the environment and the properties file. */
    private val overrides = mutableMapOf<String, String?>()

    public fun get(key: String): String? {
        if (key in overrides) return overrides[key]
        val envName = "TESTCONTAINERS_" + key.uppercase().replace('.', '_').replace('-', '_')
        getEnv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
        return userProperties[key]?.takeIf { it.isNotBlank() }
    }

    public fun getBoolean(key: String, default: Boolean = false): Boolean =
        get(key)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default

    /** Sets (or with null, hides) a value for this process, e.g. in a test setup. */
    public fun set(key: String, value: String?) {
        overrides[key] = value
    }

    public fun reset(key: String) {
        overrides.remove(key)
    }

    internal val userPropertiesPath: String? get() = userHome()?.let { "$it/.testcontainers.properties" }

    private val userProperties: Map<String, String> by lazy {
        val path = userPropertiesPath ?: return@lazy emptyMap()
        readTextFile(path)?.let(::parseProperties).orEmpty()
    }
}

internal fun userHome(): String? =
    getEnv("HOME")?.takeIf { it.isNotBlank() } ?: getEnv("USERPROFILE")?.takeIf { it.isNotBlank() }

internal fun readTextFile(path: String): String? = try {
    val p = Path(path)
    if (!SystemFileSystem.exists(p)) null else SystemFileSystem.source(p).buffered().use { it.readString() }
} catch (_: Exception) {
    null
}

/** Parses the subset of java.util.Properties syntax that config files use. */
internal fun parseProperties(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val logical = mutableListOf<String>()
    var pending = StringBuilder()
    for (raw in text.lines()) {
        val line = if (pending.isEmpty()) raw.trimStart() else raw.trimStart()
        if (pending.isEmpty() && (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))) continue
        val continued = line.length - line.trimEnd('\\').length
        if (continued % 2 == 1) {
            pending.append(line.dropLast(1))
        } else {
            pending.append(line)
            logical += pending.toString()
            pending = StringBuilder()
        }
    }
    if (pending.isNotEmpty()) logical += pending.toString()

    for (line in logical) {
        var i = 0
        val key = StringBuilder()
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { key.append(unescape(line[i + 1])); i += 2; continue }
            if (c == '=' || c == ':' || c.isWhitespace()) break
            key.append(c); i++
        }
        while (i < line.length && line[i].isWhitespace()) i++
        if (i < line.length && (line[i] == '=' || line[i] == ':')) i++
        while (i < line.length && line[i].isWhitespace()) i++
        val value = StringBuilder()
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                val n = line[i + 1]
                if (n == 'u' && i + 5 < line.length) {
                    line.substring(i + 2, i + 6).toIntOrNull(16)?.let { value.append(it.toChar()); i += 6; continue }
                }
                value.append(unescape(n)); i += 2; continue
            }
            value.append(c); i++
        }
        result[key.toString()] = value.toString().trimEnd()
    }
    return result
}

private fun unescape(c: Char): Char = when (c) {
    't' -> '\t'
    'n' -> '\n'
    'r' -> '\r'
    'f' -> '\u000C'
    else -> c
}
