package com.koupper.providers.files

import java.io.File

interface TextFileHandler : FileHandler {
    fun read(filePath: String): String

    fun getContentBetweenContent(firstContent: String, secondContent: String, inOccurrenceNumber: Int = 1, filePath: String): List<String>

    fun getNumberLineFor(contentToFind: String, filePath: String): Long

    fun getNumberLinesFor(contentToFind: String, filePath: String): List<Long>

    fun putLineBefore(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean = false): File

    fun putLineAfter(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean = false): File

    fun replaceLine(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean = false): File

    fun appendContentBefore(content: String, inOccurrenceNumber: Int = 1, contentToAdd: String, filePath: String, overrideOriginal: Boolean = false): File

    fun appendContentAfter(content: String, inOccurrenceNumber: Int = 1, contentToAdd: String, filePath: String, overrideOriginal: Boolean = false): File

    fun getContentFromLines(initialLine: Int, finalLine: Int, filePath: String): List<String>

    fun getContentBetweenLines(initialLine: Long, finalLine: Long, filePath: String): List<String>

    fun getContentForLine(linePosition: Long, filePath: String): String
}