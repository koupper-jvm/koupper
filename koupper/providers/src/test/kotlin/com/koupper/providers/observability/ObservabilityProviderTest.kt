package com.koupper.providers.observability

import io.kotest.core.spec.style.AnnotationSpec
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityProviderTest : AnnotationSpec() {

    private fun provider(): LocalObservabilityProvider {
        val tmp = Files.createTempFile("koupper-obs-test", ".jsonl").toFile()
        tmp.deleteOnExit()
        return LocalObservabilityProvider(sinkPath = tmp.absolutePath)
    }

    @Test
    fun `emitMetric writes a metric entry to sink`() {
        val p = provider()
        p.emitMetric("deploy.duration", 450.0, mapOf("env" to "prod"))
        val counters = p.snapshotCounters()
        assertEquals(1L, counters["metric.deploy.duration"])
    }

    @Test
    fun `emitEvent writes an event entry to sink`() {
        val p = provider()
        p.emitEvent("script.failed", mapOf("script" to "worker.kts", "error" to "timeout"))
        val counters = p.snapshotCounters()
        assertEquals(1L, counters["event.script.failed"])
    }

    @Test
    fun `emitTrace writes a trace entry to sink`() {
        val p = provider()
        p.emitTrace("script.execution", mapOf("exportId" to "setup"), durationMs = 123L)
        val counters = p.snapshotCounters()
        assertEquals(1L, counters["trace.script.execution"])
    }

    @Test
    fun `snapshotCounters accumulates multiple emissions`() {
        val p = provider()
        p.emitMetric("req", 1.0)
        p.emitMetric("req", 2.0)
        p.emitMetric("req", 3.0)
        assertEquals(3L, p.snapshotCounters()["metric.req"])
    }

    @Test
    fun `sink file is created and non-empty after emission`() {
        val tmp = Files.createTempFile("koupper-obs-sink", ".jsonl").toFile()
        tmp.deleteOnExit()
        val p = LocalObservabilityProvider(sinkPath = tmp.absolutePath)
        p.emitEvent("test.event", mapOf("key" to "value"))
        assertTrue(tmp.exists())
        assertTrue(tmp.readText().contains("test.event"))
    }
}
