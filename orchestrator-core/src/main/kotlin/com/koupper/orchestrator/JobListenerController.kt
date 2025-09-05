package com.koupper.orchestrator

import java.util.concurrent.atomic.AtomicBoolean

object JobListenerController {
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    fun start(
        context: String,
        queue: String,
        driver: String,
        sleepTime: Long,
        engine: javax.script.ScriptEngine,
        onJob: (KouTask) -> Unit
    ) {
        if (running.get()) return
        running.set(true)

        thread = Thread {
            try {
                while (running.get()) {
                    JobRunner.runPendingJobs(queue = queue, driver = driver) { job ->
                        onJob(job)
                    }

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
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    fun isRunning(): Boolean = running.get()
}
