package com.koupper.octopus.monitoring

import com.koupper.container.app
import com.koupper.providers.observability.ObservabilityProvider
import com.koupper.shared.monitoring.ExecutionMeta
import com.koupper.shared.monitoring.ExecutionMonitor

/**
 * Bridges the runtime execution lifecycle to [ObservabilityProvider].
 *
 * Emits:
 * - A trace span ("script.execution") on every execution with durationMs and status.
 * - A metric ("script.execution.durationMs") tagged with kind and status.
 * - An event ("script.execution.failed") with error details on failure.
 *
 * The provider is resolved lazily from the container. If it is not bound
 * (e.g. ObservabilityServiceProvider was not registered), this monitor is a no-op.
 */
class ObservabilityExecutionMonitor : ExecutionMonitor {

    private val provider: ObservabilityProvider? by lazy {
        runCatching { app.getInstance(ObservabilityProvider::class) as ObservabilityProvider }.getOrNull()
    }

    override fun <T> track(meta: ExecutionMeta, block: () -> T): T {
        val obs = provider ?: return block()
        val started = System.currentTimeMillis()

        return try {
            val result = block()
            val durationMs = System.currentTimeMillis() - started
            obs.emitTrace(
                span = "script.execution",
                attributes = mapOf(
                    "exportId" to meta.exportId,
                    "kind" to meta.kind,
                    "scriptPath" to (meta.scriptPath ?: ""),
                    "status" to "ok"
                ),
                durationMs = durationMs
            )
            obs.emitMetric(
                name = "script.execution.durationMs",
                value = durationMs.toDouble(),
                tags = mapOf("kind" to meta.kind, "status" to "ok")
            )
            result
        } catch (t: Throwable) {
            val durationMs = System.currentTimeMillis() - started
            obs.emitEvent(
                type = "script.execution.failed",
                payload = mapOf(
                    "exportId" to meta.exportId,
                    "kind" to meta.kind,
                    "scriptPath" to (meta.scriptPath ?: ""),
                    "error" to (t.message ?: t::class.simpleName ?: "unknown"),
                    "durationMs" to durationMs
                )
            )
            obs.emitMetric(
                name = "script.execution.durationMs",
                value = durationMs.toDouble(),
                tags = mapOf("kind" to meta.kind, "status" to "error")
            )
            throw t
        }
    }

    override fun reportPayload(key: String, payload: Any) {
        provider?.emitEvent(
            type = "script.payload",
            payload = mapOf("key" to key, "value" to payload.toString())
        )
    }
}
