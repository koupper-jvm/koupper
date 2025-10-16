package com.koupper.logging

object GlobalLogger {
    @Volatile
    private var delegate: KLogger? = null

    fun setLogger(KLogger: KLogger) {
        delegate = KLogger
    }

    val log: KLogger
        get() = delegate ?: LoggerFactory.get("GlobalLogger")
}