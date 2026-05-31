package com.koupper.providers.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.net.URL

class PDFBoxReaderProvider : PDFReaderProvider {

    private fun load(source: String): PDDocument =
        if (source.startsWith("http://") || source.startsWith("https://"))
            PDDocument.load(URL(source).openStream())
        else
            PDDocument.load(File(source))

    override fun extractText(source: String): String =
        load(source).use { PDFTextStripper().getText(it) }

    override fun extractPages(source: String): List<String> =
        load(source).use { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage   = page
                stripper.getText(doc)
            }
        }

    override fun metadata(source: String): PDFMetadata =
        load(source).use { doc ->
            val info = doc.documentInformation
            PDFMetadata(
                title        = info?.title        ?: "",
                author       = info?.author       ?: "",
                subject      = info?.subject      ?: "",
                pageCount    = doc.numberOfPages,
                creationDate = info?.creationDate?.time?.toString() ?: ""
            )
        }

    override fun read(source: String): PDFDocument =
        load(source).use { doc ->
            val stripper  = PDFTextStripper()
            val fullText  = stripper.getText(doc)
            val pages     = (1..doc.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage   = page
                stripper.getText(doc)
            }
            val info = doc.documentInformation
            PDFDocument(
                source   = source,
                metadata = PDFMetadata(
                    title        = info?.title        ?: "",
                    author       = info?.author       ?: "",
                    subject      = info?.subject      ?: "",
                    pageCount    = doc.numberOfPages,
                    creationDate = info?.creationDate?.time?.toString() ?: ""
                ),
                text  = fullText,
                pages = pages
            )
        }
}
