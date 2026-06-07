package com.koupper.providers.lsp

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class LspServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(LspBridgeProvider::class, { LspBridgeProviderImpl() })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "lspBridge" to """
            import com.koupper.providers.lsp.LspBridgeProvider
            fun lspBridge(): LspBridgeProvider = com.koupper.container.app.getInstance(LspBridgeProvider::class)
        """.trimIndent()
    )
}
