package com.koupper.providers.files

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.core.test.TestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFileHandlerTest : AnnotationSpec() {
    private lateinit var container: Container

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        this.container = app
    }

    @Test
    fun `should get the number line for specific content`() {
        val fileHandler = TextFileHandlerImpl(this.container)

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
        val fileHandler = TextFileHandlerImpl(this.container)

        val numberOfLines = fileHandler.getNumberLinesFor(
                "<meta",
                "resource://index.html"
        )

        assertTrue { numberOfLines.containsAll(listOf(5, 8, 9)) }
    }

    @Test
    fun `should put a line before to specific line`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val file = fileHandler.putLineBefore(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(1) == 0 && file.delete()
        }
    }

    @Test
    fun `should put a line after to specific line`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val file = fileHandler.putLineAfter(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(2) == 0 && file.delete()
        }
    }

    @Test
    fun `should append content before other specified content`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val file = fileHandler.appendContentBefore(">This", contentToAdd = " class=\"font-weight-bold\"", filePath = "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<span class=\"font-weight-bold\">This", file.path).compareTo(14) == 0
        }
    }

    @Test
    fun `should append content after other specified content`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val file = fileHandler.appendContentAfter(">This", contentToAdd = " example", filePath = "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("pan>This example is for a ko", file.path).compareTo(14) == 0
        }
    }

    @Test
    fun `should replace line in specific number`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val file = fileHandler.replaceLine(1, "<!--this is a comment-->", "resource://index.html")

        assertTrue {
            fileHandler.getNumberLineFor("<!--this is a comment-->", file.path).compareTo(1) == 0
        }
    }

    @Test
    fun `should get the content for specified line`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val content = fileHandler.getContentForLine(1, "resource://index.html")

        assertEquals(content, "<!doctype html>")
    }

    @Test
    fun `should get the content between lines`() {
        val fileHandler = TextFileHandlerImpl(this.container)

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
        val fileHandler = TextFileHandlerImpl(this.container)

        val content = fileHandler.getContentBetweenContent("<span>", "</span>", 3, "resource://index.html")

        assertEquals("Here go other text", content[0].trim())
    }

    @Test
    fun `should get the content between either content in the same line`() {
        val fileHandler = TextFileHandlerImpl(this.container)

        val content = fileHandler.getContentBetweenContent("This is", "koupper test.", 1, "resource://index.html")

        assertEquals("for a", content[0].trim())
    }
}
