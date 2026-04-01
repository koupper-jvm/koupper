package com.koupper.logging

object GlobalLogger {
    private val delegate = ThreadLocal<KLogger?>()

    fun setLogger(logger: KLogger) {
        delegate.set(logger)
    }

    val log: KLogger
        get() {
            val current = delegate.get()
            if (current != null) return current

            val created = LoggerFactory.get("GlobalLogger")
            delegate.set(created)
            return created
        }
}