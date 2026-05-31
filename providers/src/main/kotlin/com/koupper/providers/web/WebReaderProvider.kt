package com.koupper.providers.web

interface WebReaderProvider {
    fun fetch(url: String): WebPage
    fun fetchText(url: String): String
    fun screenshot(url: String): ByteArray
}
