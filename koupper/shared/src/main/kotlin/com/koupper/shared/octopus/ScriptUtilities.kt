package com.koupper.shared.octopus

import java.io.File
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0

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
        """(?s)((?:@\w+(?:\([^)]*\))?\s*)+)(?:public|private|protected|internal\s+)?(?:[A-Za-z0-9_\s]*)(val|fun)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
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

data class ExportFunctionSignature(
    val packageName: String?,
    val imports: Map<String, String>,
    val parameterTypes: List<String>,
    val returnType: String,
    val code: String = ""
)

fun extractExportFunctionSignature(
    rawTypeName: String,
    signature: ExportFunctionSignature,
    classLoader: ClassLoader
): Class<*>? {
    val cleanType = rawTypeName
        .substringBefore("<")
        .removeSuffix("?")
        .trim()

    val builtIns = mapOf(
        "String" to String::class.java,
        "Int" to Int::class.javaObjectType,
        "Long" to Long::class.javaObjectType,
        "Double" to Double::class.javaObjectType,
        "Float" to Float::class.javaObjectType,
        "Boolean" to Boolean::class.javaObjectType,
        "Short" to Short::class.javaObjectType,
        "Byte" to Byte::class.javaObjectType,
        "Char" to Char::class.javaObjectType
    )

    builtIns[cleanType]?.let { return it }

    if (cleanType.contains('.')) {
        runCatching {
            return Class.forName(cleanType, true, classLoader)
        }
    }

    signature.imports[cleanType]?.let { fqcn ->
        runCatching {
            return Class.forName(fqcn, true, classLoader)
        }
    }

    signature.packageName?.let { pkg ->
        runCatching {
            return Class.forName("$pkg.$cleanType", true, classLoader)
        }
    }

    return null
}

fun extractExportFunctionSignature(input: String): ExportFunctionSignature? {
    val packageName = Regex(
        """(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*$"""
    ).find(input)?.groupValues?.get(1)

    val imports = linkedMapOf<String, String>()
    val importRegex = Regex(
        """(?m)^\s*import\s+([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_]\w*))?\s*$"""
    )

    for (m in importRegex.findAll(input)) {
        val fqcn = m.groupValues[1]
        val alias = m.groupValues[2].takeIf { it.isNotBlank() }
        val simpleName = alias ?: fqcn.substringAfterLast('.')
        imports[simpleName] = fqcn
    }

    val exportMatch = Regex("""@(?:[\w.]*\.)?Export\b""").find(input) ?: return null
    val tail = input.substring(exportMatch.range.first)

    val sig = Regex(
        """(?s)
           (?:.*?@(?:[\w.]*\.)?Export\b.*?)
           (?:\s*@[\w.]+(?:\([^()]*\))?\s*)*
           \s*val\s+`?[\w$]+`?\s*:\s*
           \((.*?)\)\s*->\s*
           ([^=\{\n;]+)
        """.trimIndent(),
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.COMMENTS)
    ).find(tail) ?: return null

    val paramsRaw = sig.groupValues[1].trim()
    val returnType = sig.groupValues[2].trim()
    val params = splitTypesTopLevel(paramsRaw)

    return ExportFunctionSignature(
        packageName = packageName,
        imports = imports,
        parameterTypes = params,
        returnType = returnType,
        code = input
    )
}

fun resolveClassFromArgName(
    rawTypeName: String,
    signature: ExportFunctionSignature,
    classLoader: ClassLoader,
    explicitClassName: String? = null
): Class<*>? {
    val cleanType = rawTypeName
        .substringBefore("<")
        .removeSuffix("?")
        .trim()
    val simpleType = cleanType.substringAfterLast('.')

    val builtIns = mapOf(
        "String" to String::class.java,
        "Int" to Int::class.javaObjectType,
        "Long" to Long::class.javaObjectType,
        "Double" to Double::class.javaObjectType,
        "Float" to Float::class.javaObjectType,
        "Boolean" to Boolean::class.javaObjectType,
        "Short" to Short::class.javaObjectType,
        "Byte" to Byte::class.javaObjectType,
        "Char" to Char::class.javaObjectType
    )

    builtIns[cleanType]?.let { return it }

    if (cleanType.contains('.')) {
        runCatching {
            return Class.forName(cleanType, true, classLoader)
        }
    }

    signature.imports[cleanType]?.let { fqcn ->
        runCatching {
            return Class.forName(fqcn, true, classLoader)
        }
    }

    signature.packageName?.let { pkg ->
        runCatching {
            return Class.forName("$pkg.$cleanType", true, classLoader)
        }
    }

    // Buscar si está definido inline en el script (soporta fqcn o simple name)
    val isDefinedInline = signature.code.contains(Regex("""(class|data class|object)\s+$simpleType\b"""))

    if (isDefinedInline) {
        runCatching {
            return Class.forName(cleanType, true, classLoader)
        }

        explicitClassName?.let { className ->
            val host = className.substringBefore("$", className)
            runCatching {
                return Class.forName("$host$$simpleType", true, classLoader)
            }
        }

        // Algunos runtimes de scripting prefijan el nombre
        runCatching {
            return Class.forName("Script\$$simpleType", true, classLoader)
        }
    }

    return null
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

data class DependentFunction(
    val fn: KProperty0<*>,
    val dependencies: MutableList<Any> = mutableListOf()
)

fun KProperty0<*>.dependsOn(vararg fns: Any): DependentFunction {
    return DependentFunction(this, fns.toMutableList())
}

fun Map<String, String>.toCliArgs(): String =
    this.entries.joinToString(" ") { (k, v) ->
        val needsQuotes = v.contains(" ") || v.contains(",")
        val escaped = v.replace("\"", "\\\"")
        if (needsQuotes) "$k=\"$escaped\"" else "$k=$escaped"
    }

fun normalizeObjectLiteralToJson(src: String): String {
    var s = src.trim()

    // Caso: JSON escapado tipo {\"a\":\"b\"}
    if (s.startsWith("{\\\"") || s.startsWith("[\\\"") || s.contains("\\\"")) {
        s = s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }

    // Si ya parece JSON (usa ":" en vez de "="), lo regresamos tal cual
    // (mínimo chequeo: objeto/array + contiene ":" o empieza con quote/brace/bracket)
    val looksJson =
        (s.startsWith("{") && s.endsWith("}") && s.contains(":")) ||
                (s.startsWith("[") && s.endsWith("]"))

    if (looksJson) return s

    // Si no es JSON, asumimos tu formato {k=v,...}
    require(s.startsWith("{") && s.endsWith("}")) { "Composed input debe verse como {k=v,...}" }

    val body = s.substring(1, s.length - 1).trim()
    if (body.isBlank()) return "{}"

    return buildString {
        append('{')
        val parts = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        parts.forEachIndexed { i, kv ->
            val idx = kv.indexOf('=')
            require(idx >= 1) { "Par inválido (esperaba k=v): $kv" }

            val k = kv.substring(0, idx).trim()
            val v = kv.substring(idx + 1).trim()

            if (i > 0) append(',')
            append('"').append(k.replace("\"", "\\\"")).append('"').append(':')

            val isJsonish = v.startsWith("{") || v.startsWith("[") || v.startsWith("\"")
            if (isJsonish) append(v) else append('"').append(v.replace("\"", "\\\"")).append('"')
        }
        append('}')
    }
}

fun looksLikeObjectLiteral(s: String): Boolean {
    val t = s.trim()
    return t.startsWith("{") && t.endsWith("}") && t.contains("=")
}


