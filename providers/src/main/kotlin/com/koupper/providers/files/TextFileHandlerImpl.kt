package com.koupper.providers.files

import java.io.File
import java.lang.Exception
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.text.StringBuilder

class TextFileHandlerImpl : TextFileHandler {
    private val fileHandler: FileHandler = FileHandlerImpl()
    private var globalFilePath: String = "undefined"
    private lateinit var globalTargetFile: File

    override fun using(filePath: String) : TextFileHandler {
        this.globalFilePath = filePath
        this.globalTargetFile = this.fileHandler.load(this.globalFilePath)

        return this
    }

    override fun getNumberLineFor(contentToFind: String, filePath: String): Int {
        return this.getNumberLinesFor(contentToFind, filePath)[0]
    }

    override fun getNumberLinesFor(contentToFind: String, filePath: String): List<Int> {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        var lineNumber = 1

        val matchingLinesNumbers = emptyList<Int>().toMutableList()

        file.forEachLine {
            if (it.contains(contentToFind)) matchingLinesNumbers.add(lineNumber.toInt())

            lineNumber++
        }

        return matchingLinesNumbers
    }

    override fun putLineBefore(
        linePosition: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return this.replaceLine(linePosition, newContent, filePath, overrideOriginal)
    }

    private fun modifyLine(
        linePosition: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean,
        insertAfter: Boolean
    ): File {
        if (this.globalFilePath == "undefined" && filePath == "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val file = if (this.globalFilePath != "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        val newContentBase = StringBuilder()
        val lines = file.readLines()

        for ((lineNumber, content) in lines.withIndex()) {
            // Check if we are inserting after a line
            if (insertAfter && (lineNumber + 1) == linePosition.inc()) {
                newContentBase.append(newContent).append("\n") // Insert new content after the line
            }

            // Always append the current line
            newContentBase.append(content).append("\n")

            // Check if we are replacing a line
            if (!insertAfter && (lineNumber + 1) == linePosition) {
                // Skip appending the current line because it will be replaced
                newContentBase.setLength(newContentBase.length - (content.length + 1)) // Remove last line appended
                newContentBase.append(newContent).append("\n") // Append new content instead
            }
        }

        return if (!overrideOriginal) {
            val tmpFile = File(System.getProperty("java.io.tmpdir"), file.name)
            tmpFile.writeText(newContentBase.toString())
            tmpFile
        } else {
            file.printWriter().use { out -> out.print(newContentBase.toString()) }
            file
        }
    }

    override fun putLineAfter(
        linePosition: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return modifyLine(linePosition, newContent, filePath, overrideOriginal, true)
    }

    override fun replaceLine(
        linePosition: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return modifyLine(linePosition, newContent, filePath, overrideOriginal, false)
    }


    override fun replaceMultipleLines(lines: Map<Int, String>, filePath: String, overrideOriginal: Boolean): File {
        TODO("Not yet implemented")
    }

    override fun appendContentBefore(
        contentToFind: String,
        inOccurrenceNumber: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        val matchingInfo = this.getRangeOfOccurrence(file, contentToFind, inOccurrenceNumber)

        val matching = matchingInfo.entries.first()

        val line = this.getContentForLine(matching.key, file.path)

        val finalLine = line.substring(0, matching.value.first).plus(newContent.plus(line.substring(matching.value)))
            .plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key, finalLine, file.path, overrideOriginal)
    }

    override fun appendContentAfter(
        contentToFind: String,
        inOccurrenceNumber: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        val rangeOfOccurrenceFound = this.getRangeOfOccurrence(file, contentToFind, inOccurrenceNumber)

        val matching = rangeOfOccurrenceFound.entries.first()

        val line = this.getContentForLine(matching.key, file.path)

        val finalLine = line.substring(0, matching.value.first).plus(line.substring(matching.value).plus(newContent))
            .plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key, finalLine, file.path, overrideOriginal)
    }

    override fun getContentBetweenLines(
        initialLine: Int,
        finalLine: Int,
        filePath: String,
        inclusiveMode: Boolean
    ): List<String> {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        var numberOfLine = 0
        val contentBetween = mutableListOf<String>()

        file.forEachLine { line ->
            numberOfLine++

            if (inclusiveMode) {
                if (numberOfLine in initialLine until finalLine + 1) {
                    contentBetween.add(line)
                }
            } else {
                if (numberOfLine in (initialLine + 1) until finalLine) {
                    contentBetween.add(line)
                }
            }
        }

        return contentBetween
    }

    override fun read(filePath: String): String {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        return file.readText(Charsets.UTF_8)
    }

    override fun getContentBetweenContent(
        firstContent: String,
        secondContent: String,
        onOccurrenceNumber: Int,
        filePath: String,
        inclusiveMode: Boolean
    ): List<String> {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val file = if (this.globalFilePath !== "undefined") this.globalTargetFile else this.fileHandler.load(filePath)

        var result: List<String> = emptyList()

        val lineNumberForFirstMatch = this.getRangeOfOccurrence(file, firstContent, onOccurrenceNumber).entries

        val lineNumberForSecondMatch = this.getRangeOfOccurrence(
            file,
            secondContent,
            1,
            lineNumberForFirstMatch.first().key
        ).entries

        if (lineNumberForFirstMatch.isNotEmpty() && lineNumberForSecondMatch.isNotEmpty()) {
            result = if (lineNumberForFirstMatch.first().key == lineNumberForSecondMatch.first().key) {
                listOf(
                    this.getContentForLine(lineNumberForFirstMatch.first().key, file.path)
                        .substring((lineNumberForFirstMatch.first().value.last + 1) until lineNumberForSecondMatch.first().value.first)
                )
            } else {
                this.getContentBetweenLines(
                    lineNumberForFirstMatch.first().key,
                    lineNumberForSecondMatch.first().key,
                    file.path,
                    inclusiveMode
                )
            }
        }

        return result
    }

    override fun getContentForLine(linePosition: Int, filePath: String): String {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        var numberOfLine = 0
        var content = ""

        file.forEachLine lit@{ line ->
            numberOfLine++

            if (linePosition == numberOfLine) {
                content += line

                return@lit
            }
        }

        return content
    }

    override fun bind(data: Map<String, String?>, filePath: String): StringBuilder {
        if (this.globalFilePath === "undefined" && filePath === "undefined") throw Exception("It's necessary a file to do operations.")

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val content = StringBuilder(this.read(finalFilePath))

        data.forEach { (key, value) ->
            if (content.contains(key.toRegex())) {
                val parsedVariable = content.replace(key.toRegex(), value.toString())

                content.clear()

                content.append(parsedVariable)
            }
        }

        return content
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): java.lang.StringBuilder {
        data.forEach { (key, value) ->
            if (content.contains(key.toRegex())) {
                val parsedVariable = content.replace(key.toRegex(), value.toString())

                content.clear()

                content.append(parsedVariable)
            }
        }

        return content
    }

    override fun remove(): Boolean {
        return globalTargetFile.delete()
    }

    private fun getRangeOfOccurrence(
        file: File,
        contentToMatch: String,
        onOccurrenceNumber: Int,
        fromLine: Int = 0
    ): Map<Int, IntRange> {
        if (onOccurrenceNumber == 0) {
            return emptyMap()
        }

        var numberOfLine = 0
        var numberOfMatching = 0
        val matchingInfo = mutableMapOf<Int, IntRange>()
        var matchFound = false

        file.forEachLine lit@{ line ->
            if (matchFound) return@lit

            numberOfLine++

            if (fromLine != 0 && numberOfLine < fromLine) {
                return@lit
            }

            if (line.contains(contentToMatch)) {
                val matches = Pattern.quote(contentToMatch).toRegex().findAll(line)

                matches.iterator().forEach { matching ->
                    numberOfMatching++

                    if (numberOfMatching == abs(onOccurrenceNumber)) {
                        matchingInfo[numberOfLine] = matching.range

                        matchFound = true

                        return@lit
                    }
                }
            }
        }

        matchFound = false

        return matchingInfo
    }
}
