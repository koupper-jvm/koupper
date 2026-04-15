package com.koupper.logging

data class StreamRoutingConfig(
    val stdout: LogLevel = LogLevel.INFO,
    val stderr: LogLevel = LogLevel.ERROR
)

object StreamRoutingContext {
    private val current = ThreadLocal.withInitial { StreamRoutingConfig() }

    fun get(): StreamRoutingConfig = current.get()

    fun set(config: StreamRoutingConfig) {
        current.set(config)
    }

    fun clear() {
        current.remove()
    }
}
