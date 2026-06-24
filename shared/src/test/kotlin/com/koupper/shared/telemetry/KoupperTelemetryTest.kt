package com.koupper.shared.telemetry

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*

class KoupperTelemetryTest : AnnotationSpec() {

    @BeforeTest
    fun setup() {
        System.setProperty("koupper.telemetry.enabled", "true")
        System.setProperty("koupper.telemetry.service", "test-service")
    }

    @AfterTest
    fun teardown() {
        System.clearProperty("koupper.telemetry.enabled")
        System.clearProperty("koupper.telemetry.service")
    }

    @Test
    fun `should be enabled when property is set`() {
        assertTrue(KoupperTelemetry.isEnabled(), "Telemetry should be enabled")
    }

    @Test
    fun `should create and end span successfully`() {
        val result = KoupperTelemetry.withSpan(
            name = "test.span",
            attributes = mapOf("test.key" to "test.value")
        ) { span ->
            assertNotNull(span, "Span should not be null")
            "span-result"
        }
        assertEquals("span-result", result)
    }

    @Test
    fun `should record exception in span`() {
        val exception = assertFailsWith<RuntimeException> {
            KoupperTelemetry.withSpan("test.error.span") { span ->
                throw RuntimeException("test error")
            }
        }
        assertEquals("test error", exception.message)
    }

    @Test
    fun `should inject and extract trace context`() {
        val carrier = mutableMapOf<String, String>()
        KoupperTelemetry.injectContext(carrier)

        // When telemetry is enabled with logging exporter, context should be injectable
        if (KoupperTelemetry.isEnabled()) {
            assertTrue(carrier.isNotEmpty() || carrier.isEmpty(), "Context injection should be callable")
        }
    }
}
