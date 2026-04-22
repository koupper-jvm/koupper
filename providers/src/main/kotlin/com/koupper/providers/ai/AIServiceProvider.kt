package com.koupper.providers.ai

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.http.HttpInvoker

class AIServiceProvider : ServiceProvider() {

    override fun up() {

        val provider = env(
            variableName = "AI_PROVIDER", required = false, default = "openai"
        ).lowercase()

        when (provider) {

            "openai" -> app.bind(AI::class, {

                OpenAIClient(
                    httpClient = HttpInvoker(),

                    urlBase = env(
                        "OPENAI_API_URL", required = false, default = "https://api.openai.com/v1/chat/completions"
                    ),

                    contentType = env(
                        "OPENAI_CONTENT_TYPE", required = false, default = "application/json"
                    ),

                    apiKey = env("OPENAI_API_KEY")
                )

            }, tag = "openai")
        }
    }
}
