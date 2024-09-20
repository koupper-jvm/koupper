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
                filePath.substring(filePath.lastIndexOf(File.separator) + 1)  // Usa File.separator
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
        val outputZipFile = File("${inputDirectory.name}.zip")

        ZipOutputStream(
            BufferedOutputStream(
                FileOutputStream(outputZipFile)
            )
        ).use { zos ->
            inputDirectory.walkTopDown().forEach lit@{ file ->
                // Obtener la ruta relativa al directorio de entrada
                var zipFileName = file.relativeTo(inputDirectory).path

                // Si la ruta es vacía, usa el nombre del archivo o directorio
                if (zipFileName.isEmpty()) {
                    zipFileName = file.name
                }

                // Ignorar archivos que están en filesToIgnore
                filesToIgnore.forEach { pattern ->
                    // Normalizar el patrón de ignorar y zipFileName
                    val normalizedPattern = pattern.replace("\\", "/")
                    val normalizedZipFileName = zipFileName.replace("\\", "/")

                    // Usar una expresión regular más clara
                    if (normalizedZipFileName.contains("$normalizedPattern(/.*)?$".toRegex())) {
                        println("Ignorando archivo: $normalizedZipFileName")
                        return@lit
                    }
                }

                // Evitar agregar el directorio raíz como una entrada
                if (file == inputDirectory) {
                    return@lit
                }

                // Crear entrada ZIP
                val entry = ZipEntry("$zipFileName${if (file.isDirectory) File.separator else ""}")

                zos.putNextEntry(entry)

                // Si es un archivo, copiar el contenido
                if (file.isFile) {
                    file.inputStream().use { input ->
                        input.copyTo(zos)
                    }
                }
                zos.closeEntry()
            }
        }

        return outputZipFile
    }

    override fun unzipFile(filePath: String, targetPath: String, filesToIgnore: List<String>): File {
        if (targetPath == "N/A") {
            val fileName = filePath.substring(filePath.lastIndexOf(File.separator) + 1, filePath.lastIndexOf('.'))  // Usa File.separator

            val finalFile = File(fileName)

            if (finalFile.exists()) {
                println("Una carpeta con nombre similar ya existe en la ruta actual.")

                return finalFile
            }
        }

        var unzippedFolderName: String? = null

        val zipFile = this.load(filePath)

        launchProcess {
            ZipFile(zipFile.absolutePath).use { zip ->
                unzippedFolderName = zipFile.absolutePath.slice(
                    zipFile.absolutePath.lastIndexOf(File.separator) + 1 until zipFile.absolutePath.indexOf(".")  // Usa File.separator
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

                        "${location.path}${File.separator}${entry.name}"  // Usa File.separator
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
