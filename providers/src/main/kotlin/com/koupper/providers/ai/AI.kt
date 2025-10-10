package com.koupper.providers.ai

enum class ModelType {
    GPT4O,
    GPT5,
    DEEPSEEK,
    CLAUDE35,
    MISTRAL,
    LLAMA3
}

interface AI {
    fun prompt(model: ModelType, input: String, context: Map<String, Any?>): String
    fun embed(model: ModelType, text: String): List<Double>
}
