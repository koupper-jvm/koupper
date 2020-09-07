package com.koupper.providers.logger

interface Logger {
    fun log()

    fun configUsing(configuration: LoggerConfiguration): Logger
}