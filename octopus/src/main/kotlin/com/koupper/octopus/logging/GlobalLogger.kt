package com.koupper.octopus.logging

object GlobalLogger {
    @Volatile
    private var delegate: Logger? = null

    fun setLogger(logger: Logger) {
        delegate = logger
    }

    val log: Logger
        get() = delegate ?: LoggerFactory.get("GlobalLogger")
}