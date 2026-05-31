package com.koupper.providers.pdf

interface PDFReaderProvider {
    /** Extracts all text from a local file path or HTTP/HTTPS URL. */
    fun extractText(source: String): String

    /** Returns text split by page. */
    fun extractPages(source: String): List<String>

    /** Returns document metadata (title, author, page count, etc). */
    fun metadata(source: String): PDFMetadata

    /** Full document: metadata + full text + per-page text. */
    fun read(source: String): PDFDocument
}
