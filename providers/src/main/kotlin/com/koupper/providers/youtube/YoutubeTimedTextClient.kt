package com.koupper.providers.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.xml.parsers.DocumentBuilderFactory

class YoutubeTimedTextClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val timedTextBaseUrl: String = "https://www.youtube.com"
) : YoutubeTranscriptProvider {

    /**
     * Fetches the transcript for a YouTube video URL.
     * Tries Spanish captions first, falls back to English.
     * Returns empty string if no captions are available or the URL is not a YouTube URL.
     */
    override fun getTranscript(youtubeUrl: String): String {
        val videoId = extractVideoId(youtubeUrl) ?: return ""
        return fetchTimedText(videoId, "es").ifBlank { fetchTimedText(videoId, "en") }
    }

    private fun extractVideoId(url: String): String? {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null
        val patterns = listOf(
            Regex("v=([^&]+)"),
            Regex("youtu\\.be/([^?/]+)"),
            Regex("shorts/([^?/]+)")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    private fun fetchTimedText(videoId: String, lang: String): String {
        val request = Request.Builder()
            .url("$timedTextBaseUrl/api/timedtext?v=$videoId&lang=$lang")
            .get()
            .build()
        return try {
            val body = httpClient.newCall(request).execute().use { resp ->
                resp.body?.string() ?: return ""
            }
            if (body.isBlank()) return ""
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(body.byteInputStream())
            val nodes = doc.getElementsByTagName("text")
            buildString {
                for (i in 0 until nodes.length) {
                    val text = nodes.item(i).textContent.trim()
                    if (text.isNotBlank()) { append(text); append(" ") }
                }
            }.trim()
        } catch (_: Exception) {
            ""
        }
    }
}
