package com.koupper.providers.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * InferenceEngine backed by any OpenAI-compatible HTTP API.
 *
 * Providers supported out of the box:
 *   NVIDIA NIM  — KOUPPER_LLM_API_BASE=https://integrate.api.nvidia.com/v1
 *   OpenAI      — KOUPPER_LLM_API_BASE=https://api.openai.com/v1
 *   Groq        — KOUPPER_LLM_API_BASE=https://api.groq.com/openai/v1
 *   Together    — KOUPPER_LLM_API_BASE=https://api.together.xyz/v1
 *   Ollama      — KOUPPER_LLM_API_BASE=http://localhost:11434/v1
 *
 * Required env vars:
 *   KOUPPER_LLM_PROVIDER=openai
 *   KOUPPER_LLM_API_BASE=<base url>
 *   KOUPPER_LLM_API_KEY=<api key>
 *   KOUPPER_LLM_MODEL=<model id>
 */
class OpenAICompatibleEngine(
    private val baseUrl:     String = System.getenv("KOUPPER_LLM_API_BASE") ?: "https://api.openai.com/v1",
    private val apiKey:      String = System.getenv("KOUPPER_LLM_API_KEY")  ?: "",
    private val model:       String = System.getenv("KOUPPER_LLM_MODEL")    ?: "gpt-4o-mini",
    private val maxTokens:   Int    = System.getenv("KOUPPER_LLM_MAX_TOKENS")?.toIntOrNull() ?: 2048,
    private val temperature: Double = System.getenv("KOUPPER_LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.7,
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : InferenceEngine {

    private val mapper = jacksonObjectMapper()

    override suspend fun <T : Any> predict(
        history: List<AgentMessage>,
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = withContext(Dispatchers.IO) {

        require(apiKey.isNotBlank()) {
            "KOUPPER_LLM_API_KEY is required for OpenAI-compatible inference"
        }

        val messages = history.map { msg ->
            mapOf("role" to msg.role, "content" to msg.content)
        }

        val streaming = listener != null

        val payload = mapper.writeValueAsString(mapOf(
            "model"       to model,
            "messages"    to messages,
            "max_tokens"  to maxTokens,
            "temperature" to temperature,
            "stream"      to streaming
        ))

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string() ?: "(no body)"
            throw IllegalStateException("${response.code} from $baseUrl: $body")
        }

        val fullResponse = StringBuilder()
        val agentId = UUID.randomUUID().toString().substring(0, 8)

        if (streaming) {
            response.body?.source()?.use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    runCatching {
                        val content = mapper.readTree(data)
                            ?.get("choices")?.get(0)?.get("delta")?.get("content")
                            ?.asText()
                        if (!content.isNullOrEmpty()) {
                            fullResponse.append(content)
                            listener!!.onToken(content, agentId)
                        }
                    }
                }
            }
        } else {
            val body = response.body?.string() ?: ""
            val content = mapper.readTree(body)
                ?.get("choices")?.get(0)?.get("message")?.get("content")
                ?.asText() ?: ""
            fullResponse.append(content)
        }

        @Suppress("UNCHECKED_CAST")
        fullResponse.toString() as T
    }
}
