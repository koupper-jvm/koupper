package com.koupper.providers.rss

interface RSSReader {
    fun read(url: String): List<RSSItem>
}
