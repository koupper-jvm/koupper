package io.kup.providers.logger

interface Logger {
    fun log()

    fun configUsing(configuration: LoggerConfiguration): Logger
}