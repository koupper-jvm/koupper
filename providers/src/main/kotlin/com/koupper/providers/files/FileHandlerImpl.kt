package com.koupper.providers.files

import com.koupper.providers.launchProcess
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class PathType {
    URL,
    RESOURCE,
    LOCATION,
    ENV,
    MALFORMED,
}

val checkByPathType: (String) -> PathType = { path ->
    when {
        path.contains("(http|https)://".toRegex()) -> PathType.URL
        path.contains("resource://".toRegex()) -> PathType.RESOURCE
        path.contains("env:".toRegex()) -> PathType.ENV
        path.startsWith(File.separator) -> PathType.LOCATION  // Usa File.separator
        else -> PathType.MALFORMED
    }
}

val buildResourcePathName: (String) -> String = {
    // Este método debe soportar rutas anidadas
    it.substring(it.lastIndexOf("/") + 1)  // Usa File.separator
}

class FileHandlerImpl : FileHandler {
    override fun load(filePath: String): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> downloadFile(
                URL(filePath),
                filePath.substring(filePath.lastIndexOf("/") + 1)  // Usa File.separator
            )
            checkByPathType(filePath) === PathType.RESOURCE -> File(
                FileHandlerImpl::class.java.classLoader.getResource(
                    buildResourcePathName(filePath)
                ).path
            )
            checkByPathType(filePath) === PathType.ENV -> File(
                ".${filePath.substring(filePath.lastIndexOf(File.separator) + 1)}"  // Usa File.separator
            )
            checkByPathType(filePath) === PathType.LOCATION -> File(filePath)  // Carga en la ubicación actual
            else -> File(filePath)
        }
    }

    override fun zipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        val inputDirectory = this.load(filePath)

        val outputZipFile = if (targetPath == "N/A") {
            val fileName = "${inputDirectory.name}.zip"
            val finalFile = File(fileName)
            if (finalFile.exists()) return finalFile
            finalFile
        } else {
            File(targetPath, "${inputDirectory.name}.zip")
        }

        val ignoreSet = filesToIgnore.toSet()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
            inputDirectory.walkTopDown().forEach { file ->
                val zipFileName = file.relativeTo(inputDirectory).path.replace("\\", "/")

                val isRootFileToIgnore = ignoreSet.contains(zipFileName)
                val isInIgnoredDirectory = ignoreSet.any { ignorePath -> zipFileName.startsWith("$ignorePath/") }

                if (isRootFileToIgnore || isInIgnoredDirectory) return@forEach

                if (file == inputDirectory) return@forEach

                val entry = ZipEntry(zipFileName + if (file.isDirectory) "/" else "")
                zos.putNextEntry(entry)

                if (file.isFile) {
                    file.inputStream().use { input -> input.copyTo(zos) }
                }

                zos.closeEntry()
            }
        }

        return outputZipFile
    }

    override fun unzipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        val normalizedFilePath = filePath.replace("\\", "/")
        val ignoreSet = filesToIgnore.map { it.replace("\\", "/") }.toSet()

        val zipFile = this.load(normalizedFilePath)

        val unzippedFolderName = if (targetPath == "N/A") {
            normalizedFilePath.substring(normalizedFilePath.lastIndexOf("/") + 1, normalizedFilePath.lastIndexOf('.'))
        } else {
            targetPath
        }

        val targetDirectory = File(unzippedFolderName)
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        ZipFile(zipFile.absolutePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryPath = entry.name.replace("\\", "/")

                val isIgnoredInRoot = entryPath == filesToIgnore.find { it == entryPath }
                val isIgnoredInSubDir = ignoreSet.any { ignoreItem -> entryPath.startsWith("$ignoreItem/") }

                if (isIgnoredInRoot || isIgnoredInSubDir) return@forEach

                val entryDestination = File(targetDirectory, entryPath)

                if (entry.isDirectory) {
                    entryDestination.mkdirs()
                } else {
                    entryDestination.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryDestination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }

        return targetDirectory
    }

    override fun signFile(filePath: String, metadata: Map<String, String>): File {
        return File("")
    }
}
