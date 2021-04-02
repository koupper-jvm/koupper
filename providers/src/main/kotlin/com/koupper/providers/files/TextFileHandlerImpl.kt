package com.koupper.providers.files

import java.io.File
import java.io.FileWriter
import java.lang.StringBuilder
import java.net.URL

class TextFileHandlerImpl : TextFileHandler {
    override fun load(filePath: String, targetPath: String): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> this.loadFileFromUrl(filePath, targetPath)
            checkByPathType(filePath) === PathType.RESOURCE -> this.loadFileFromResource(filePath)
            else -> File(filePath)
        }
    }

    private fun loadFileFromUrl(fileUrl: String, targetPath: String): File {
        val fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1)

        return if (targetPath === "N/A") {
            downloadFile(URL(fileUrl), fileName) // Download the file in the current location.
        } else {
            downloadFile(URL(fileUrl), "$targetPath/${fileName}")
        }
    }

    private fun loadFileFromResource(filePath: String): File {
        return File(TextFileHandlerImpl::class.java.classLoader.getResource(buildResourcePathName(filePath)).path)
    }

    override fun zipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> this.zipFileFromUrl(filePath, targetPath, filesToIgnore)
            checkByPathType(filePath) === PathType.RESOURCE -> this.zipFileFromResource(filePath, targetPath, filesToIgnore)
            else -> this.zipFileFromPath(filePath, targetPath, filesToIgnore)
        }
    }

    private fun zipFileFromPath(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        val file = this.load(filePath, targetPath)

        val targetLocation = buildFinalTargetPath(targetPath, file.name)

        val zippedFile = buildZipFile(file.path, targetLocation, filesToIgnore)

        file.delete()

        return zippedFile
    }

    private fun zipFileFromUrl(fileUrl: String, targetPath: String, filesToIgnore: List<String>): File {
        val file = this.loadFileFromUrl(fileUrl, targetPath)

        val targetLocation = buildFinalTargetPath(targetPath, file.name)

        val zippedFile = buildZipFile(file.name, targetLocation, filesToIgnore)

        file.delete()

        return zippedFile
    }

    private fun zipFileFromResource(fileName: String, targetPath: String, filesToIgnore: List<String>): File {
        val resource = this.loadFileFromResource(buildResourcePathName(fileName))

        val targetLocation = buildFinalTargetPath(targetPath, resource.name)

        val zippedFile = buildZipFile(resource.path, targetLocation, filesToIgnore)

        resource.delete()

        return zippedFile
    }

    override fun unzipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> this.unzipFileFromUrl(filePath, targetPath, filesToIgnore)
            checkByPathType(filePath) === PathType.RESOURCE -> this.unzipFileFromResource(filePath, targetPath, filesToIgnore)
            else -> this.unzipFileFromPath(filePath, targetPath, filesToIgnore)
        }
    }

    private fun unzipFileFromPath(zipPath: String, targetPath: String, ignoring: List<String>): File {
        val zipFile = this.load(zipPath)

        return unzipFile(zipFile.path, ignoring, targetPath)
    }

    private fun unzipFileFromUrl(zipPath: String, targetPath: String, ignoring: List<String>): File {
        val zipFile = this.loadFileFromUrl(zipPath, targetPath)

        return unzipFile(zipFile.path, ignoring, targetPath)
    }

    private fun unzipFileFromResource(zipPath: String, targetPath: String, ignoring: List<String>): File {
        val zipFile = this.loadFileFromResource(zipPath)

        return unzipFile(zipFile.path, ignoring, zipFile.path.substring(0, zipFile.path.lastIndexOf("/")))
    }

    override fun signFile(filePath: String, metadata: Map<String, String>): File {
        return File("")
    }

    override fun getNumberLineFor(contentToFind: String, filePath: String): Long {
        return this.getNumberLinesFor(contentToFind, filePath)[0]
    }

    override fun getNumberLinesFor(contentToFind: String, filePath: String): List<Long> {
        return when {
            checkByPathType(filePath) === PathType.URL -> {
                val file = this.loadFileFromUrl(filePath, System.getProperty("java.io.tmpdir"))
                val numberOfLines = this.getNumberLinesFor(file, contentToFind)
                file.delete()
                numberOfLines
            }
            checkByPathType(filePath) === PathType.RESOURCE -> {
                val file = this.loadFileFromResource(filePath)
                this.getNumberLinesFor(file, contentToFind)
            }
            else -> {
                val file = this.load(filePath)
                this.getNumberLinesFor(file, contentToFind)
            }
        }
    }

    private fun getNumberLinesFor(file: File, contentToFind: String): List<Long> {
        var lineNumber = 1
        val matchingLinesNumbers = emptyList<Long>().toMutableList()

        file.forEachLine {
            if (it.contains(contentToFind)) matchingLinesNumbers.add(lineNumber.toLong())

            lineNumber++
        }

        return matchingLinesNumbers
    }

    override fun putLineBefore(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean): File {
        val file = this.load(filePath)

        return this.putLine(linePosition, newLine, file, false)
    }

    override fun putLineAfter(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean): File {
        return this.putLineBefore(linePosition.inc(), newLine, filePath, overrideOriginal)
    }

    override fun replaceLine(linePosition: Long, newLine: String, filePath: String, overrideOriginal: Boolean): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> {
                val file = this.loadFileFromUrl(filePath, System.getProperty("java.io.tmpdir"))

                this.putLine(linePosition, newLine, file, overrideOriginal = false, replaceLine = true)
            }
            checkByPathType(filePath) === PathType.RESOURCE -> {
                val file = this.loadFileFromResource(filePath)

                this.putLine(linePosition, newLine, file, overrideOriginal, true)
            }
            else -> {
                val file = this.load(filePath, System.getProperty("java.io.tmpdir"))

                this.putLine(linePosition, newLine, file, overrideOriginal, true)
            }
        }
    }

    private fun putLine(linePosition: Long, newLine: String, file: File, overrideOriginal: Boolean, replaceLine: Boolean = false): File {
        val newContent = StringBuilder()

        val lines = file.readLines()

        for ((lineNumber, content) in lines.iterator().withIndex()) {
            when {
                (lineNumber + 1).toLong() == linePosition && !replaceLine -> {
                    newContent.append(newLine)
                    if (content.isEmpty()) {
                        newContent.append("\n")
                    } else {
                        newContent.append("$content")
                    }
                }
                (lineNumber + 1).toLong() == linePosition && replaceLine -> {
                    newContent.append(newLine)
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
            FileWriter(file.path).write(newContent.toString())

            file
        }
    }

    override fun appendContentBefore(content: String, inOccurrenceNumber: Int, contentToAdd: String, filePath: String, overrideOriginal: Boolean): File {
        return this.appendContentBefore(this.load(filePath), content, inOccurrenceNumber, contentToAdd, overrideOriginal)
    }

    private fun appendContentBefore(file: File, contentToMatch: String, inOccurrenceNumber: Int, contentToAdd: String, overrideOriginal: Boolean): File {
        val matchingInfo = this.getRangeOfOccurrence(file, contentToMatch, inOccurrenceNumber)

        val matching = matchingInfo.entries.first()

        val line = this.getContentForLine(matching.key.toLong(), file.path)

        val finalLine = line.substring(0, matching.value.first).plus(contentToAdd.plus(line.substring(matching.value))).plus(line.substring(matching.value.last + 1))

        return this.replaceLine(matching.key.toLong(), finalLine, file.path, overrideOriginal)
    }

    override fun appendContentAfter(content: String, inOccurrenceNumber: Int, contentToAdd: String, filePath: String, overrideOriginal: Boolean): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> {
                val file = this.loadFileFromUrl(filePath, System.getProperty("java.io.tmpdir"))

                this.appendContentAfter(file, content, inOccurrenceNumber, contentToAdd, overrideOriginal)
            }
            checkByPathType(filePath) === PathType.RESOURCE -> {
                val file = this.loadFileFromResource(filePath)

                this.appendContentAfter(file, content, inOccurrenceNumber, contentToAdd, overrideOriginal)
            }
            else -> {
                val file = this.load(filePath, System.getProperty("java.io.tmpdir"))

                this.appendContentAfter(file, content, inOccurrenceNumber, contentToAdd, overrideOriginal)
            }
        }
    }

    override fun getContentFromLines(initialLine: Int, finalLine: Int, filePath: String): List<String> {
        TODO("Not yet implemented")
    }

    private fun appendContentAfter(file: File, contentToMatch: String, inOccurrenceNumber: Int, contentToAdd: String, overrideOriginal: Boolean): File {
        val rangeOfOccurrenceFound = this.getRangeOfOccurrence(file, contentToMatch, inOccurrenceNumber)

        val matching = rangeOfOccurrenceFound.entries.first()

        val line = this.getContentForLine(matching.key.toLong(), file.path)

        val finalLine = line.substring(0, matching.value.first).plus(line.substring(matching.value).plus(contentToAdd)).plus(line.substring(matching.value.last + 1))

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

        this.load(filePath).forEachLine { line ->
            numberOfLine++

            if (numberOfLine in (initialLine + 1) until finalLine) {
                contentBetween.add(line)
            }
        }

        return contentBetween
    }

    override fun getContentBetweenContent(firstContent: String, secondContent: String, inOccurrenceNumber: Int, filePath: String): List<String> {
        val file = this.load(filePath)

        val lineNumberForFirstMatch = this.getRangeOfOccurrence(file, firstContent, inOccurrenceNumber).entries.first().key

        val lineNumberForSecondMatch = this.getRangeOfOccurrence(file, secondContent, inOccurrenceNumber).entries.first().key

        return if (lineNumberForFirstMatch < lineNumberForSecondMatch) {
            this.getContentBetweenLines(lineNumberForFirstMatch, lineNumberForSecondMatch, file.path)
        } else  {
            val rangeOfFirstMatching = this.getRangeOfOccurrence(file, firstContent, inOccurrenceNumber).entries.first().value

            val rangeOfSecondMatching = this.getRangeOfOccurrence(file, secondContent, inOccurrenceNumber).entries.first().value

            val originalLine = this.getContentForLine(lineNumberForFirstMatch, file.path)

            listOf(originalLine.substring(rangeOfFirstMatching.last + 1, rangeOfSecondMatching.first))
        }
    }

    override fun getContentForLine(linePosition: Long, filePath: String): String {
        val file = this.load(filePath)

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