package com.koupper.providers.files

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class FileHandlerTest : AnnotationSpec() {
    @Test
    fun `should read from url`() {
        val fileHandler = FileHandlerImpl()

        val file = fileHandler.readFileFromUrl("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            file.exists()
            file.delete()
        }
    }

    @Test
    fun `should read from resource`() {
        val fileHandler = FileHandlerImpl()

        assertTrue {
            fileHandler.readFileFromResource(".env").exists()
        }
    }

    @Test
    fun `should unzip file from path`() {
        val fileHandler = FileHandlerImpl()

        fileHandler.unzipFileFromResource("zipFolder.zip", emptyList())

        val listOfZippedFiles = listOf("subdirectory1", "subfile1.txt", "file1.txt")

        val unzipContent = emptyList<String>().toMutableList()

        fileHandler.readFileFromResource("zipFolder").walk().forEach {
            unzipContent += it.name
        }

        assertTrue {
            unzipContent.containsAll(listOfZippedFiles)
        }
    }

    @Test
    fun `should unzip file from url`() {
        val fileHandler = FileHandlerImpl()

        val unzippedFile = fileHandler.unzipFileFromUrl("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            unzippedFile.exists()
            unzippedFile.delete()
        }
    }

    @Test
    fun `should zip file from url`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFileFromUrl("https://lib-installer.s3.amazonaws.com/koupper.txt")

        assertTrue {
            zippedFile.exists()
            listContentOfZippedFile(zippedFile.path).contains("koupper.txt")
            zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from resource`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFileFromResource("unzippedFolder")

        assertTrue {
            zippedFile.exists()
            zippedFile.name == "unzippedFolder.zip"
        }

        val listOfZippedFiles = listOf("subfolder/", "subfolder2/", "hello.txt", "subfolder/hello2.txt", "subfolder2/hello3.txt")

        assertTrue {
            listContentOfZippedFile(zippedFile.path).containsAll(listOfZippedFiles)
            zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from resource ignoring files`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFileFromResource("unzippedFolder", filesToIgnore = listOf("hello.txt", "subfolder2/hello3.txt"))

        assertTrue {
            zippedFile.exists()
            zippedFile.name == "unzippedFolder.zip"
        }

        val listOfZippedFiles = listOf("subfolder/", "subfolder2/", "subfolder/hello2.txt")

        val contentOfZippedFile = listContentOfZippedFile(zippedFile.path)

        assertTrue {
            contentOfZippedFile.containsAll(listOfZippedFiles)
            !contentOfZippedFile.contains("hello.txt")
            !contentOfZippedFile.contains("subfolder2/hello3.txt")
            !contentOfZippedFile.contains("subfolder2/")
            zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from path`() {
        val fileHandler = FileHandlerImpl()

        val zippedFile = fileHandler.zipFileFromPath("/Users/jacobacosta/Code/front-module", filesToIgnore = listOf(".idea", "README.md", ".git", ".DS_Store", "front-module"))

        assertTrue {
            zippedFile.exists()
            zippedFile.name == "front-module.zip"
        }

        val contentOfZippedFile = listContentOfZippedFile(zippedFile.path)

        // this should be updated to check subfolders
        assertTrue {
            !contentOfZippedFile.contains("README.md")
            !contentOfZippedFile.contains(".idea")
            !contentOfZippedFile.contains(".git")
            !contentOfZippedFile.contains(".DS_Store")
            zippedFile.delete()
        }
    }
}