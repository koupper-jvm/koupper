package com.koupper.providers.files

import java.io.File
import java.lang.StringBuilder

interface TextFileHandler {
    /**
     * Read a file from a path. The file path might be a resource, http or local store locations.
     *
     * @property filePath the resource file path.
     * @return the file object.
     */
    fun read(filePath: String): String

    /**
     * Get the content between two chunk sections of strings.
     *
     * Because the implementation iterate over string data, the matching is made when the first occurrence is found. This
     * behavior can be changed using the inOccurrenceNumber property.
     *
     * @property firstContent the chunk of first-string content.
     * @property secondContent the chunk of second-string content.
     * @property inOccurrenceNumber the number of occurrences to make the match.
     * @property filePath the resource file path.
     * @return List of strings between the string chunks.
     */
    fun getContentBetweenContent(firstContent: String, secondContent: String, onOccurrenceNumber: Int = 1, filePath: String): MutableList<List<String>>

    /**
     * Get the line number for a specific chunk string
     *
     * @property contentToFind content to find.
     * @property filePath the file path.
     * @return The line number.
     */
    fun getNumberLineFor(contentToFind: String, filePath: String): Long

    fun getNumberLinesFor(contentToFind: String, filePath: String): List<Long>

    fun putLineBefore(linePosition: Long, newContent: String, filePath: String, overrideOriginal: Boolean = false): File

    fun putLineAfter(linePosition: Long, newContent: String, filePath: String, overrideOriginal: Boolean = false): File

    /**
     * Replace a line
     *
     * @property linePosition the line number.
     * @property newContent the new content.
     * @property filePath the file path.
     * @property overrideOriginal if true, override the original file else the change only stay in memory.
     * @return The object File.
     */
    fun replaceLine(linePosition: Long, newContent: String, filePath: String, overrideOriginal: Boolean = false): File

    /**
     * Receive a map of lineNumber-value to optimize the multiple object creations using [replaceLine] to modify the
     * same file on different lines.
     *
     * @property lines the map of lineNumber-content to apply on a specific file.
     * @property filePath the file path.
     * @property overrideOriginal if true, override the original file else the change only stay in memory.
     * @return The object File.
     */
    fun replaceMultipleLines(lines: Map<Long, String>, filePath: String, overrideOriginal: Boolean = false): File

    fun appendContentBefore(content: String, inOccurrenceNumber: Int = 1, contentToAdd: String, filePath: String, overrideOriginal: Boolean = false): File

    fun appendContentAfter(content: String, inOccurrenceNumber: Int = 1, contentToAdd: String, filePath: String, overrideOriginal: Boolean = false): File

    fun getContentFromLines(initialLine: Int, finalLine: Int, filePath: String): List<String>

    fun getContentBetweenLines(initialLine: Long, finalLine: Long, filePath: String): List<String>

    fun getContentForLine(linePosition: Long, filePath: String): String

    fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder
}