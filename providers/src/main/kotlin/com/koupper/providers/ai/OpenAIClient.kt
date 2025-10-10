package com.koupper.providers.ai

import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.providers.files.toType
import com.koupper.providers.http.HtppClient
import com.koupper.providers.http.HttpResponse

class OpenAIClient(
    private val httpClient: HtppClient,
    private val urlBase: String,
    private val contentType: String,
    private val apiKey: String,
) : AI {

    private val jsonHandler = JSONFileHandlerImpl<Map<String, Any>>()

    override fun prompt(model: ModelType, input: String, context: Map<String, Any?>): String {
        val url = context["url"] as? String ?: urlBase
        val customHeaders = context["headers"] as? Map<String, String> ?: emptyMap()
        val contentType = context["contentType"] as? String ?: this.contentType

        val bodyMap = mapOf(
            "model" to when (model) {
                ModelType.GPT4O -> "gpt-4o-mini"
                ModelType.GPT5 -> "gpt-5"
                else -> "gpt-4o"
            },
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are Koupper's AI assistant."),
                mapOf("role" to "user", "content" to input)
            )
        )

        val body = jsonHandler.mapToJsonString(bodyMap)

        val response: HttpResponse = httpClient.post {
            this.url = url
            headers["Content-Type"] = contentType
            headers["Authorization"] = "Bearer $apiKey"
            headers.putAll(customHeaders)
            body(contentType) { string(body) }
        }

        val responseBody = response.asString() ?: ""
        require(responseBody.isNotBlank()) { "Empty response from OpenAI API" }

        val data = JSONFileHandlerImpl<Map<String, Any>>().read(responseBody).toType<Map<String, Any>>()

        val content = ((data["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.let { choice ->
            val message = choice["message"] as? Map<*, *>
            message?.get("content") as? String
        }

        return content ?: "[No response from model]"
    }

    override fun embed(model: ModelType, text: String): List<Double> = emptyList()
}
