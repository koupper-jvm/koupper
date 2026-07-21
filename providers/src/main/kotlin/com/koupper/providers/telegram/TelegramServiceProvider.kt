package com.koupper.providers.telegram

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class TelegramServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(TelegramChannelProvider::class, {
            TelegramChannelProviderImpl()
        })
    }
}
