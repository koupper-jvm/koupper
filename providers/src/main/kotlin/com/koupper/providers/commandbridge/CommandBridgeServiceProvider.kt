package com.koupper.providers.commandbridge

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class CommandBridgeServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(CommandBridgeProvider::class, {
            CommandBridgeProviderImpl()
        })
    }
}
