package com.koupper.providers.youtube

import io.kotest.core.spec.style.AnnotationSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YoutubeTimedTextClientTest : AnnotationSpec() {

    @Test
    fun `getTranscript should extract text from timedtext XML response`() {
        val server = MockWebServer()
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <transcript>
                <text start="0.5" dur="2.0">Hello world</text>
                <text start="2.5" dur="1.5">this is a test</text>
            </transcript>""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(xml))
        server.enqueue(MockResponse().setResponseCode(200).setBody("")) // fallback won't be needed
        server.start()

        val client = YoutubeTimedTextClient(
            httpClient = OkHttpClient(),
            timedTextBaseUrl = server.url("").toString().trimEnd('/')
        )
        val result = client.getTranscript("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue(result.contains("Hello world"))
        assertTrue(result.contains("this is a test"))
        server.shutdown()
    }

    @Test
    fun `getTranscript should return empty string when video has no captions`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        server.start()

        val client = YoutubeTimedTextClient(
            httpClient = OkHttpClient(),
            timedTextBaseUrl = server.url("").toString().trimEnd('/')
        )
        val result = client.getTranscript("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertEquals("", result)
        server.shutdown()
    }

    @Test
    fun `getTranscript should return empty string for invalid URL`() {
        val client = YoutubeTimedTextClient()
        val result = client.getTranscript("https://not-youtube.com/watch?v=abc")
        assertEquals("", result)
    }

    @Test
    fun `extractVideoId should handle youtu-be short URLs`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        server.start()

        YoutubeTimedTextClient(
            httpClient = OkHttpClient(),
            timedTextBaseUrl = server.url("").toString().trimEnd('/')
        ).getTranscript("https://youtu.be/dQw4w9WgXcQ")

        val recordedRequest = server.takeRequest()
        assertTrue(recordedRequest.path?.contains("v=dQw4w9WgXcQ") == true,
            "Expected path to contain video ID 'dQw4w9WgXcQ' but got: ${recordedRequest.path}")
        server.shutdown()
    }
}
