package com.koupper.providers.vision

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.http.HtppClient

class VisionServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(VisionProvider::class, {
            OpenAICompatVisionProvider(
                http    = app.getInstance(HtppClient::class),
                apiUrl  = env("LLM_API_URL", required = false, default = "http://localhost:1234"),
                apiKey  = env("LLM_API_KEY", required = false, default = "local"),
                model   = env("LLM_MODEL",   required = false, default = "gpt-4o")
            )
        })
    }
}
