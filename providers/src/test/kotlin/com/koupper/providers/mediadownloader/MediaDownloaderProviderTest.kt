package com.koupper.providers.mediadownloader

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDownloaderProviderTest : AnnotationSpec() {
    init {
        MediaDownloaderServiceProvider().up()
    }

    @Test
    fun `should bind MediaDownloaderProvider to YtDlpMediaDownloader`() {
        assertTrue { app.getInstance(MediaDownloaderProvider::class) is YtDlpMediaDownloader }
    }

    @Test
    fun `download should return ok=true when yt-dlp exits 0 with filepath in stdout`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(0, "/tmp/videos/abc123.mp4", "", false)
        })

        val result = provider.download(DownloadRequest(url = "https://example.com/video", outputDir = "/tmp/videos"))

        assertTrue(result.ok)
        assertEquals("/tmp/videos/abc123.mp4", result.filePath)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `download should return ok=false when yt-dlp exits non-zero`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(1, "", "ERROR: Unable to download", false)
        })

        val result = provider.download(DownloadRequest(url = "https://example.com/video", outputDir = "/tmp/videos"))

        assertFalse(result.ok)
        assertNull(result.filePath)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `download should report timeout error when process times out`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(124, "", "timeout", true)
        })

        val result = provider.download(DownloadRequest(url = "https://example.com/video", outputDir = "/tmp/videos"))

        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("timed out") })
    }

    @Test
    fun `download artifacts should include the yt-dlp command`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(0, "/tmp/videos/abc.mp4", "", false)
        })

        val result = provider.download(DownloadRequest(url = "https://example.com/video", outputDir = "/tmp/videos"))

        val command = result.artifacts["command"]?.toString().orEmpty()
        assertTrue(command.contains("yt-dlp"))
        assertTrue(command.contains("after_move:filepath"))
    }

    @Test
    fun `extractAudio should build mp3 output path from video path`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(0, "", "", false)
        })

        val result = provider.extractAudio(AudioExtractRequest(videoPath = "/tmp/videos/abc123.mp4"))

        assertTrue(result.ok)
        assertEquals("/tmp/videos/abc123.mp3", result.filePath)
    }

    @Test
    fun `extractAudio should build wav output path when format is wav`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(0, "", "", false)
        })

        val result = provider.extractAudio(AudioExtractRequest(videoPath = "/tmp/videos/abc123.mp4", outputFormat = "wav"))

        assertTrue(result.ok)
        assertEquals("/tmp/videos/abc123.wav", result.filePath)
    }

    @Test
    fun `extractAudio should return ok=false and null path when ffmpeg fails`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(1, "", "ffmpeg: command not found", false)
        })

        val result = provider.extractAudio(AudioExtractRequest(videoPath = "/tmp/videos/abc123.mp4"))

        assertFalse(result.ok)
        assertNull(result.filePath)
        assertNotNull(result.errors.firstOrNull())
    }

    @Test
    fun `extractAudio should report timeout error`() {
        val provider = YtDlpMediaDownloader(commandRunner = { _, _ ->
            MediaRunnerResult(124, "", "timeout", true)
        })

        val result = provider.extractAudio(AudioExtractRequest(videoPath = "/tmp/videos/abc123.mp4"))

        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("timed out") })
    }
}
