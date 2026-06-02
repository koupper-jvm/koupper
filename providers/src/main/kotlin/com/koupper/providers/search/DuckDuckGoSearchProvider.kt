package com.koupper.providers.search

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class DuckDuckGoSearchProvider : WebSearchProvider {

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url     = "https://html.duckduckgo.com/html/?q=$encoded"
        val html    = fetch(url)
        return parseResults(html).take(maxResults)
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Koupper/1.0)")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    private fun decodeUrl(raw: String): String {
        // DuckDuckGo wraps URLs in: //duckduckgo.com/l/?uddg=<encoded-url>&...
        val uddg = Regex("""uddg=([^&]+)""").find(raw)?.groupValues?.get(1)
        return if (uddg != null) URLDecoder.decode(uddg, "UTF-8")
               else if (raw.startsWith("//")) "https:$raw"
               else raw
    }

    private fun parseResults(html: String): List<SearchResult> {
        val results  = mutableListOf<SearchResult>()

        val titlePat   = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetPat = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val titles   = titlePat.findAll(html).toList()
        val snippets = snippetPat.findAll(html).toList()

        titles.forEachIndexed { i, m ->
            val rawUrl  = m.groupValues[1]
            val url     = decodeUrl(rawUrl)
            val title   = m.groupValues[2].stripTags()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.stripTags() ?: ""

            if (url.startsWith("http") && !url.contains("duckduckgo.com/y.js") && title.isNotBlank()) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
    }

    private fun String.stripTags() = replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .trim()
}
