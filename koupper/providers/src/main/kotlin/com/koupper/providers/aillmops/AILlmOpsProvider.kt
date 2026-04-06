package com.koupper.providers.aillmops

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.providers.ai.AI
import com.koupper.providers.ai.ModelType

data class ChatRequest(
    val input: String,
    val model: ModelType? = null,
    val context: Map<String, Any?> = emptyMap()
)

data class StructuredRequest(
    val input: String,
    val schemaHint: String,
    val model: ModelType? = null
)

data class EmbedRequest(
    val texts: List<String>,
    val model: ModelType? = null
)

data class ToolCallRequest(
    val tool: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val model: ModelType? = null
)

interface AILlmOpsProvider {
    fun chat(request: ChatRequest): String
    fun structured(request: StructuredRequest): Map<String, Any?>
    fun embed(request: EmbedRequest): List<List<Double>>
    fun rerank(query: String, docs: List<String>): List<String>
    fun toolCall(request: ToolCallRequest): Map<String, Any?>
}

class DefaultAILlmOpsProvider(
    private val ai: AI?,
    private val fallbackModels: List<ModelType> = listOf(ModelType.GPT4O, ModelType.GPT5, ModelType.DEEPSEEK),
    private val mode: String = "mock"
) : AILlmOpsProvider {
    private val mapper = jacksonObjectMapper()

    override fun chat(request: ChatRequest): String {
        if (mode == "mock" || ai == null) {
            return "mock:${request.input.take(120)}"
        }
        return withFallback(request.model) { model -> ai.prompt(model, request.input, request.context) }
    }

    override fun structured(request: StructuredRequest): Map<String, Any?> {
        val text = chat(
            ChatRequest(
                input = "Return JSON only. schemaHint=${request.schemaHint}; input=${request.input}",
                model = request.model
            )
        )

        return try {
            mapper.readValue(text)
        } catch (_: Throwable) {
            mapOf("raw" to text, "schemaHint" to request.schemaHint)
        }
    }

    override fun embed(request: EmbedRequest): List<List<Double>> {
        if (mode == "mock" || ai == null) {
            return request.texts.map { text -> text.take(32).map { ch -> ch.code.toDouble() / 255.0 } }
        }
        val model = request.model ?: fallbackModels.first()
        return request.texts.map { ai.embed(model, it) }
    }

    override fun rerank(query: String, docs: List<String>): List<String> {
        return docs.sortedByDescending { score(query, it) }
    }

    override fun toolCall(request: ToolCallRequest): Map<String, Any?> {
        return mapOf(
            "tool" to request.tool,
            "arguments" to request.arguments,
            "response" to chat(
                ChatRequest(
                    input = "Tool=${request.tool}, args=${request.arguments}",
                    model = request.model
                )
            )
        )
    }

    private fun score(query: String, doc: String): Int {
        val tokens = query.lowercase().split(" ").filter { it.isNotBlank() }
        val body = doc.lowercase()
        return tokens.fold(0) { acc, token -> acc + if (body.contains(token)) 1 else 0 }
    }

    private fun withFallback(preferred: ModelType?, call: (ModelType) -> String): String {
        val models = buildList {
            if (preferred != null) add(preferred)
            addAll(fallbackModels.filterNot { it == preferred })
        }
        var lastError: Throwable? = null
        for (model in models) {
            try {
                return call(model)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("AI call failed for all fallback models", lastError)
    }
}
