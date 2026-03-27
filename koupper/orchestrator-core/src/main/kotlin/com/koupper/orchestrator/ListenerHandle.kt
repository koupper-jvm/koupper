package com.koupper.orchestrator

import java.util.concurrent.atomic.AtomicBoolean

class ListenerHandle(
    private val thread: Thread,
    private val running: AtomicBoolean
) {
    fun stop() {
        running.set(false)
        thread.interrupt()
    }

    fun isRunning(): Boolean = running.get()
}
