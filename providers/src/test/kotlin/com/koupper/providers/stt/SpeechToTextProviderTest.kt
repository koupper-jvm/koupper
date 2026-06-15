package com.koupper.providers.stt

import io.kotest.core.spec.style.AnnotationSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechToTextProviderTest : AnnotationSpec() {

    // ── binding ──────────────────────────────────────────────────────────────

    @Test
    fun `WhisperApiSpeechToText should implement SpeechToTextProvider`() {
        assertTrue { WhisperApiSpeechToText(apiKey = "test-key") is SpeechToTextProvider }
    }

    @Test
    fun `WhisperCliSpeechToText should implement SpeechToTextProvider`() {
        assertTrue { WhisperCliSpeechToText(modelPath = "/models/base.bin") is SpeechToTextProvider }
    }

    // ── WhisperCliSpeechToText ────────────────────────────────────────────────

    @Test
    fun `cli should return transcription text from stdout when txt file absent`() {
        val provider = WhisperCliSpeechToText(
            modelPath = "/models/base.bin",
            commandRunner = { _, _ -> SttRunnerResult(0, "Hola mundo desde el video.", "", false) }
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "/tmp/audio.mp3"))

        assertTrue(result.ok)
        assertEquals("Hola mundo desde el video.", result.text)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `cli should return ok=false when whisper-cpp exits non-zero`() {
        val provider = WhisperCliSpeechToText(
            modelPath = "/models/base.bin",
            commandRunner = { _, _ -> SttRunnerResult(1, "", "error: model not found", false) }
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "/tmp/audio.mp3"))

        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("model not found") })
    }

    @Test
    fun `cli should report timeout`() {
        val provider = WhisperCliSpeechToText(
            modelPath = "/models/base.bin",
            commandRunner = { _, _ -> SttRunnerResult(124, "", "timeout", true) }
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "/tmp/audio.mp3"))

        assertFalse(result.ok)
        assertEquals(124, result.exitCode)
        assertTrue(result.errors.any { it.contains("timed out") })
    }

    @Test
    fun `cli should include language flag when language is not auto`() {
        var capturedArgs = listOf<String>()
        val provider = WhisperCliSpeechToText(
            modelPath = "/models/base.bin",
            commandRunner = { args, _ -> capturedArgs = args; SttRunnerResult(0, "texto", "", false) }
        )

        provider.transcribe(TranscribeRequest(audioPath = "/tmp/audio.mp3", language = "es"))

        assertTrue(capturedArgs.containsAll(listOf("-l", "es")))
    }

    @Test
    fun `cli should omit language flag when language is auto`() {
        var capturedArgs = listOf<String>()
        val provider = WhisperCliSpeechToText(
            modelPath = "/models/base.bin",
            commandRunner = { args, _ -> capturedArgs = args; SttRunnerResult(0, "text", "", false) }
        )

        provider.transcribe(TranscribeRequest(audioPath = "/tmp/audio.mp3", language = "auto"))

        assertFalse(capturedArgs.contains("-l"))
    }

    // ── WhisperApiSpeechToText ────────────────────────────────────────────────

    @Test
    fun `api should return transcription text from JSON response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"text":"Este es el texto transcrito."}"""))
        server.start()

        val provider = WhisperApiSpeechToText(
            apiBaseUrl = server.url("").toString().trimEnd('/'),
            apiKey = "test-key",
            httpClient = OkHttpClient()
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "src/test/resources/sample.mp3"))

        assertTrue(result.ok)
        assertEquals("Este es el texto transcrito.", result.text)
        assertTrue(result.errors.isEmpty())

        server.shutdown()
    }

    @Test
    fun `api should return ok=false on 401 unauthorized`() {
        val server = MockWebServer()
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error":{"message":"Invalid API key"}}"""))
        server.start()

        val provider = WhisperApiSpeechToText(
            apiBaseUrl = server.url("").toString().trimEnd('/'),
            apiKey = "bad-key",
            httpClient = OkHttpClient()
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "src/test/resources/sample.mp3"))

        assertFalse(result.ok)
        assertEquals(401, result.exitCode)
        assertTrue(result.errors.any { it.contains("401") })

        server.shutdown()
    }

    @Test
    fun `api should return ok=false on network failure`() {
        val provider = WhisperApiSpeechToText(
            apiBaseUrl = "http://localhost:19999",
            apiKey = "test-key",
            httpClient = OkHttpClient()
        )

        val result = provider.transcribe(TranscribeRequest(audioPath = "src/test/resources/sample.mp3"))

        assertFalse(result.ok)
        assertTrue(result.errors.isNotEmpty())
    }
}
