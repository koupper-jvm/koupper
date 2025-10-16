package com.koupper.orchestrator

import com.koupper.logging.GlobalLogger
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

        // 1) Captura el logger y (opcional) el MDC/CL del hilo padre
        val capturedLogger = GlobalLogger.log
        // val parentMdc = MDC.getCopyOfContextMap()
        val parentCl = Thread.currentThread().contextClassLoader

        val thread = Thread {
            // 2) Adopta el classloader del padre (opcional pero recomendado)
            Thread.currentThread().contextClassLoader = parentCl

            // 3) Instala el logger capturado dentro del hilo
            val prev = GlobalLogger.log
            GlobalLogger.setLogger(capturedLogger)

            // 4) Si usas MDC de SLF4J, propágalo también
            // val prevMdc = MDC.getCopyOfContextMap()
            try {
                // if (parentMdc != null) MDC.setContextMap(parentMdc) else MDC.clear()

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
                // 5) Restaura MDC y logger del hilo (por limpieza)
                // if (prevMdc != null) MDC.setContextMap(prevMdc) else MDC.clear()
                GlobalLogger.setLogger(prev)
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
        runOnce: (onJob: (List<Any?>) -> Unit) -> Unit,
        onJob: (List<Any?>) -> Unit
    ) {
        stop(key)
        start(key, sleepTime, runOnce, onJob)
    }

    fun stopAll() {
        listeners.values.forEach { it.stop() }
        listeners.clear()
    }
}
