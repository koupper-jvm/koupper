package com.koupper.orchestrator

import java.util.concurrent.atomic.AtomicBoolean

object ListenersRegistry {
    private val listeners = mutableMapOf<String, ListenerHandle>()

    fun start(
        key: String,
        sleepTime: Long,
        runOnce: (onJob: (KouTask) -> Unit) -> Unit,
        onJob: (KouTask) -> Unit
    ) {
        if (listeners[key]?.isRunning() == true) return

        val running = AtomicBoolean(true)
        val thread = Thread {
            try {
                while (running.get()) {
                    runOnce(onJob)
                    var left = sleepTime
                    while (running.get() && left > 0) {
                        val chunk = minOf(200L, left)
                        Thread.sleep(chunk)
                        left -= chunk
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                running.set(false)
            }
        }.apply { isDaemon = true; start() }

        listeners[key] = ListenerHandle(thread, running)
    }

    fun stop(key: String) {
        listeners.remove(key)?.stop()
    }

    fun restart(
        key: String,
        sleepTime: Long,
        runOnce: (onJob: (KouTask) -> Unit) -> Unit,
        onJob: (KouTask) -> Unit
    ) {
        stop(key)
        start(key, sleepTime, runOnce, onJob)
    }

    fun stopAll() {
        listeners.values.forEach { it.stop() }
        listeners.clear()
    }
}
