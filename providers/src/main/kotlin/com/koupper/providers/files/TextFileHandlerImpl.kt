package com.koupper.providers.files

import java.io.File
import java.lang.Exception
import kotlin.text.StringBuilder

class TextFileHandlerImpl : TextFileHandler {
    private val fileHandler: FileHandler = FileHandlerImpl()
    private var globalFilePath: String = "undefined"

    override fun getNumberLineFor(contentToFind: String, filePath: String): Long {
        return this.getNumberLinesFor(contentToFind, filePath)[0]
    }

    override fun getNumberLinesFor(contentToFind: String, filePath: String): List<Long> {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        var lineNumber = 1

        val matchingLinesNumbers = emptyList<Long>().toMutableList()

        file.forEachLine {
            if (it.contains(contentToFind)) matchingLinesNumbers.add(lineNumber.toLong())

            lineNumber++
        }

        return matchingLinesNumbers
    }

    override fun putLineBefore(
        linePosition: Long,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return this.replaceLine(linePosition, newContent, filePath, overrideOriginal)
    }

    override fun putLineAfter(
        linePosition: Long,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return this.replaceLine(linePosition.inc(), newContent, filePath, overrideOriginal)
    }

    override fun replaceLine(
        linePosition: Long,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        val newContentBase = StringBuilder()

        val lines = file.readLines()

        for ((lineNumber, content) in lines.iterator().withIndex()) {
            when {
                (lineNumber + 1).toLong() == linePosition -> {
                    newContentBase.append("$newContent\n")
                }
                lineNumber == lines.size -> {
                    newContentBase.append(content)
                }
                else -> {
                    newContentBase.append("$content\n")
                }
            }
        }

        return if (!overrideOriginal) {
            val tmpFile = File(System.getProperty("java.io.tmpdir") + file.name)
            tmpFile.writeText(newContentBase.toString())
            tmpFile
        } else {
            File(file.path).printWriter().use { out -> out.println(newContentBase.toString()) }

            file
        }
    }

    override fun replaceMultipleLines(lines: Map<Long, String>, filePath: String, overrideOriginal: Boolean): File {
        TODO("Not yet implemented")
    }

    override fun appendContentBefore(
        contentToFind: String,
        inOccurrenceNumber: Int,
        newContent: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

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
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        val rangeOfOccurrenceFound = this.getRangeOfOccurrence(file, contentToFind, inOccurrenceNumber)

        val matching = rangeOfOccurrenceFound.entries.first()

        val line = this.getContentForLine(matching.key, file.path)

        val finalLine = line.substring(0, matching.value.first).plus(line.substring(matching.value).plus(newContent))
            .plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key, finalLine, file.path, overrideOriginal)
    }

    override fun getContentBetweenLines(initialLine: Long, finalLine: Long, filePath: String): List<String> {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        var numberOfLine = 0
        val contentBetween = mutableListOf<String>()

        file.forEachLine { line ->
            numberOfLine++

            if (numberOfLine in (initialLine + 1) until finalLine) {
                contentBetween.add(line)
            }
        }

        return contentBetween
    }

    override fun using(filePath: String) {
        this.globalFilePath = filePath
    }

    override fun read(filePath: String): String {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        return file.readText(Charsets.UTF_8)
    }

    override fun getContentBetweenContent(
        firstContent: String,
        secondContent: String,
        onOccurrenceNumber: Int,
        filePath: String
    ): MutableList<List<String>> {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        val result = mutableListOf<List<String>>()
        var occurrenceIndex = onOccurrenceNumber

        if (occurrenceIndex < 0) {
            occurrenceIndex += 2

            while (true) {
                val lineNumberForFirstMatch =
                    this.getRangeOfOccurrence(file, firstContent, occurrenceIndex).entries

                val lineNumberForSecondMatch =
                    this.getRangeOfOccurrence(file, secondContent, occurrenceIndex).entries

                if (lineNumberForFirstMatch.isNotEmpty() && lineNumberForSecondMatch.isNotEmpty()) {
                    result.add(
                        this.getContentBetweenLines(
                            lineNumberForFirstMatch.first().key,
                            lineNumberForSecondMatch.first().key,
                            file.path
                        )
                    )
                } else {
                    break
                }

                occurrenceIndex++
            }
        } else {
            val lineNumberForFirstMatch =
                this.getRangeOfOccurrence(file, firstContent, onOccurrenceNumber).entries

            val lineNumberForSecondMatch =
                this.getRangeOfOccurrence(file, secondContent, onOccurrenceNumber).entries

            if (lineNumberForFirstMatch.isNotEmpty() && lineNumberForSecondMatch.isNotEmpty()) {
                if (lineNumberForFirstMatch.first().key == lineNumberForSecondMatch.first().key) {
                    val content = this.getContentForLine(lineNumberForFirstMatch.first().key, file.path)

                    result.add(
                        listOf(
                            content.substring(
                                lineNumberForFirstMatch.first().value.last + 1,
                                lineNumberForSecondMatch.first().value.first
                            )
                        )
                    )
                } else {
                    result.add(
                        this.getContentBetweenLines(
                            lineNumberForFirstMatch.first().key,
                            lineNumberForSecondMatch.first().key,
                            file.path
                        )
                    )
                }
            }
        }

        return result
    }

    override fun getContentForLine(linePosition: Long, filePath: String): String {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

        val finalFilePath = if (this.globalFilePath !== "undefined") this.globalFilePath else filePath

        val file = this.fileHandler.load(finalFilePath)

        var numberOfLine = 0
        var content = ""

        file.forEachLine lit@{ line ->
            numberOfLine++

            if (linePosition == numberOfLine.toLong()) {
                content += line

                return@lit
            }
        }

        return content
    }

    override fun bind(data: Map<String, String?>, filePath: String): StringBuilder {
        if (filePath === "undefined" && this.globalFilePath === "undefined") {
            throw Exception("It's necessary a file to do operations.")
        }

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

    private fun getRangeOfOccurrence(file: File, contentToMatch: String, onOccurrenceNumber: Int): Map<Long, IntRange> {
        var numberOfLine = 0
        var numberOfMatching = 0
        val matchingInfo = mutableMapOf<Long, IntRange>()

        file.forEachLine lit@{ line ->
            numberOfLine++

            if (line.contains(contentToMatch)) {
                val matches = contentToMatch.toRegex().findAll(line)

                matches.iterator().forEach { matching ->
                    numberOfMatching++

                    if (numberOfMatching == onOccurrenceNumber) {
                        matchingInfo[numberOfLine.toLong()] = matching.range

                        return@lit
                    }
                }
            }
        }

        return matchingInfo
    }
}
