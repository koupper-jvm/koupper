package com.koupper.providers.ai

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.http.HttpInvoker

class AIServiceProvider : ServiceProvider() {
    override fun up() {
        val provider = env("AI_PROVIDER").lowercase() ?: "openai"

        when (provider) {
            "openai" -> app.bind(AI::class, {
                OpenAIClient(
                    httpClient = HttpInvoker(),
                    urlBase = env("OPENAI_API_URL") ?: "https://api.openai.com/v1/chat/completions",
                    contentType = env("OPENAI_CONTENT_TYPE") ?: "application/json",
                    apiKey = env("OPENAI_API_KEY")
                )
            }, tag = "openai")
        }
    }
}
