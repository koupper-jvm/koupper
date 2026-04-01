package com.koupper.providers.ai

/**
 * Contract for any Artificial Intelligence model integration.
 *
 * Implementations should be able to:
 * - Send prompts to AI models (e.g. OpenAI, Claude, Mistral, etc.)
 * - Receive text-based completions or embeddings.
 * - Work under a unified interface to simplify provider switching.
 */
interface AI {

    /**
     * Sends a text prompt to the selected AI model and returns a textual response.
     *
     * @param model the model to use for inference.
     * @param input the user prompt or query.
     * @param context optional contextual data (e.g. metadata, variables, etc.).
     * @return the AI-generated text response.
     */
    fun prompt(model: ModelType, input: String, context: Map<String, Any?> = emptyMap()): String

    /**
     * Generates an embedding (vector representation) for the given text using the selected model.
     *
     * @param model the model to use for embedding generation.
     * @param text the input text to embed.
     * @return a list of floating-point values representing the embedding vector.
     */
    fun embed(model: ModelType, text: String): List<Double>
}

/**
 * Enumeration of supported AI models across providers.
 */
enum class ModelType {
    GPT4O,
    GPT5,
    DEEPSEEK,
    CLAUDE35,
    MISTRAL,
    LLAMA3
}
