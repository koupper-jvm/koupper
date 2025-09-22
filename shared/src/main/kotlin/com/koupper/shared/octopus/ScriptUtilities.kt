package com.koupper.shared.octopus

import jdk.nashorn.api.scripting.ScriptUtils
import java.io.File
import kotlin.reflect.KFunction

fun extractAllAnnotations(script: String): Map<String, Map<String, String>> {
    val annotationRegex = Regex("""@(\w+)(\(([^)]*)\))?""")
    val annotations = mutableMapOf<String, Map<String, String>>()

    for (match in annotationRegex.findAll(script)) {
        val name = match.groupValues[1]
        val paramsString = match.groupValues.getOrNull(3)?.trim().orEmpty()

        val params = if (paramsString.isNotEmpty()) {
            paramsString.split(",")
                .mapNotNull {
                    val parts = it.split("=").map { it.trim() }
                    if (parts.size == 2) parts[0] to parts[1].removeSurrounding("\"") else null
                }.toMap()
        } else {
            emptyMap()
        }

        annotations[name] = params
    }

    return annotations
}

fun extractExportedAnnotations(script: String): Pair<String, Map<String, Map<String, String>>>? {
    val declWithAnns = Regex(
        """(?s)
        ((?:@\w+(?:\([^)]*\))?\s*)+)   # bloque de anotaciones (una o más)
        (?:public|private|protected|internal\s+)?   # visibilidad opcional
        (?:[A-Za-z0-9_\s]*)            # modificadores opcionales
        (val|fun)\s+
        (`[^`]+`|[A-Za-z_][A-Za-z0-9_]*) # nombre
        """.trimIndent(),
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE, RegexOption.COMMENTS)
    )

    val singleAnn = Regex("""@([\w.]+)\s*(?:\(([^)]*)\))?""")
    val argRegex  = Regex("""(\w+)\s*=\s*("[^"]*"|'[^']*'|[^,\s)]+)""")

    for (m in declWithAnns.findAll(script)) {
        val annsBlock = m.groupValues[1]
        val foundAnns = singleAnn.findAll(annsBlock).toList()
        val hasExport = foundAnns.any { it.groupValues[1].endsWith("Export") }
        if (!hasExport) continue

        val rawName = m.groupValues[3]
        val name = rawName.trim('`')

        val annMap = linkedMapOf<String, Map<String, String>>()
        for (ann in foundAnns) {
            val simple = ann.groupValues[1].substringAfterLast('.')
            val rawArgs = ann.groupValues[2]

            val args = mutableMapOf<String, String>()
            if (rawArgs.isNotBlank()) {
                for (a in argRegex.findAll(rawArgs)) {
                    val key = a.groupValues[1]
                    val value = a.groupValues[2].trim('"', '\'')
                    args[key] = value
                }
            }
            annMap[simple] = args
        }

        return name to annMap
    }
    return null
}

fun extractExportFunctionName(scriptContent: String): String? {
    val lines = scriptContent.lines()

    val exportLineIndex = lines.indexOfFirst { it.contains("@Export") }
    if (exportLineIndex == -1) return null

    for (i in exportLineIndex until lines.size) {
        val line = lines[i]
        val valMatch = Regex("""val\s+(`?[\w$]+`?)\s*:""").find(line)
        if (valMatch != null) {
            return valMatch.groups[1]?.value
        }
    }

    return null
}

fun extractExportFunctionSignature(input: String): Pair<List<String>, String>? {
    // Primero busca la posición de @Export para recortar
    val exportMatch = Regex("""@(?:[\w.]*\.)?Export\b""").find(input) ?: return null
    val tail = input.substring(exportMatch.range.first)

    // Regex mejorado que maneja anotaciones en cualquier orden y multilínea
    val sig = Regex(
        """(?s)                              # DOTALL
           (?:.*?@(?:[\w.]*\.)?Export\b.*?)  # línea con @Export y cualquier cosa alrededor
           (?:\s*@[\w.]+(?:\([^()]*\))?\s*)* # otras anotaciones opcionales (pueden estar después)
           \s*val\s+`?[\w$]+`?\s*:\s*        # val nombre :
           \((.*?)\)\s*->\s*                 # (1) parámetros
           ([^=\{\n;]+)                      # (2) retorno
        """.trimIndent(),
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.COMMENTS)
    ).find(tail) ?: return null

    val paramsRaw = sig.groupValues[1].trim()
    val returnType = sig.groupValues[2].trim()
    val params = splitTypesTopLevel(paramsRaw)
    return params to returnType
}

// Split por comas a nivel 0 (soporta genéricos y lambdas) - esta función está bien
private fun splitTypesTopLevel(s: String): List<String> {
    if (s.isBlank()) return emptyList()
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var a = 0; var p = 0; var b = 0 // < >, ( ), [ ]

    for (c in s) {
        when (c) {
            '<' -> { a++; sb.append(c) }
            '>' -> { a--; sb.append(c) }
            '(' -> { p++; sb.append(c) }
            ')' -> { p--; sb.append(c) }
            '[' -> { b++; sb.append(c) }
            ']' -> { b--; sb.append(c) }
            ',' -> if (a == 0 && p == 0 && b == 0) {
                val piece = sb.toString().trim()
                if (piece.isNotEmpty()) out.add(piece)
                sb.clear()
            } else sb.append(c)
            else -> sb.append(c)
        }
    }
    val last = sb.toString().trim()
    if (last.isNotEmpty()) out.add(last)
    return out
}

private fun parseParameters(parameters: String): List<String> {
    val paramList = mutableListOf<String>()
    val currentParam = StringBuilder()

    var angleBrackets = 0
    var parentheses = 0
    var squareBrackets = 0

    for (char in parameters) {
        when (char) {
            '<' -> angleBrackets++
            '>' -> angleBrackets--
            '(' -> parentheses++
            ')' -> parentheses--
            '[' -> squareBrackets++
            ']' -> squareBrackets--
            ',' -> {
                if (angleBrackets == 0 && parentheses == 0 && squareBrackets == 0) {
                    paramList.add(currentParam.toString().trim())
                    currentParam.clear()
                    continue
                }
            }
        }
        currentParam.append(char)
    }

    if (currentParam.isNotBlank()) {
        paramList.add(currentParam.toString().trim())
    }

    return paramList
}

fun normalizeSignature(sig: String): String {
    return sig
        .replace("→", "->")
        .replace("\u003E", "->")
        .replace("kotlin.", "")
        .replace("\\s+".toRegex(), "")
}

fun KFunction<*>.buildSignature(): String {
    val paramTypes = this.parameters
        .drop(1) // drop receiver
        .joinToString(",") { it.type.toString().replace("kotlin.", "") }

    val returnType = this.returnType.toString().replace("kotlin.", "")
    return "($paramTypes) -> $returnType"
}

fun signaturesMatch(reflected: String, extracted: String): Boolean {
    return normalizeSignature(reflected) == normalizeSignature(extracted)
}

fun sha256Of(file: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { fis ->
        val buf = ByteArray(8192)
        var r: Int
        while (fis.read(buf).also { r = it } != -1) md.update(buf, 0, r)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun readTextOrNull(path: String?): String? =
    path?.let { p -> runCatching { File(p).takeIf { it.isFile }?.readText() }.getOrNull() }

