package com.koupper.providers.files

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

val zipFile: (String, String, List<String>) -> File = { file, targetLocation, filesToIgnore ->
    val inputDirectory = File(file)

    val outputZipFile = File(targetLocation)

    ZipOutputStream(
            BufferedOutputStream(
                    FileOutputStream(outputZipFile)
            )
    ).use { zos ->
        inputDirectory.walkTopDown().forEach lit@{ file ->
            var zipFileName = file.absolutePath.removePrefix(inputDirectory.absolutePath).removePrefix("/")

            if (zipFileName.isEmpty()) {
                zipFileName = file.name
            }

            filesToIgnore.forEach {
                if (zipFileName.contains("$it(/.*)?$".toRegex())) return@lit
            }

            val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")

            zos.putNextEntry(entry)

            if (file.isFile) {
                file.inputStream().copyTo(zos)
            }
        }
    }

    outputZipFile
}

val downloadFile: (url: URL, targetFileName: String) -> File = { url, targetFileName ->
    url.openStream().use { `in` -> Files.copy(`in`, Paths.get(targetFileName)) }

    File(targetFileName)
}

val unzipFile: (String, List<String>, String) -> File = { zipName, filesToIgnore, targetLocation ->
    val path = if (targetLocation.isNotEmpty()) "$targetLocation/" else targetLocation
    var unzippedFolderName = ""

    ZipFile(zipName).use { zip ->
        unzippedFolderName = zip.name

        zip.entries().asSequence().forEach { entry ->
            filesToIgnore.forEach lit@{
                if (it.contains(entry.name)) return@lit
            }

            if (entry.isDirectory) {
                File("$path${entry.name}").mkdir()
            } else {
                zip.getInputStream(entry).use { input ->
                    File("$path${entry.name}").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    File("$path${unzippedFolderName}")
}

val listContentOfZippedFile: (String) -> List<String> = {
    val listOfFiles = emptyList<String>().toMutableList()

    ZipFile(it).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            listOfFiles.add(entry.name)
        }
    }

    listOfFiles
}

interface FileHandler {
    fun readFileFromPath(filePath: String): File

    fun readFileFromUrl(fileUrl: String, downloadPath: String = ""): File

    fun readFileFromResource(filePath: String): File

    fun zipFileFromPath(filePath: String = "", downloadPath: String = "", filesToIgnore: List<String> = emptyList()): File

    fun zipFileFromUrl(fileUrl: String = "", downloadPath: String = "", filesToIgnore: List<String> = emptyList()): File

    fun zipFileFromResource(fileName: String, downloadPath: String = "", filesToIgnore: List<String> = emptyList()): File

    fun unzipFileFromPath(zipPath: String): File

    fun unzipFileFromUrl(zipUrl: String, downloadPath: String = ""): File

    fun unzipFileFromResource(zipName: String, ignoring: List<String>): File

    fun signFile(filename: String, metadata: Map<String, String>): File
}