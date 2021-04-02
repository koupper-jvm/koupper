package com.koupper.providers.files

import java.io.File
import java.io.FileWriter
import java.lang.StringBuilder
import java.net.URL

enum class PathType {
    URL,
    RESOURCE,
    LOCATION
}

val buildFinalTargetPath: (String, String) -> String = { targetPath, fileName ->
    if (targetPath === "N/A") {
        if (fileName.contains(".")) {
            "${fileName.substring(0, fileName.indexOf("."))}.zip"
        } else {
            "${fileName}.zip"
        }
    } else {
        if (fileName.contains(".")) {
            "$targetPath/${fileName.substring(0, fileName.indexOf("."))}.zip"
        } else {
            "$targetPath/${fileName}.zip"
        }
    }
}

val checkByPathType: (String) -> PathType = { path ->
    when {
        path.contains("(http|https)://".toRegex()) -> PathType.URL
        path.contains("resource://".toRegex()) -> PathType.RESOURCE
        else -> PathType.LOCATION
    }
}

val buildResourcePathName: (String) -> String = {
    it.substring(it.lastIndexOf("/") + 1)
}

class FileHandlerImpl : FileHandler {
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
        return File(FileHandlerImpl::class.java.classLoader.getResource(buildResourcePathName(filePath)).path)
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
}

