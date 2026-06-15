package com.koupper.providers.stt

import java.io.File
import java.util.concurrent.TimeUnit

class WhisperCliSpeechToText(
    private val whisperCommand: String = "whisper-cpp",
    private val modelPath: String,
    private val commandRunner: ((List<String>, Long) -> SttRunnerResult)? = null
) : SpeechToTextProvider {

    override fun transcribe(request: TranscribeRequest): TranscribeResult {
        val args = buildList {
            add(whisperCommand)
            addAll(listOf("-m", modelPath))
            addAll(listOf("-f", request.audioPath))
            add("-otxt")
            if (request.language != "auto") addAll(listOf("-l", request.language))
        }

        val started = System.currentTimeMillis()
        val result = run(args, request.timeoutSeconds)
        val duration = System.currentTimeMillis() - started

        if (result.timedOut) return TranscribeResult(
            ok = false, exitCode = 124, durationMs = duration,
            errors = listOf("Transcription timed out after ${request.timeoutSeconds}s")
        )

        if (result.exitCode != 0) return TranscribeResult(
            ok = false, exitCode = result.exitCode, durationMs = duration,
            errors = listOf(result.stderr.ifBlank { "whisper-cpp failed with exitCode=${result.exitCode}" })
        )

        // whisper-cpp writes a .txt file alongside the audio; fall back to stdout if absent
        val audioFile = File(request.audioPath)
        val txtFile = File(audioFile.parent ?: ".", "${audioFile.nameWithoutExtension}.txt")
        val text = if (txtFile.exists()) txtFile.readText().trim() else result.stdout.trim()

        return TranscribeResult(
            ok = true,
            exitCode = 0,
            text = text,
            durationMs = duration,
            artifacts = mapOf("txtFile" to txtFile.absolutePath)
        )
    }

    private fun run(args: List<String>, timeoutSeconds: Long): SttRunnerResult {
        commandRunner?.let { return it.invoke(args, timeoutSeconds) }

        val process = try {
            ProcessBuilder(args).start()
        } catch (e: Exception) {
            return SttRunnerResult(127, "", e.message ?: "failed to start whisper-cpp", false)
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return SttRunnerResult(124, "", "timeout", true)
        }

        return SttRunnerResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            timedOut = false
        )
    }
}
