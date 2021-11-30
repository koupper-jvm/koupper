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
        path.startsWith("/") -> PathType.LOCATION
        else -> PathType.MALFORMED
    }
}

val buildResourcePathName: (String) -> String = {
    // this should be support nested paths
    it.substring(it.lastIndexOf("/") + 1)
}

class FileHandlerImpl : FileHandler {
    override fun load(filePath: String, targetPath: String): File {
        return when {
            checkByPathType(filePath) === PathType.URL -> downloadFile(
                URL(filePath),
                filePath.substring(filePath.lastIndexOf("/") + 1)
            )
            checkByPathType(filePath) === PathType.RESOURCE -> File(
                FileHandlerImpl::class.java.classLoader.getResource(
                    buildResourcePathName(filePath)
                ).path
            )
            checkByPathType(filePath) === PathType.ENV -> File(
                ".${filePath.substring(filePath.lastIndexOf("/") + 1)}"
            )
            checkByPathType(filePath) === PathType.LOCATION -> File(filePath) // Download the file in the current location.
            else -> File(filePath)
        }
    }

    override fun zipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        val inputDirectory = this.load(filePath)

        val outputZipFile = File("${inputDirectory.name}.zip")

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

                if (
                    entry.isDirectory &&
                    entry.name.substring(0, entry.name.indexOf("/")) == outputZipFile.name.substring(
                        0,
                        outputZipFile.name.indexOf(".")
                    )
                ) {
                    return@lit
                }

                zos.putNextEntry(entry)

                if (file.isFile) {
                    file.inputStream().copyTo(zos)
                }
            }
        }

        return outputZipFile
    }

    override fun unzipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        var unzippedFolderName: String? = null

        val zipFile = this.load(filePath)

        launchProcess {
            ZipFile(zipFile.absolutePath).use { zip ->
                unzippedFolderName = zipFile.absolutePath.slice(
                    zipFile.absolutePath.lastIndexOf("/") + 1 until zipFile.absolutePath.indexOf(".")
                )

                zip.entries().asSequence().forEach lit@{ entry ->
                    filesToIgnore.forEach filesToIgnore@{
                        if (it.contains(entry.name)) return@filesToIgnore
                    }

                    var directoryLocation = unzippedFolderName

                    directoryLocation = if (directoryLocation!!.isNotEmpty()) {
                        val location = File(directoryLocation)

                        if (!location.exists()) {
                            location.mkdir()
                        }

                        "${location.path}/${entry.name}"
                    } else {
                        entry.name
                    }

                    if (entry.isDirectory) {
                        File(directoryLocation).mkdir()
                    } else {
                        zip.getInputStream(entry).use { input ->
                            File(directoryLocation).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        }.join()

        return File(unzippedFolderName)
    }

    override fun signFile(filePath: String, metadata: Map<String, String>): File {
        return File("")
    }
}
