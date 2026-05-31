package com.koupper.providers.pdf

data class PDFMetadata(
    val title: String,
    val author: String,
    val subject: String,
    val pageCount: Int,
    val creationDate: String
)

data class PDFDocument(
    val source: String,
    val metadata: PDFMetadata,
    val text: String,
    val pages: List<String>
)
