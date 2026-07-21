package com.koupper.providers.vectordb

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object OllamaEmbedder {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mapper = jacksonObjectMapper()
    private val JSON = "application/json".toMediaType()

    fun embed(
        text: String,
        baseUrl: String = "http://localhost:11434",
        model: String = "nomic-embed-text"
    ): List<Double> {
        val body = mapper.writeValueAsString(mapOf("model" to model, "prompt" to text))
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/embeddings")
            .post(body)
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                @Suppress("UNCHECKED_CAST")
                val json = mapper.readValue(resp.body!!.string(), Map::class.java)
                (json["embedding"] as? List<*>)
                    ?.filterIsInstance<Number>()
                    ?.map { it.toDouble() }
                    ?: emptyList()
            }
        }.getOrElse { emptyList() }
    }

    fun isAvailable(baseUrl: String = "http://localhost:11434"): Boolean = runCatching {
        val request = Request.Builder().url("$baseUrl/api/tags").get().build()
        http.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
