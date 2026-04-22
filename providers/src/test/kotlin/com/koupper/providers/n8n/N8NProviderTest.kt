package com.koupper.providers.n8n

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class N8NProviderTest : AnnotationSpec() {

    private fun mockProvider() = N8NHttpProvider(mode = "mock")

    @Test
    fun `triggerWorkflow in mock mode returns ok result`() {
        val result = mockProvider().triggerWorkflow(mapOf("event" to "deploy"))
        assertTrue(result.ok)
        assertEquals(200, result.statusCode)
        assertNotNull(result.executionId)
        assertTrue(result.executionId!!.startsWith("mock-"))
    }

    @Test
    fun `getExecution in mock mode returns success status`() {
        val result = mockProvider().getExecution("mock-exec-123")
        assertTrue(result.ok)
        assertEquals("success", result.status)
        assertEquals("mock-exec-123", result.executionId)
    }

    @Test
    fun `waitForExecution in mock mode returns immediately with success`() {
        val result = mockProvider().waitForExecution("mock-exec-456", pollSeconds = 1, timeoutSeconds = 5)
        assertTrue(result.ok)
        assertEquals("success", result.status)
    }

    @Test
    fun `triggerWorkflow returns execution id in mock body`() {
        val result = mockProvider().triggerWorkflow(mapOf("key" to "value"))
        assertTrue(result.body.contains("mock"))
    }
}
