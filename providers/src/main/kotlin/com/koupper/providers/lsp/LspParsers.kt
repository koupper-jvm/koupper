package com.koupper.providers.lsp

/**
 * Pure parser functions for LSP response payloads.
 * All positions are converted from 0-based (LSP) to 1-based (public API).
 */
internal object LspParsers {

    fun parseRange(range: Any?): LspRange? {
        val obj   = range as? Map<*, *> ?: return null
        val start = obj["start"] as? Map<*, *> ?: return null
        val end   = obj["end"]   as? Map<*, *> ?: return null
        return LspRange(
            startLine   = ((start["line"]      as? Number)?.toInt() ?: 0) + 1,
            startColumn = ((start["character"] as? Number)?.toInt() ?: 0) + 1,
            endLine     = ((end["line"]        as? Number)?.toInt() ?: 0) + 1,
            endColumn   = ((end["character"]   as? Number)?.toInt() ?: 0) + 1
        )
    }

    fun parseDiagnostic(obj: Map<String, Any?>, uri: String): LspDiagnostic {
        val range = parseRange(obj["range"])
        val severity = when ((obj["severity"] as? Number)?.toInt()) {
            1    -> LspSeverity.ERROR
            2    -> LspSeverity.WARNING
            3    -> LspSeverity.INFORMATION
            4    -> LspSeverity.HINT
            else -> LspSeverity.ERROR
        }
        return LspDiagnostic(
            file      = uri.removePrefix("file://"),
            line      = range?.startLine   ?: 1,
            column    = range?.startColumn ?: 1,
            endLine   = range?.endLine     ?: 1,
            endColumn = range?.endColumn   ?: 1,
            message   = obj["message"] as? String ?: "",
            severity  = severity,
            code      = obj["code"]?.toString()
        )
    }

    fun parseHover(result: Any?): LspHover? {
        val obj = result as? Map<*, *> ?: return null
        val contents = obj["contents"]
        val text = when (contents) {
            is String    -> contents
            is Map<*, *> -> contents["value"] as? String ?: return null
            is List<*>   -> contents.joinToString("\n") { item ->
                when (item) {
                    is String    -> item
                    is Map<*, *> -> item["value"] as? String ?: ""
                    else         -> ""
                }
            }
            else -> return null
        }
        if (text.isBlank()) return null
        val range = parseRange(obj["range"])
        return LspHover(text, range?.startLine, range?.startColumn, range?.endLine, range?.endColumn)
    }

    fun parseLocation(obj: Map<String, Any?>): LspLocation? {
        val uri   = (obj["uri"] ?: obj["targetUri"]) as? String ?: return null
        val range = parseRange(obj["range"] ?: obj["targetRange"]) ?: return null
        return LspLocation(
            file   = uri.removePrefix("file://"),
            line   = range.startLine,
            column = range.startColumn
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseLocations(result: Any?): List<LspLocation> = when (result) {
        null         -> emptyList()
        is Map<*, *> -> listOfNotNull(parseLocation(result as Map<String, Any?>))
        is List<*>   -> (result as List<*>).filterIsInstance<Map<String, Any?>>().mapNotNull { parseLocation(it) }
        else         -> emptyList()
    }
}
