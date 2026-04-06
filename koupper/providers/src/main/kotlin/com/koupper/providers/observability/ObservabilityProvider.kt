package com.koupper.providers.observability

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface ObservabilityProvider {
    fun emitMetric(name: String, value: Double, tags: Map<String, String> = emptyMap())
    fun emitEvent(type: String, payload: Map<String, Any?> = emptyMap())
    fun emitTrace(span: String, attributes: Map<String, Any?> = emptyMap(), durationMs: Long? = null)
    fun snapshotCounters(): Map<String, Long>
}

class LocalObservabilityProvider(
    private val sinkPath: String = ".koupper-observability.jsonl"
) : ObservabilityProvider {
    private val mapper = jacksonObjectMapper()
    private val counters = ConcurrentHashMap<String, Long>()

    override fun emitMetric(name: String, value: Double, tags: Map<String, String>) {
        counters.merge("metric.$name", 1L) { a, b -> a + b }
        write(
            mapOf(
                "type" to "metric",
                "name" to name,
                "value" to value,
                "tags" to tags,
                "timestamp" to Instant.now().toString()
            )
        )
    }

    override fun emitEvent(type: String, payload: Map<String, Any?>) {
        counters.merge("event.$type", 1L) { a, b -> a + b }
        write(
            mapOf(
                "type" to "event",
                "eventType" to type,
                "payload" to payload,
                "timestamp" to Instant.now().toString()
            )
        )
    }

    override fun emitTrace(span: String, attributes: Map<String, Any?>, durationMs: Long?) {
        counters.merge("trace.$span", 1L) { a, b -> a + b }
        write(
            mapOf(
                "type" to "trace",
                "span" to span,
                "attributes" to attributes,
                "durationMs" to durationMs,
                "timestamp" to Instant.now().toString()
            )
        )
    }

    override fun snapshotCounters(): Map<String, Long> = counters.toMap()

    private fun write(payload: Map<String, Any?>) {
        val file = File(sinkPath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.appendText(mapper.writeValueAsString(payload) + System.lineSeparator())
    }
}
