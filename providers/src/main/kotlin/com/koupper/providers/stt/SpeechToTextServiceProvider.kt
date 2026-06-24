package com.koupper.providers.stt

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.os.envOptional
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class SpeechToTextServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.EXPERIMENTAL

    override fun externalDependencies() = listOf(
        "com.squareup.okhttp3:okhttp:4.12.0",
        "com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2"
    )

    override fun up() {
        val mode = envOptional("STT_MODE", "api").lowercase()
        app.bind(SpeechToTextProvider::class, {
            when (mode) {
                "local" -> WhisperCliSpeechToText(
                    whisperCommand = env("WHISPER_COMMAND", required = false, default = "whisper-cpp"),
                    modelPath = env("WHISPER_MODEL_PATH")
                )
                else -> WhisperApiSpeechToText(
                    apiBaseUrl = env("WHISPER_API_URL", required = false, default = "https://api.openai.com"),
                    apiKey = env("WHISPER_API_KEY"),
                    apiModel = env("WHISPER_MODEL", required = false, default = "whisper-1")
                )
            }
        })
    }
}
