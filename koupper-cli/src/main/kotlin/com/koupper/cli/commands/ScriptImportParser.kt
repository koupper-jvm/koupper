package com.koupper.cli.commands

enum class ScriptImportMode { INCLUSIVE, EXCLUSIVE }

data class ParsedScriptImport(
    val mode: ScriptImportMode,
    val wildcard: Boolean,
    val path: String
)

object ScriptImportParser {
    fun parseKeyValueParams(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()

        val regex = Regex("""\b([A-Za-z][A-Za-z0-9_-]*)\s*=\s*("([^"]*)"|([^\s,]+))""")
        val out = LinkedHashMap<String, String>()

        regex.findAll(input).forEach { m ->
            val key = m.groupValues[1].trim()
            val quoted = m.groupValues[3]
            val plain = m.groupValues[4]
            out[key] = if (quoted.isNotBlank()) quoted else plain
        }

        return out
    }

    fun splitBySpacesRespectingQuotes(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in input) {
            when (ch) {
                '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }

                ' ' -> {
                    if (inQuotes) sb.append(ch)
                    else {
                        val token = sb.toString().trim()
                        if (token.isNotEmpty()) out.add(token)
                        sb.setLength(0)
                    }
                }

                else -> sb.append(ch)
            }
        }

        val last = sb.toString().trim()
        if (last.isNotEmpty()) out.add(last)

        return out
    }

    fun parseScriptImports(tokens: List<String>): List<ParsedScriptImport> {
        fun stripQuotes(s: String): String =
            if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1) else s

        fun normalizePath(raw: String): String {
            var value = raw.trim().replace('\\', '/')
            value = value.removePrefix("./")
            value = value.removePrefix(".\\")
            while (value.startsWith('/')) value = value.removePrefix("/")
            return value
        }

        fun flagToImport(flag: String): Pair<ScriptImportMode, Boolean>? = when (flag) {
            "-si", "--script-inclusive" -> ScriptImportMode.INCLUSIVE to false
            "-se", "--script-exclusive" -> ScriptImportMode.EXCLUSIVE to false
            "-swi", "--script-wildcard-inclusive" -> ScriptImportMode.INCLUSIVE to true
            "-swe", "--script-wildcard-exclusive" -> ScriptImportMode.EXCLUSIVE to true
            else -> null
        }

        val out = mutableListOf<ParsedScriptImport>()
        var i = 0

        while (i < tokens.size) {
            val flag = tokens[i].trim()
            val mapped = flagToImport(flag)

            if (mapped != null) {
                val (mode, wildcardFlag) = mapped
                val next = tokens.getOrNull(i + 1) ?: throw IllegalArgumentException("Missing path after $flag")
                val normalizedPath = normalizePath(stripQuotes(next.trim()))
                val wildcard = wildcardFlag && normalizedPath.contains("*")
                out.add(ParsedScriptImport(mode, wildcard, normalizedPath))
                i += 2
            } else {
                i += 1
            }
        }

        return out
    }

    fun validateScriptImports(imports: List<ParsedScriptImport>): List<String> {
        val errors = mutableListOf<String>()

        imports.forEach { imp ->
            if (imp.path.isBlank()) {
                errors.add("Empty script path")
                return@forEach
            }

            if (!imp.path.startsWith("extensions/")) {
                errors.add("Script path must start with extensions/: ${imp.path}")
            }

            if (!imp.wildcard) {
                if (!(imp.path.endsWith(".kts") || imp.path.endsWith(".kt"))) {
                    errors.add("Script must end with .kts or .kt: ${imp.path}")
                }
            } else if (!imp.path.contains("*")) {
                errors.add("Wildcard flag requires * in path: ${imp.path}")
            }
        }

        return errors
    }
}
