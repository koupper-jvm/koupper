package io.kup.providers.logger

import io.kup.container.app
import io.kup.providers.ServiceProvider


class LoggerServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerDBLogger()
    }

    private fun registerDBLogger() {
        app.bind(Logger::class, {
            DBLogger()
        })
    }
}