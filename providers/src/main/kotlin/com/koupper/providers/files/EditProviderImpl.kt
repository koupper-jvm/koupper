package com.koupper.providers.files

import java.io.File

class EditProviderImpl : EditProvider {

    override fun replace(file: File, oldString: String, newString: String, replaceAll: Boolean): EditResult {
        if (!file.exists()) return EditResult.Err("File not found: ${file.absolutePath}", EditErrorCode.FILE_NOT_FOUND)
        return runCatching {
            val content = file.readText()
            val count   = countOccurrences(content, oldString)
            when {
                count == 0 -> EditResult.Err(
                    "String not found in ${file.name}",
                    EditErrorCode.STRING_NOT_FOUND
                )
                count > 1 && !replaceAll -> EditResult.Err(
                    "Found $count occurrences in ${file.name} — add more surrounding context to make it unique, or pass replaceAll=true",
                    EditErrorCode.NOT_UNIQUE
                )
                else -> {
                    val result = if (replaceAll) content.replace(oldString, newString)
                                 else replaceFirst(content, oldString, newString)
                    file.writeText(result)
                    EditResult.Ok(occurrences = count)
                }
            }
        }.getOrElse { e -> EditResult.Err("IO error: ${e.message}", EditErrorCode.IO_ERROR) }
    }

    override fun view(file: File, fromLine: Int, toLine: Int): EditResult {
        if (!file.exists()) return EditResult.Err("File not found: ${file.absolutePath}", EditErrorCode.FILE_NOT_FOUND)
        return runCatching {
            val lines = splitLines(file.readText())
            val rangeErr = checkRange(fromLine, toLine, lines.size)
            if (rangeErr != null) return rangeErr
            val content = lines.subList(fromLine - 1, toLine).joinToString("\n")
            EditResult.Ok(content = content)
        }.getOrElse { e -> EditResult.Err("IO error: ${e.message}", EditErrorCode.IO_ERROR) }
    }

    override fun replaceLines(file: File, fromLine: Int, toLine: Int, newContent: String): EditResult {
        if (!file.exists()) return EditResult.Err("File not found: ${file.absolutePath}", EditErrorCode.FILE_NOT_FOUND)
        return runCatching {
            val text  = file.readText()
            val lines = splitLines(text)
            val rangeErr = checkRange(fromLine, toLine, lines.size)
            if (rangeErr != null) return rangeErr
            val result = buildString {
                append(lines.subList(0, fromLine - 1).joinToString("\n"))
                if (fromLine > 1) append("\n")
                append(newContent)
                val tail = lines.subList(toLine, lines.size)
                if (tail.isNotEmpty()) { append("\n"); append(tail.joinToString("\n")) }
                if (text.endsWith("\n")) append("\n")
            }
            file.writeText(result)
            EditResult.Ok(occurrences = toLine - fromLine + 1)
        }.getOrElse { e -> EditResult.Err("IO error: ${e.message}", EditErrorCode.IO_ERROR) }
    }

    override fun deleteLines(file: File, fromLine: Int, toLine: Int): EditResult {
        if (!file.exists()) return EditResult.Err("File not found: ${file.absolutePath}", EditErrorCode.FILE_NOT_FOUND)
        return runCatching {
            val text  = file.readText()
            val lines = splitLines(text)
            val rangeErr = checkRange(fromLine, toLine, lines.size)
            if (rangeErr != null) return rangeErr
            val kept = lines.subList(0, fromLine - 1) + lines.subList(toLine, lines.size)
            val result = kept.joinToString("\n") + if (text.endsWith("\n")) "\n" else ""
            file.writeText(result)
            EditResult.Ok(occurrences = toLine - fromLine + 1)
        }.getOrElse { e -> EditResult.Err("IO error: ${e.message}", EditErrorCode.IO_ERROR) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun countOccurrences(text: String, pattern: String): Int {
        var count = 0
        var idx   = 0
        while (true) {
            idx = text.indexOf(pattern, idx)
            if (idx < 0) break
            count++
            idx += pattern.length
        }
        return count
    }

    private fun replaceFirst(text: String, old: String, new: String): String {
        val idx = text.indexOf(old)
        if (idx < 0) return text
        return text.substring(0, idx) + new + text.substring(idx + old.length)
    }

    // Splits preserving behaviour of readLines() — trailing newline → no empty last element.
    private fun splitLines(text: String): List<String> {
        val stripped = if (text.endsWith("\n")) text.dropLast(1) else text
        return stripped.split("\n")
    }

    private fun checkRange(fromLine: Int, toLine: Int, total: Int): EditResult.Err? {
        if (fromLine < 1 || toLine < fromLine || toLine > total)
            return EditResult.Err(
                "Lines $fromLine..$toLine out of range (file has $total lines)",
                EditErrorCode.LINE_OUT_OF_RANGE
            )
        return null
    }
}
