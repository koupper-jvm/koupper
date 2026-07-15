package com.koupper.providers.ai

import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.providers.files.toType
import com.koupper.providers.http.HtppClient
import com.koupper.providers.http.HttpResponse

/**
 * Implementation of [AIProvider] for the OpenAI API.
 *
 * This client communicates with the OpenAI Chat Completions endpoint.
 * It supports dynamic configuration via context maps or default parameters.
 */
class OpenAIClient(
    private val httpClient: HtppClient,
    private val urlBase: String,
    private val contentType: String = "application/json",
    private val apiKey: String
) : AI {

    private val jsonHandler = JSONFileHandlerImpl()

    /**
     * Sends a chat prompt to the configured OpenAI model and retrieves the generated text.
     */
    override fun prompt(model: ModelType, input: String, context: Map<String, Any?>): String {
        val url = context["url"] as? String ?: urlBase
        val customHeaders = context["headers"] as? Map<String, String> ?: emptyMap()
        val contentType = context["contentType"] as? String ?: this.contentType

        val systemPrompt = context["systemPrompt"] as? String ?: "You are Koupper's AI assistant."

        // Build the body payload
        val bodyMap = mapOf(
            "model" to when (model) {
                ModelType.GPT4O -> "gpt-4o-mini"
                ModelType.GPT5 -> "gpt-5"
                else -> "gpt-4o"
            },
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to input)
            )
        )

        val bodyJson = jsonHandler.toJson(bodyMap)

        // Execute the HTTP request
        val response: HttpResponse = httpClient.post {
            this.url = url
            headers["Content-Type"] = contentType
            headers["Authorization"] = "Bearer $apiKey"
            headers.putAll(customHeaders)
            body(contentType) {
                string(bodyJson)
            }
        }

        val responseBody = response.asString() ?: ""
        require(responseBody.isNotBlank()) { "Empty response from OpenAI API (HTTP ${response.code()})" }

        val data = JSONFileHandlerImpl().read(responseBody).toType<Map<String, Any>>()

        // Surface API-level errors (e.g. invalid key, quota, model not found)
        if (data.containsKey("error")) {
            val errMap = data["error"] as? Map<*, *>
            val errMsg = errMap?.get("message")?.toString() ?: data["error"].toString()
            throw Exception("OpenAI API error (HTTP ${response.code()}): $errMsg")
        }

        // Extract model output
        val content = ((data["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.let { choice ->
            val message = choice["message"] as? Map<*, *>
            message?.get("content") as? String
        }

        return content ?: throw Exception("OpenAI returned no content (HTTP ${response.code()}): $responseBody")
    }

    /**
     * Embeddings endpoint is not implemented yet for this provider.
     */
    override fun embed(model: ModelType, text: String): List<Double> = emptyList()
}
