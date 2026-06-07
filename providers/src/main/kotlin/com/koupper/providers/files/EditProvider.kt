package com.koupper.providers.files

import java.io.File

sealed class EditResult {
    data class Ok(val occurrences: Int = 1, val content: String? = null) : EditResult()
    data class Err(val reason: String, val code: EditErrorCode) : EditResult()
}

enum class EditErrorCode {
    FILE_NOT_FOUND,
    STRING_NOT_FOUND,
    NOT_UNIQUE,
    LINE_OUT_OF_RANGE,
    IO_ERROR,
}

/**
 * Surgical code editor for agent use.
 *
 * Key invariant on [replace]: if [oldString] appears more than once the call fails with
 * [EditErrorCode.NOT_UNIQUE] unless [replaceAll] is true. This prevents accidental
 * multi-site mutations — the agent must supply enough context to make the match unique.
 */
interface EditProvider {

    /**
     * Replace the first (or all) occurrence(s) of [oldString] with [newString].
     * Returns [EditErrorCode.NOT_UNIQUE] when the string appears more than once and
     * [replaceAll] is false, forcing the caller to widen the context window.
     */
    fun replace(file: File, oldString: String, newString: String, replaceAll: Boolean = false): EditResult

    /**
     * Read lines [fromLine]..[toLine] (1-based, inclusive) and return them as
     * [EditResult.Ok.content]. Useful for the agent to inspect before editing.
     */
    fun view(file: File, fromLine: Int, toLine: Int): EditResult

    /**
     * Replace lines [fromLine]..[toLine] (1-based, inclusive) with [newContent].
     * [newContent] may span multiple lines.
     */
    fun replaceLines(file: File, fromLine: Int, toLine: Int, newContent: String): EditResult

    /**
     * Delete lines [fromLine]..[toLine] (1-based, inclusive).
     */
    fun deleteLines(file: File, fromLine: Int, toLine: Int): EditResult
}
