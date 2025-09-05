package com.koupper.shared.octopus

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


fun extractExportFunctionName(scriptContent: String): String? {
    val exportPattern = "@Export\\s+val\\s+(\\S+)\\s*:"
    val regex = Regex(exportPattern)
    val matchResult = regex.find(scriptContent)
    return matchResult?.groups?.get(1)?.value
}

fun extractExportFunctionSignature(input: String): Pair<List<String>, String>? {
    val isOnlySignature = input.trim().startsWith("(") && "->" in input

    return if (isOnlySignature) {
        val regex = Regex("""\((.*?)\)\s*->\s*(.+)""")
        val match = regex.find(input) ?: return null

        val paramList = match.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val returnType = match.groupValues[2].trim()

        Pair(paramList, returnType)
    } else {
        val regex = Regex("""@Export\s+val\s+\w+\s*:\s*\((.*?)\)\s*->\s*([\w<>,()?\s.]+)""")
        val match = regex.find(input) ?: return null

        val paramList = match.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val returnType = match.groupValues[2].trim()

        Pair(paramList, returnType)
    }
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

