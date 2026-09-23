package app.menosan.config

import java.io.File

/**
 * Minimal `.env` reader for local development. Supports `KEY=value`, `export KEY=value`,
 * single/double-quoted values, full-line comments, and inline ` # comments` after unquoted values.
 */
object DotEnv {
    fun read(file: File): Map<String, String> =
        if (file.isFile) parse(file.readText()) else emptyMap()

    fun parse(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            result[key] = parseValue(line.substring(eq + 1).trim())
        }
        return result
    }

    private fun parseValue(raw: String): String {
        if (raw.length >= 2 && (raw[0] == '"' || raw[0] == '\'')) {
            val end = raw.indexOf(raw[0], startIndex = 1)
            if (end > 0) return raw.substring(1, end)
        }
        val comment = Regex("""\s#""").find(raw)
        return (if (comment != null) raw.substring(0, comment.range.first) else raw).trim()
    }
}
