package ru.ttraum.testcontainers.images

/**
 * `.dockerignore` rules, with the docker CLI's semantics: one pattern per line, `#` comments, `!` re-includes,
 * `*` and `?` stay within a path segment, `**` spans any number of segments, `[...]` character classes and
 * `\` escapes. A pattern also excludes everything below a directory it matches. The last matching rule wins.
 */
internal class DockerIgnore(content: String) {
    private class Rule(val regex: Regex, val exclude: Boolean)

    private val rules: List<Rule> = content.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val exclude = !line.startsWith("!")
        val pattern = clean(if (exclude) line else line.substring(1).trim())
        if (pattern.isEmpty()) null else Rule(toRegex(pattern), exclude)
    }

    val isEmpty: Boolean get() = rules.isEmpty()

    fun isExcluded(path: String): Boolean {
        val clean = clean(path)
        // "a/b/c" is matched as itself and through its parents "a" and "a/b".
        val segments = clean.split('/')
        val candidates = (1..segments.size).map { segments.subList(0, it).joinToString("/") }
        var excluded = false
        for (rule in rules) {
            if (candidates.any { rule.regex.matches(it) }) excluded = rule.exclude
        }
        return excluded
    }

    private companion object {
        /** filepath.Clean, relative to the context root (`\` is an escape, not a separator). */
        fun clean(path: String): String {
            val out = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> out.removeLastOrNull()
                    else -> out.addLast(part)
                }
            }
            return out.joinToString("/")
        }

        fun toRegex(pattern: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && pattern.getOrNull(i + 1) == '*' -> {
                        i++
                        if (pattern.getOrNull(i + 1) == '/') {
                            i++
                            sb.append("(?:.*/)?")
                        } else {
                            sb.append(".*")
                        }
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '[' -> {
                        val end = pattern.indexOf(']', i + 2)
                        if (end < 0) {
                            sb.append("\\[")
                        } else {
                            var body = pattern.substring(i + 1, end)
                            if (body.startsWith("!") || body.startsWith("^")) body = "^" + body.substring(1)
                            sb.append('[').append(body.replace("\\", "\\\\").replace("[", "\\[")).append(']')
                            i = end
                        }
                    }
                    c == '\\' && i + 1 < pattern.length -> {
                        i++
                        sb.append(Regex.escape(pattern[i].toString()))
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString())
        }
    }
}
