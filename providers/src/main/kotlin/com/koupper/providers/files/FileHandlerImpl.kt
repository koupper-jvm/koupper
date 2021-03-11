package com.koupper.providers.files

import java.io.File
import java.net.URL

val sanitizeTargetLocation: (String, String) -> String = { downloadPath, fileName ->
    if (downloadPath.isEmpty()) {
        if (fileName.contains(".")) {
            "${fileName.substring(0, fileName.indexOf("."))}.zip"
        } else {
            "${fileName}.zip"
        }
    } else {
        if (fileName.contains(".")) {
            "$downloadPath/${fileName.substring(0, fileName.indexOf("."))}.zip"
        } else {
            "$downloadPath/${fileName}.zip"
        }
    }
}

class FileHandlerImpl : FileHandler {
    var fileName: String = ""

    override fun readFileFromPath(filePath: String): File {
        return File(filePath)
    }

    override fun readFileFromUrl(fileUrl: String, downloadPath: String): File {
        this.fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1)

        return if (downloadPath.isEmpty()) {
            downloadFile(URL(fileUrl), this.fileName)
        } else {
            downloadFile(URL(fileUrl), "$downloadPath/${this.fileName}")
        }
    }

    override fun readFileFromResource(filePath: String): File {
        return File(FileHandlerImpl::class.java.classLoader.getResource(filePath).path)
    }

    override fun zipFileFromPath(filePath: String, downloadPath: String, filesToIgnore: List<String>): File {
        val file = this.readFileFromPath(filePath)

        val targetLocation = sanitizeTargetLocation(downloadPath, file.name)

        val zippedFile = zipFile(file.path, targetLocation, filesToIgnore)

        file.delete()

        return zippedFile
    }

    override fun zipFileFromUrl(fileUrl: String, downloadPath: String, filesToIgnore: List<String>): File {
        val file = this.readFileFromUrl(fileUrl)

        val targetLocation = sanitizeTargetLocation(downloadPath, file.name)

        val zippedFile = zipFile(file.name, targetLocation, filesToIgnore)

        file.delete()

        return zippedFile
    }

    override fun zipFileFromResource(fileName: String, downloadPath: String, filesToIgnore: List<String>): File {
        val file = this.readFileFromResource(fileName)

        val targetLocation = sanitizeTargetLocation(downloadPath, file.name)

        val zippedFile = zipFile(file.path, targetLocation, filesToIgnore)

        file.delete()

        return zippedFile
    }

    override fun unzipFileFromPath(zipPath: String): File {
        TODO("Not yet implemented")
    }

    override fun unzipFileFromUrl(zipUrl: String, downloadPath: String): File {
        val zipFile = this.readFileFromUrl(zipUrl)

        return unzipFile(zipFile.path, emptyList(), downloadPath)
    }

    override fun unzipFileFromResource(zipName: String, ignoring: List<String>): File {
        val zipFile = this.readFileFromResource(zipName)

        return unzipFile(zipFile.path, ignoring, zipFile.path.substring(0, zipFile.path.lastIndexOf("/")))
    }

    override fun signFile(filename: String, metadata: Map<String, String>): File {
        TODO("Not yet implemented")
    }
}