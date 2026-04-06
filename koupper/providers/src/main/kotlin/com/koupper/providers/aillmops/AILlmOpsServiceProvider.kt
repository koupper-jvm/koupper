package com.koupper.providers.aillmops

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ai.AI
import com.koupper.providers.ai.ModelType

class AILlmOpsServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(AILlmOpsProvider::class, {
            val aiProvider = try {
                app.getInstance(AI::class)
            } catch (_: Throwable) {
                null
            }
            DefaultAILlmOpsProvider(
                ai = aiProvider,
                mode = env("AI_LLM_OPS_MODE", required = false, default = "mock"),
                fallbackModels = parseFallback(env("AI_LLM_OPS_FALLBACK", required = false, default = "GPT4O,GPT5,DEEPSEEK"))
            )
        })
    }

    private fun parseFallback(raw: String): List<ModelType> {
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { token -> ModelType.entries.firstOrNull { it.name.equals(token, ignoreCase = true) } }
            .ifEmpty { listOf(ModelType.GPT4O, ModelType.GPT5, ModelType.DEEPSEEK) }
    }
}
