package com.koupper.providers.rss

data class RSSItem(
    val title: String,
    val link: String,
    val html: String,
    val pubDate: Long?,
    val source: String
)
