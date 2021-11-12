package com.koupper.providers.files

import com.koupper.container.interfaces.Container
import java.io.File
import java.lang.StringBuilder

class TextFileHandlerImpl(container: Container) : TextFileHandler {
    private var fileHandler: FileHandler = container.createInstanceOf(FileHandler::class)

    override fun getNumberLineFor(contentToFind: String, filePath: String): Long {
        return this.getNumberLinesFor(contentToFind, filePath)[0]
    }

    override fun getNumberLinesFor(contentToFind: String, filePath: String): List<Long> {
        val file = this.fileHandler.load(filePath, System.getProperty("java.io.tmpdir"))

        return getNumberLinesFor(contentToFind, file)
    }

    private fun getNumberLinesFor(contentToFind: String, file: File): List<Long> {
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
        val file = this.fileHandler.load(filePath, System.getProperty("java.io.tmpdir"))

        return this.putLine(linePosition, newContent, file, overrideOriginal, replaceLine = true)
    }

    override fun replaceMultipleLines(lines: Map<Long, String>, filePath: String, overrideOriginal: Boolean): File {
        TODO("Not yet implemented")
    }

    private fun putLine(
        linePosition: Long,
        newLine: String,
        file: File,
        overrideOriginal: Boolean = false,
        replaceLine: Boolean = false
    ): File {
        val newContent = StringBuilder()

        val lines = file.readLines()

        for ((lineNumber, content) in lines.iterator().withIndex()) {
            when {
                (lineNumber + 1).toLong() == linePosition && !replaceLine -> {
                    newContent.append("\n")
                }
                (lineNumber + 1).toLong() == linePosition && replaceLine -> {
                    newContent.append("$newLine\n")
                }
                lineNumber == lines.size -> {
                    newContent.append(content)
                }
                else -> {
                    newContent.append("$content\n")
                }
            }
        }

        return if (!overrideOriginal) {
            val tmpFile = File(System.getProperty("java.io.tmpdir") + file.name)
            tmpFile.writeText(newContent.toString())

            tmpFile
        } else {
            File(file.path).printWriter().use { out -> out.println(newContent.toString()) }

            file
        }
    }

    override fun appendContentBefore(
        content: String,
        inOccurrenceNumber: Int,
        contentToAdd: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return this.appendContentBefore(
            this.fileHandler.load(filePath),
            content,
            inOccurrenceNumber,
            contentToAdd,
            overrideOriginal
        )
    }

    private fun appendContentBefore(
        file: File,
        contentToMatch: String,
        inOccurrenceNumber: Int,
        contentToAdd: String,
        overrideOriginal: Boolean
    ): File {
        val matchingInfo = this.getRangeOfOccurrence(file, contentToMatch, inOccurrenceNumber)

        val matching = matchingInfo.entries.first()

        val line = this.getContentForLine(matching.key, file.path)

        val finalLine = line.substring(0, matching.value.first).plus(contentToAdd.plus(line.substring(matching.value)))
            .plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key, finalLine, file.path, overrideOriginal)
    }

    override fun appendContentAfter(
        content: String,
        inOccurrenceNumber: Int,
        contentToAdd: String,
        filePath: String,
        overrideOriginal: Boolean
    ): File {
        return this.appendContentAfter(
            this.fileHandler.load(filePath, System.getProperty("java.io.tmpdir")),
            content,
            inOccurrenceNumber,
            contentToAdd,
            overrideOriginal
        )
    }

    override fun getContentFromLines(initialLine: Int, finalLine: Int, filePath: String): List<String> {
        TODO("Not yet implemented")
    }

    private fun appendContentAfter(
        file: File,
        contentToMatch: String,
        inOccurrenceNumber: Int,
        contentToAdd: String,
        overrideOriginal: Boolean
    ): File {
        val rangeOfOccurrenceFound = this.getRangeOfOccurrence(file, contentToMatch, inOccurrenceNumber)

        val matching = rangeOfOccurrenceFound.entries.first()

        val line = this.getContentForLine(matching.key.toLong(), file.path)

        val finalLine = line.substring(0, matching.value.first).plus(line.substring(matching.value).plus(contentToAdd))
            .plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key.toLong(), finalLine, file.path, overrideOriginal)
    }

    private fun getRangeOfOccurrence(file: File, contentToMatch: String, inOccurrenceNumber: Int): Map<Long, IntRange> {
        var numberOfLine = 0
        var numberOfMatching = 0
        val matchingInfo = mutableMapOf<Long, IntRange>()

        file.forEachLine lit@{ line ->
            numberOfLine++

            if (line.contains(contentToMatch)) {
                val matches = contentToMatch.toRegex().findAll(line)

                matches.iterator().forEach { matching ->
                    numberOfMatching++

                    if (inOccurrenceNumber == numberOfMatching) {
                        matchingInfo[numberOfLine.toLong()] = matching.range

                        return@lit
                    }
                }
            }
        }

        return matchingInfo
    }

    override fun getContentBetweenLines(initialLine: Long, finalLine: Long, filePath: String): List<String> {
        var numberOfLine = 0
        val contentBetween = mutableListOf<String>()

        this.fileHandler.load(filePath).forEachLine { line ->
            numberOfLine++

            if (numberOfLine in (initialLine + 1) until finalLine) {
                contentBetween.add(line)
            }
        }

        return contentBetween
    }

    override fun read(filePath: String): String {
        return this.fileHandler.load(filePath).readText(Charsets.UTF_8)
    }

    override fun getContentBetweenContent(
        firstContent: String,
        secondContent: String,
        inOccurrenceNumber: Int,
        filePath: String
    ): List<String> {
        val file = this.fileHandler.load(filePath)

        val lineNumberForFirstMatch =
            this.getRangeOfOccurrence(file, firstContent, inOccurrenceNumber).entries.first().key

        val lineNumberForSecondMatch =
            this.getRangeOfOccurrence(file, secondContent, inOccurrenceNumber).entries.first().key

        return if (lineNumberForFirstMatch < lineNumberForSecondMatch) {
            this.getContentBetweenLines(lineNumberForFirstMatch, lineNumberForSecondMatch, file.path)
        } else {
            val rangeOfFirstMatching =
                this.getRangeOfOccurrence(file, firstContent, inOccurrenceNumber).entries.first().value

            val rangeOfSecondMatching =
                this.getRangeOfOccurrence(file, secondContent, inOccurrenceNumber).entries.first().value

            val originalLine = this.getContentForLine(lineNumberForFirstMatch, file.path)

            listOf(originalLine.substring(rangeOfFirstMatching.last + 1, rangeOfSecondMatching.first))
        }
    }

    override fun getContentForLine(linePosition: Long, filePath: String): String {
        val file = this.fileHandler.load(filePath)

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
}
