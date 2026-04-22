package com.koupper.providers.notifications

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationsProviderTest : AnnotationSpec() {

    private val provider = ConsoleNotificationsProvider()

    @Test
    fun `sendText returns ok result`() {
        val result = provider.sendText("general", "hello world")
        assertTrue(result.ok)
        assertEquals(200, result.statusCode)
        assertEquals("console", result.provider)
    }

    @Test
    fun `sendStructured returns ok result`() {
        val result = provider.sendStructured("alerts", mapOf("severity" to "high", "message" to "disk full"))
        assertTrue(result.ok)
        assertEquals(200, result.statusCode)
    }

    @Test
    fun `sendError returns ok result`() {
        val result = provider.sendError("ops", "Deploy failed", "Lambda update timeout after 60s")
        assertTrue(result.ok)
        assertEquals(200, result.statusCode)
    }
}
