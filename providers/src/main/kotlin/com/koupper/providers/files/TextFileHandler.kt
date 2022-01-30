package com.koupper.providers.files

import java.io.File
import java.lang.StringBuilder

interface TextFileHandler {
    /**
     * Uses a file as global file for all operations on this class.
     *
     * @property filePath the resource file path.
     */
    fun using(filePath: String = "undefined")

    /**
     * Reads the content of a file from a path. The file path might be a resource, http or local storages.
     *
     * @property filePath the resource file path.
     * @return the text file content .
     */
    fun read(filePath: String = "undefined"): String

    /**
     * Gets the content between two chunk of strings.
     *
     * Because the implementation iterate over string data, the matching process is done when the first matching is found.
     * The behavior can be changed using the inOccurrenceNumber property.
     *
     * @property firstContent the chunk of first-string content.
     * @property secondContent the chunk of second-string content.
     * @property inOccurrenceNumber the number of occurrences to make the match.
     * @property filePath the resource file path.
     * @return List of strings between the string chunks.
     */
    fun getContentBetweenContent(
        firstContent: String,
        secondContent: String,
        onOccurrenceNumber: Int = 1,
        filePath: String = "undefined"
    ): MutableList<List<String>>

    /**
     * Gets the line number for specific chunk string
     *
     * @property contentToFind content to find.
     * @property filePath the file path.
     * @return The line number.
     */
    fun getNumberLineFor(contentToFind: String, filePath: String = "undefined"): Long

    /**
     * Gets the line numbers for matching content
     *
     * @property contentToFind content to find.
     * @property filePath the file path.
     * @return A list of matching line numbers
     */
    fun getNumberLinesFor(contentToFind: String, filePath: String = "undefined"): List<Long>

    /**
     * Puts a content before specified line number.
     * This behavior occurs on memory, but can be overridden by using overrideOriginal property that commits the changes
     * to the original file.
     *
     * @property linePosition reference line number.
     * @property newContent the content to put.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return the File object with the added content
     */
    fun putLineBefore(linePosition: Long, newContent: String, filePath: String = "undefined", overrideOriginal: Boolean = false): File

    /**
     * Puts a content after specified line number.
     * This behavior occurs on memory, but can be overridden by using overrideOriginal property that commits the changes
     * to the original file.
     *
     * @property linePosition reference line number.
     * @property newContent the content to put.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return the File object with the added content
     */
    fun putLineAfter(linePosition: Long, newContent: String, filePath: String = "undefined", overrideOriginal: Boolean = false): File

    /**
     * Replace a line
     *
     * @property linePosition the line number.
     * @property newContent the new content.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return The object File.
     */
    fun replaceLine(linePosition: Long, newContent: String, filePath: String = "undefined", overrideOriginal: Boolean = false): File

    /**
     * Replace multiple lines
     *
     * @property lines a map of lineNumber-content.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return The object File.
     */
    fun replaceMultipleLines(lines: Map<Long, String>, filePath: String = "undefined", overrideOriginal: Boolean = false): File

    /**
     * Append content before to other content
     *
     * @property contentToFind content to find.
     * @property inOccurrenceNumber the occurrence number to add before.
     * @property newContent the new content.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return The object File.
     */
    fun appendContentBefore(
        contentToFind: String,
        inOccurrenceNumber: Int = 1,
        newContent: String,
        filePath: String = "undefined",
        overrideOriginal: Boolean = false
    ): File

    /**
     * Append content after to other content
     *
     * @property contentToFind content to find.
     * @property inOccurrenceNumber the occurrence number to add after.
     * @property newContent the new content.
     * @property filePath the file path to read.
     * @property overrideOriginal specify if the original file should be overridden.
     * @return The object File.
     */
    fun appendContentAfter(
        contentToFind: String,
        inOccurrenceNumber: Int = 1,
        newContent: String,
        filePath: String = "undefined",
        overrideOriginal: Boolean = false
    ): File

    /**
     * Gets the content between specified lines numbers
     *
     * @property initialLine indicate the number to start.
     * @property finalLine the final line number.
     * @property filePath the file path to read.
     * @return A list of matching lines.
     */
    fun getContentBetweenLines(initialLine: Long, finalLine: Long, filePath: String = "undefined"): List<String>

    /**
     * Get the content of a specific line
     *
     * @property linePosition line to get the content.
     * @property filePath the file path to read.
     * @return The content.
     */
    fun getContentForLine(linePosition: Long, filePath: String = "undefined"): String

    /**
     * Binds "property-value" to template file in the specified file path.
     *
     * @property data the pair key-value to bind.
     * @property filePath the path to content.
     * @return The binding content.
     */
    fun bind(data: Map<String, String?>, filePath: String = "undefined"): StringBuilder

    /**
     * Binds "property-value" to template content.
     *
     * @property data the pair key-value to bind.
     * @property content the content to bind.
     * @return The binding content.
     */
    fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder
}
