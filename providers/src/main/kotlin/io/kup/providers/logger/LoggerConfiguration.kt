package io.kup.providers.logger

interface LoggerConfiguration {
    fun setup(): Map<String, Any>
}