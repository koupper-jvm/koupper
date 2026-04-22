package com.koupper.providers.rss

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RSSReaderImpl : RSSReader {

    override fun read(url: String): List<RSSItem> {
        val results = mutableListOf<RSSItem>()

        try {
            val xml = URL(url).readText()
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = doc.select("item")

            for (item in items) {
                val title = item.selectFirst("title")?.text().orEmpty()
                val link = item.selectFirst("link")?.text().orEmpty()
                val html = item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text().orEmpty()
                val pubDateTxt = item.selectFirst("pubDate")?.text()
                val pubDate = pubDateTxt?.let { parseRssDate(it) }

                results.add(
                    RSSItem(
                        title = title,
                        link = link,
                        html = html,
                        pubDate = pubDate,
                        source = url
                    )
                )
            }
        } catch (e: Exception) {
            println("⚠️ RSSReaderImpl error reading $url: ${e.message}")
        }

        return results
    }

    private fun parseRssDate(date: String): Long? {
        return try {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
