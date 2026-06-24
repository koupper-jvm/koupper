package com.koupper.providers.logger

import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class LoggerServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.CORE

    override fun up() {
        this.registerDBLogger()
    }

    private fun registerDBLogger() {
        app.bind(Logger::class, {
            PSQLDBLogger()
        })
    }
}
