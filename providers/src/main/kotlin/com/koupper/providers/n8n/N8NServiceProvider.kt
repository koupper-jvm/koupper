package com.koupper.providers.n8n

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class N8NServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(N8NProvider::class, {
            N8NHttpProvider(
                mode = env("N8N_MODE", required = false, default = "mock").lowercase(),
                webhookUrl = env("N8N_WEBHOOK_URL", required = false, default = "").ifBlank { null },
                apiBaseUrl = env("N8N_API_BASE_URL", required = false, default = "").ifBlank { null },
                apiKey = env("N8N_API_KEY", required = false, default = "").ifBlank { null },
                timeoutSeconds = env("N8N_TIMEOUT_SECONDS", required = false, default = "30").toLong()
            )
        })
    }
}
