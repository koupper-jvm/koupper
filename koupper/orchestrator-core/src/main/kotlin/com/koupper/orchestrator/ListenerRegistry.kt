package com.koupper.orchestrator

import java.util.concurrent.atomic.AtomicBoolean

object ListenersRegistry {
    private val listeners = mutableMapOf<String, ListenerHandle>()

    fun start(
        key: String,
        sleepTime: Long,
        runOnce: (onJob: (List<Any?>) -> Unit) -> Unit,
        onJob: (List<Any?>) -> Unit
    ) {
        if (listeners[key]?.isRunning() == true) return

        val running = AtomicBoolean(true)

        //val parentCl = Thread.currentThread().contextClassLoader

        val thread = Thread {
            //Thread.currentThread().contextClassLoader = parentCl

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
                // noop
            } finally {
                running.set(false)
            }
        }.apply { isDaemon = true; start() }

        listeners[key] = ListenerHandle(thread, running)
    }
}
