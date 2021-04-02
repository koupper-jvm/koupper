package com.koupper.providers.files

import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFileHandlerTest : AnnotationSpec() {
    @Test
    fun `should load from url`() {
        val fileHandler = TextFileHandlerImpl()

        fileHandler.load("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            File("model-project.zip").exists()
        }

        assertTrue {
            File("model-project.zip").delete()
        }
    }

    @Test
    fun `should load from resource`() {
        val fileHandler = TextFileHandlerImpl()

        assertTrue {
            fileHandler.load("resource://.env_notifications.example").exists()
        }
    }

    @Test
    fun `should unzip file from path`() {
        val fileHandler = TextFileHandlerImpl()

        val unzipFile = fileHandler.unzipFile("/Users/jacobacosta/Code/koupper/providers/src/test/resources/zipFolder.zip", filesToIgnore = emptyList())

        val listOfZippedFiles = listOf("subdirectory1", "subfile1.txt", "file1.txt")

        val unzipContent = emptyList<String>().toMutableList()

        fileHandler.load("zipFolder").walk().forEach {
            unzipContent += it.name
        }

        assertTrue {
            unzipContent.containsAll(listOfZippedFiles) &&
                    unzipFile.deleteRecursively() &&
                    File("zipFolder").deleteRecursively()
        }
    }

    @Test
    fun `should unzip file from url`() {
        val fileHandler = TextFileHandlerImpl()

        val unzippedFile = fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/model-project.zip")

        assertTrue {
            unzippedFile.exists() &&
                    unzippedFile.deleteRecursively() &&
                    File("model-project.zip").delete()
        }
    }

    @Test
    fun `should zip file from url`() {
        val fileHandler = TextFileHandlerImpl()

        val zippedFile = fileHandler.zipFile("https://lib-installer.s3.amazonaws.com/koupper.txt")

        assertTrue {
            zippedFile.exists() &&
                    listContentOfZippedFile(zippedFile.path).contains("koupper.txt") &&
                    zippedFile.delete()
        }
    }

    @Test
    fun `should zip file from resource`() {
        val fileHandler = TextFileHandlerImpl()

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
        val fileHandler = TextFileHandlerImpl()

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
        val fileHandler = TextFileHandlerImpl()

        val zippedFile = fileHandler.zipFile("/Users/jacobacosta/Code/front-module", filesToIgnore = listOf(".idea", "README.md", ".git", ".DS_Store", "front-module"))

        assertTrue {
            zippedFile.exists() &&
                    zippedFile.name == "front-module.zip"
        }

        val contentOfZippedFile = listContentOfZippedFile(zippedFile.path)

        // this should be updated to check subfolders
        assertTrue {
            !contentOfZippedFile.contains("README.md") &&
                    !contentOfZippedFile.contains(".idea") &&
                    !contentOfZippedFile.contains(".git") &&
                    !contentOfZippedFile.contains(".DS_Store") &&
                    zippedFile.delete()
        }
    }

    @Test
    fun `should get the number line for specific content`() {
        val fileHandler = TextFileHandlerImpl()

        val numberOfLine = fileHandler.getNumberLineFor(
                "<link rel=\"stylesheet\" href=\"css/styles.css?v=1.0\">",
                "resource://index.html"
        )


        assertTrue {
            numberOfLine > 0
        }

        assertTrue {
            numberOfLine.compareTo(11) == 0 // this is the line for the targeting content in the specified file.
        }
    }

    @Test
    fun `should get the number lines for specific content`() {
        val fileHandler = TextFileHandlerImpl()

        val numberOfLines = fileHandler.getNumberLinesFor(
                "<meta",
                "resource://index.html"
        )

        assertTrue { numberOfLines.containsAll(listOf(5, 8, 9)) }
    }

    @Test
    fun `should put a line before to specific line`() {
        val fileHandler = TextFileHandlerImpl()

        val file = fileHandler.putLineBefore(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(1) == 0 && file.delete()
        }
    }

    @Test
    fun `should put a line after to specific line`() {
        val fileHandler = TextFileHandlerImpl()

        val file = fileHandler.putLineAfter(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(2) == 0 && file.delete()
        }
    }

    @Test
    fun `should append content before other specified content`() {
        val fileHandler = TextFileHandlerImpl()

        val file = fileHandler.appendContentBefore(">This", contentToAdd = " class=\"font-weight-bold\"", filePath = "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<span class=\"font-weight-bold\">This", file.path).compareTo(14) == 0 &&
                    file.delete()
        }
    }

    @Test
    fun `should append content after other specified content`() {
        val fileHandler = TextFileHandlerImpl()

        val file = fileHandler.appendContentAfter(">This", contentToAdd = " example", filePath = "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("pan>This example is for a ko", file.path).compareTo(14) == 0 &&
                    file.delete()
        }
    }

    @Test
    fun `should replace line in specific number`() {
        val fileHandler = TextFileHandlerImpl()

        val file = fileHandler.replaceLine(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(1) == 0
        }
    }

    @Test
    fun `should get the content for specified line`() {
        val fileHandler = TextFileHandlerImpl()

        val content = fileHandler.getContentForLine(1, "resource://index.html")

        assertEquals(content, "<!doctype html>")
    }

    @Test
    fun `should get the content between lines`() {
        val fileHandler = TextFileHandlerImpl()

        val content = fileHandler.getContentBetweenLines(1, 3, "resource://index.html")

        assertTrue {
            content.isNotEmpty()
        }

        assertTrue {
            content[0].isEmpty()
        }
    }

    @Test
    fun `should get the content between either content in different line`() {
        val fileHandler = TextFileHandlerImpl()

        val content = fileHandler.getContentBetweenContent("<span>", "</span>", 3, "resource://index.html")

        assertEquals("Here go other text", content[0].trim())
    }

    @Test
    fun `should get the content between either content in the same line`() {
        val fileHandler = TextFileHandlerImpl()

        val content = fileHandler.getContentBetweenContent("This is", "koupper test.", 1, "resource://index.html")

        assertEquals("for a", content[0].trim())
    }
}
