package com.koupper.providers.stt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperApiSpeechToText(
    private val apiBaseUrl: String = "https://api.openai.com",
    private val apiKey: String,
    private val apiModel: String = "whisper-1",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
) : SpeechToTextProvider {

    private val mapper = jacksonObjectMapper()

    override fun transcribe(request: TranscribeRequest): TranscribeResult {
        val audioFile = File(request.audioPath)
        val started = System.currentTimeMillis()

        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/${audioFile.extension}".toMediaType())
                )
                .addFormDataPart("model", apiModel)
                .apply {
                    if (request.language != "auto") addFormDataPart("language", request.language)
                }
                .build()

            val httpRequest = Request.Builder()
                .url("$apiBaseUrl/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val duration = System.currentTimeMillis() - started
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return TranscribeResult(
                    ok = false,
                    exitCode = response.code,
                    durationMs = duration,
                    errors = listOf("API returned ${response.code}: $responseBody")
                )
            }

            val text = mapper.readTree(responseBody).path("text").asText("")
            TranscribeResult(
                ok = true,
                exitCode = response.code,
                text = text,
                durationMs = duration,
                artifacts = mapOf("model" to apiModel, "endpoint" to "$apiBaseUrl/v1/audio/transcriptions")
            )
        } catch (e: Exception) {
            TranscribeResult(
                ok = false,
                exitCode = -1,
                durationMs = System.currentTimeMillis() - started,
                errors = listOf(e.message ?: "HTTP request failed")
            )
        }
    }
}
