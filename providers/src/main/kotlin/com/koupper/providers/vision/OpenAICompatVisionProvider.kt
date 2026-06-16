package com.koupper.providers.vision

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.providers.http.HtppClient
import java.util.Base64

class OpenAICompatVisionProvider(
    private val http: HtppClient,
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.3
) : VisionProvider {

    private val mapper = jacksonObjectMapper()

    override fun analyze(imageBytes: ByteArray, prompt: String): VisionAnalysis {
        val content = analyzeAsText(imageBytes, prompt)
        val jsonStr = Regex("""\{[\s\S]*\}""").find(content)?.value ?: "{}"
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = mapper.readValue(jsonStr, Map::class.java) as Map<String, Any?>
            VisionAnalysis(
                contentType    = raw["content_type"]?.toString()   ?: "unknown",
                summary        = raw["summary"]?.toString()        ?: "",
                topics         = (raw["topics"] as? List<*>)?.joinToString(", ") ?: "",
                sentiment      = raw["sentiment"]?.toString()      ?: "neutral",
                controversy    = raw["controversy"]?.toString()    ?: "low",
                commentTone    = raw["comment_tone"]?.toString()   ?: "irrelevant",
                relevanceScore = (raw["relevance_score"] as? Number)?.toInt() ?: 0,
                keepImage      = raw["keep_image"] == true,
                reason         = raw["reason"]?.toString()         ?: ""
            )
        } catch (_: Exception) {
            VisionAnalysis(summary = "Error parsing response", reason = content.take(200))
        }
    }

    override fun analyzeAsText(imageBytes: ByteArray, prompt: String): String {
        val base64  = Base64.getEncoder().encodeToString(imageBytes)
        val bodyStr = mapper.writeValueAsString(mapOf(
            "model"       to model,
            "temperature" to temperature,
            "messages"    to listOf(mapOf(
                "role"    to "user",
                "content" to listOf(
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,$base64")),
                    mapOf("type" to "text", "text" to prompt)
                )
            ))
        ))

        val raw = http.post {
            url = "$apiUrl/v1/chat/completions"
            headers["Authorization"] = "Bearer $apiKey"
            body("application/json") { json(bodyStr) }
        }.asString() ?: "{}"

        val root    = mapper.readValue(raw, Map::class.java)
        val choices = root["choices"] as? List<*>
        val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
        return message?.get("content") as? String ?: ""
    }
}
