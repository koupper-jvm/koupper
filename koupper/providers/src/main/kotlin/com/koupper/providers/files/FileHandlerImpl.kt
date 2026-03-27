package com.koupper.providers.files

import com.koupper.container.context
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
        path.startsWith(File.separator) -> PathType.LOCATION
        else -> PathType.MALFORMED
    }
}

val buildResourcePathName: (String) -> String = {
    // Este método debe soportar rutas anidadas
    it.substring(it.lastIndexOf("/") + 1)
}

class FileHandlerImpl : FileHandler {
    override fun load(filePath: String): File {

        val pathType = checkByPathType(filePath)

        return when (pathType) {

            PathType.URL -> downloadFile(
                URL(filePath),
                filePath.substring(filePath.lastIndexOf("/") + 1)
            )

            PathType.RESOURCE -> File(
                FileHandlerImpl::class.java.classLoader
                    .getResource(buildResourcePathName(filePath))
                    ?.path ?: throw IllegalArgumentException("Resource not found: $filePath")
            )

            PathType.ENV -> File(
                ".${filePath.substring(filePath.lastIndexOf(File.separator) + 1)}"
            )

            PathType.LOCATION -> File(filePath)

            else -> File(filePath)
        }
    }

    override fun zipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        val inputDirectory = this.load(filePath)

        val outputZipFile = if (targetPath == "N/A") {
            val fileName = "${inputDirectory.name}.zip"
            val finalFile = File(context + File.separator + fileName)
            if (finalFile.exists()) return finalFile
            finalFile
        } else {
            File(context + File.separator + targetPath, "${inputDirectory.name}.zip")
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
        val zipFile = this.load(filePath)

        // Determinar carpeta destino
        val targetDirectory = if (targetPath == "N/A") {
            // Usar el nombre del zip sin extensión como carpeta
            val baseName = zipFile.nameWithoutExtension
            File(context, baseName)
        } else {
            File(context, targetPath)
        }

        // Crear directorio destino si no existe
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        // Normalizar rutas a ignorar
        val ignoreSet = filesToIgnore.map { it.replace("\\", "/") }.toSet()

        ZipFile(zipFile.absolutePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryName = entry.name
                val entryPath = entryName.replace("\\", "/")

                // Verificar si debe ignorarse
                val shouldIgnore = ignoreSet.any { ignoreItem ->
                    entryPath == ignoreItem ||
                            entryPath.startsWith("$ignoreItem/") ||
                            (ignoreItem.endsWith("/") && entryPath.startsWith(ignoreItem))
                }

                if (shouldIgnore) {
                    return@forEach
                }

                // Construir path destino
                val entryDestination = File(targetDirectory, entryPath)

                // Security check
                val canonicalDest = entryDestination.canonicalFile
                if (!canonicalDest.path.startsWith(targetDirectory.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry is outside target dir: $entryPath")
                }

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

        // Opcional: eliminar el zip después de extraer
        // if (!zipFile.delete()) {
        //     println("Warning: Could not delete $filePath")
        // }

        return targetDirectory
    }

    override fun signFile(filePath: String, metadata: Map<String, String>): File {
        return File("")
    }
}
