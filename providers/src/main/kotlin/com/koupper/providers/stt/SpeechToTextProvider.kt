package com.koupper.providers.stt

data class TranscribeRequest(
    val audioPath: String,
    val language: String = "auto",
    val model: String = "base",
    val timeoutSeconds: Long = 300
)

data class TranscribeResult(
    val ok: Boolean,
    val exitCode: Int = 0,
    val text: String = "",
    val language: String? = null,
    val durationMs: Long,
    val errors: List<String> = emptyList(),
    val artifacts: Map<String, Any?> = emptyMap()
)

interface SpeechToTextProvider {
    fun transcribe(request: TranscribeRequest): TranscribeResult
}

data class SttRunnerResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)
