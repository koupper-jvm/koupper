package com.koupper.providers.files

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

val downloadFile: (url: URL, targetFileName: String) -> File = { url, targetFileName ->
    url.openStream().use { `in` -> Files.copy(`in`, Paths.get(targetFileName)) }

    File(targetFileName)
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
    fun load(filePath: String): File

    fun zipFile(filePath: String = "", targetPath: String = "N/A", filesToIgnore: List<String> = emptyList()): File

    fun unzipFile(filePath: String, targetPath: String = "N/A", filesToIgnore: List<String> = emptyList()): File

    fun signFile(filePath: String, metadata: Map<String, String>): File
}
