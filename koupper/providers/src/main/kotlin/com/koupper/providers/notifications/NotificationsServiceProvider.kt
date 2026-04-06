package com.koupper.providers.notifications

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class NotificationsServiceProvider : ServiceProvider() {
    override fun up() {
        val mode = env("NOTIFICATIONS_PROVIDER", required = false, default = "console").lowercase()
        app.bind(NotificationsProvider::class, {
            when (mode) {
                "webhook", "slack", "discord", "teams" -> WebhookNotificationsProvider(
                    webhookUrl = env("NOTIFICATIONS_WEBHOOK_URL"),
                    providerName = mode
                )

                else -> ConsoleNotificationsProvider()
            }
        })
    }
}
