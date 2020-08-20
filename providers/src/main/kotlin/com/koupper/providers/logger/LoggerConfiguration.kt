package com.koupper.providers.logger

interface LoggerConfiguration {
    fun setup(): Map<String, Any>
}