package com.koupper.providers.pdf

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.nio.file.Files

class PDFBoxReaderProviderTest : StringSpec({

    val sut = PDFBoxReaderProvider()

    fun buildPdf(
        title: String = "",
        author: String = "",
        subject: String = "",
        vararg pageTexts: String
    ): File {
        val file = Files.createTempFile("koupper-pdf-test-", ".pdf").toFile()
        PDDocument().use { doc ->
            pageTexts.forEach { text ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            if (pageTexts.isEmpty()) doc.addPage(PDPage())
            doc.documentInformation.also { info ->
                if (title.isNotBlank())   info.title   = title
                if (author.isNotBlank())  info.author  = author
                if (subject.isNotBlank()) info.subject = subject
            }
            doc.save(file)
        }
        return file
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    "extractText should return full text content" {
        val file = buildPdf(pageTexts = arrayOf("Hello Koupper"))
        try {
            sut.extractText(file.absolutePath) shouldContain "Hello Koupper"
        } finally { file.delete() }
    }

    "metadata should return title, author, subject and pageCount" {
        val file = buildPdf(title = "My Title", author = "Jane Doe", subject = "Testing")
        try {
            val meta = sut.metadata(file.absolutePath)
            meta.title     shouldBe "My Title"
            meta.author    shouldBe "Jane Doe"
            meta.subject   shouldBe "Testing"
            meta.pageCount shouldBe 1
        } finally { file.delete() }
    }

    "extractPages should split content by page" {
        val file = buildPdf(pageTexts = arrayOf("Page one content", "Page two content"))
        try {
            val pages = sut.extractPages(file.absolutePath)
            pages shouldHaveSize 2
            pages[0] shouldContain "Page one content"
            pages[1] shouldContain "Page two content"
        } finally { file.delete() }
    }

    "read should combine metadata, full text and per-page list" {
        val file = buildPdf(title = "Full Doc", author = "Author", pageTexts = arrayOf("Combined text"))
        try {
            val doc = sut.read(file.absolutePath)
            doc.source            shouldBe file.absolutePath
            doc.metadata.title    shouldBe "Full Doc"
            doc.metadata.author   shouldBe "Author"
            doc.metadata.pageCount shouldBe 1
            doc.text              shouldContain "Combined text"
            doc.pages             shouldHaveSize 1
            doc.pages[0]          shouldContain "Combined text"
        } finally { file.delete() }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    "metadata should return empty strings when PDF has no metadata" {
        val file = buildPdf()
        try {
            val meta = sut.metadata(file.absolutePath)
            meta.title   shouldBe ""
            meta.author  shouldBe ""
            meta.subject shouldBe ""
            meta.pageCount shouldBe 1
        } finally { file.delete() }
    }

    "extractText on empty-page PDF should return blank or whitespace" {
        val file = buildPdf()
        try {
            val text = sut.extractText(file.absolutePath).trim()
            text shouldBe ""
        } finally { file.delete() }
    }

    "extractPages on multi-page PDF should return correct count" {
        val file = buildPdf(pageTexts = arrayOf("A", "B", "C"))
        try {
            sut.extractPages(file.absolutePath) shouldHaveSize 3
        } finally { file.delete() }
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    "extractText should throw when file does not exist" {
        shouldThrowAny {
            sut.extractText("/nonexistent/path/to/missing.pdf")
        }
    }

    "metadata should throw when file does not exist" {
        shouldThrowAny {
            sut.metadata("/nonexistent/path/to/missing.pdf")
        }
    }
})
