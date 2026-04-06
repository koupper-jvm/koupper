package com.koupper.providers.mcp

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class MCPServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(MCPServerProvider::class, {
            LocalMCPServerProvider()
        })
    }
}
