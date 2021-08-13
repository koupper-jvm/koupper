package com.koupper.providers.files

import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import kotlin.test.assertTrue

class FileHandlerTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should load from url`() {
        val fileHandler = FileHandlerImpl()

        val loadedFile = fileHandler.load("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            loadedFile.exists()
        }

        assertTrue {
            loadedFile.delete()
        }
    }

    @Test
    fun `should load from resource`() {
        val fileHandler = FileHandlerImpl()

        val loadedFile = fileHandler.load("resource://.env_notifications.example")

        assertTrue {
            loadedFile.exists()
        }
    }

    @Test
    fun `should unzip file from path`() {
        val fileHandler = FileHandlerImpl()

        val unzippedFile = fileHandler.unzipFile("resource://zipFolder.zip", filesToIgnore = emptyList())

        val listOfZippedFiles = listOf("subdirectory1", "subfile1.txt", "file1.txt")

        val unzipContent = emptyList<String>().toMutableList()

        unzippedFile.walk().forEach {
            unzipContent += it.name
        }

        assertTrue {
            unzipContent.containsAll(listOfZippedFiles)
        }

        assertTrue {
            unzippedFile.deleteRecursively()
        }

        assertTrue {
            !unzippedFile.exists()
        }
    }

    @Ignore
    @Test
    fun `should unzip file from url`() {
        val fileHandler = FileHandlerImpl()

        val unzippedFile = fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            File("${unzippedFile.absolutePath}.zip").delete()
        }

        assertTrue {
            unzippedFile.exists()
        }

        assertTrue {
            unzippedFile.deleteRecursively()
        }

        assertTrue {
            !unzippedFile.exists()
        }
    }

    @Ignore
    @Test
    fun `should unzip file from url specifying a target name`() {
        val fileHandler = FileHandlerImpl()

        val unzippedFile = fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            File("${unzippedFile.absolutePath}.zip").delete()
        }

        assertTrue {
            unzippedFile.exists()
        }

        assertTrue {
            unzippedFile.deleteRecursively()
        }

        assertTrue {
            !unzippedFile.exists()
        }
    }

    @Test
    fun `should zip file from url`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFile("https://lib-installer.s3.amazonaws.com/koupper.txt")

        assertTrue {
            File("koupper.txt").delete()
        }

        assertTrue {
            zippedFile.exists()
        }

        assertTrue {
            listContentOfZippedFile(zippedFile.path).contains("koupper.txt")
        }

        assertTrue {
            zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from resource`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFile("resource://unzippedFolder")

        assertTrue {
            zippedFile.exists()
        }

        assertTrue {
            zippedFile.name == "unzippedFolder.zip"
        }

        val listOfZippedFiles = listOf("subfolder/", "subfolder2/", "hello.txt", "subfolder/hello2.txt", "subfolder2/hello3.txt")

        assertTrue {
            listContentOfZippedFile(zippedFile.path).containsAll(listOfZippedFiles)
        }

        assertTrue {
            zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from resource ignoring files`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFile("resource://unzippedFolder", filesToIgnore = listOf("hello.txt", "subfolder2/hello3.txt"))

        assertTrue { zippedFile.exists() }

        assertTrue { zippedFile.name == "unzippedFolder.zip" }

        val listOfZippedFiles = listOf("subfolder/", "subfolder2/", "subfolder/hello2.txt")

        val contentOfZippedFile = listContentOfZippedFile(zippedFile.path)

        assertTrue { contentOfZippedFile.containsAll(listOfZippedFiles) }
        assertTrue { !contentOfZippedFile.contains("hello.txt") }
        assertTrue { !contentOfZippedFile.contains("subfolder2/hello3.txt") }
        assertTrue { contentOfZippedFile.contains("subfolder2/") }
        assertTrue { zippedFile.delete() }
    }

    @Test
    fun `should zip file from path`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFile("/Users/jacobacosta/Code/front-module", filesToIgnore = listOf(".idea", "README.md", ".git", ".DS_Store", "front-module"))

        assertTrue {
            zippedFile.exists()
        }

        assertTrue {
            zippedFile.name == "front-module.zip"
        }

        val contentOfZippedFile = listContentOfZippedFile(zippedFile.path)

        // this should be updated to check subfolders
        assertTrue {
            !contentOfZippedFile.contains("README.md") &&
                    !contentOfZippedFile.contains(".idea") &&
                    !contentOfZippedFile.contains(".git") &&
                    !contentOfZippedFile.contains(".DS_Store")
        }

        assertTrue {
            zippedFile.delete()
        }
    }
}