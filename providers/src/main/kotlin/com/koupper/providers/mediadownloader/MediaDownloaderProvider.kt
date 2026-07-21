package com.koupper.providers.mediadownloader

import java.io.File
import java.util.concurrent.TimeUnit

data class DownloadRequest(
    val url: String,
    val outputDir: String = "./temp_videos",
    val format: String = "best[ext=mp4]/best",
    val timeoutSeconds: Long = 300,
    val cookiesFromBrowser: String? = null
)

data class DownloadResult(
    val ok: Boolean,
    val exitCode: Int,
    val filePath: String? = null,
    val durationMs: Long,
    val errors: List<String> = emptyList(),
    val artifacts: Map<String, Any?> = emptyMap()
)

data class AudioExtractRequest(
    val videoPath: String,
    val outputFormat: String = "mp3",
    val timeoutSeconds: Long = 120
)

data class AudioExtractResult(
    val ok: Boolean,
    val exitCode: Int,
    val filePath: String? = null,
    val durationMs: Long,
    val errors: List<String> = emptyList()
)

interface MediaDownloaderProvider {
    fun download(request: DownloadRequest): DownloadResult
    fun extractAudio(request: AudioExtractRequest): AudioExtractResult
}

data class MediaRunnerResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

class YtDlpMediaDownloader(
    private val ytDlpCommand: String = "yt-dlp",
    private val ffmpegCommand: String = "ffmpeg",
    private val commandRunner: ((List<String>, Long) -> MediaRunnerResult)? = null
) : MediaDownloaderProvider {

    override fun download(request: DownloadRequest): DownloadResult {
        File(request.outputDir).mkdirs()
        val args = buildList {
            add(ytDlpCommand)
            add("--print"); add("after_move:filepath")
            add("--format"); add(request.format)
            add("--output"); add("${request.outputDir}/%(id)s.%(ext)s")
            add("--no-playlist")
            request.cookiesFromBrowser?.let { add("--cookies-from-browser"); add(it) }
            add(request.url)
        }

        val started = System.currentTimeMillis()
        val result = run(args, request.timeoutSeconds)
        val duration = System.currentTimeMillis() - started

        val filePath = result.stdout.trim().ifBlank { null }
        val errors = mutableListOf<String>()
        if (result.timedOut) errors += "Download timed out after ${request.timeoutSeconds}s"
        else if (result.exitCode != 0) errors += result.stderr.ifBlank { "yt-dlp failed with exitCode=${result.exitCode}" }

        return DownloadResult(
            ok = result.exitCode == 0 && filePath != null,
            exitCode = result.exitCode,
            filePath = filePath,
            durationMs = duration,
            errors = errors,
            artifacts = mapOf(
                "command" to args.joinToString(" "),
                "stderr" to result.stderr
            )
        )
    }

    override fun extractAudio(request: AudioExtractRequest): AudioExtractResult {
        val input = File(request.videoPath)
        val output = File(input.parent, "${input.nameWithoutExtension}.${request.outputFormat}")
        val codecArg = if (request.outputFormat == "wav") "pcm_s16le" else "libmp3lame"

        val args = buildList {
            add(ffmpegCommand)
            add("-y")
            addAll(listOf("-i", request.videoPath))
            add("-vn")
            addAll(listOf("-acodec", codecArg))
            if (request.outputFormat == "mp3") addAll(listOf("-q:a", "2"))
            add(output.absolutePath)
        }

        val started = System.currentTimeMillis()
        val result = run(args, request.timeoutSeconds)
        val duration = System.currentTimeMillis() - started

        val errors = mutableListOf<String>()
        if (result.timedOut) errors += "Audio extraction timed out after ${request.timeoutSeconds}s"
        else if (result.exitCode != 0) errors += result.stderr.lines().lastOrNull { it.isNotBlank() }
            ?: "ffmpeg failed with exitCode=${result.exitCode}"

        return AudioExtractResult(
            ok = result.exitCode == 0,
            exitCode = result.exitCode,
            filePath = if (result.exitCode == 0) output.absolutePath else null,
            durationMs = duration,
            errors = errors
        )
    }

    private fun run(args: List<String>, timeoutSeconds: Long): MediaRunnerResult {
        commandRunner?.let { return it.invoke(args, timeoutSeconds) }

        val process = try {
            ProcessBuilder(args).start()
        } catch (e: Exception) {
            return MediaRunnerResult(127, "", e.message ?: "failed to start process", false)
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return MediaRunnerResult(124, "", "timeout", true)
        }

        return MediaRunnerResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            timedOut = false
        )
    }
}
