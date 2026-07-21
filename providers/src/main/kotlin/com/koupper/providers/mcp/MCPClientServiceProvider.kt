package com.koupper.providers.mcp

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class MCPClientServiceProvider : ServiceProvider() {
    override fun up() {
        app.singleton(MCPClientProvider::class, {
            LocalMCPClientProvider()
        })
    }
}
