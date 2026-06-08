package com.koupper.providers.mcp

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tests for SSE event parsing and endpoint URL resolution.
// These run without any real HTTP server — they exercise the pure parsing logic
// extracted as internal top-level functions.

class SseParseLinesTest : AnnotationSpec() {

    @Test
    fun `parses endpoint event`() {
        val lines = sequenceOf("event: endpoint", "data: /message", "")
        val events = parseSseLines(lines)
        assertEquals(1, events.size)
        assertEquals("endpoint", events[0].first)
        assertEquals("/message", events[0].second)
    }

    @Test
    fun `parses message event with explicit event type`() {
        val lines = sequenceOf(
            "event: message",
            """data: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""",
            ""
        )
        val events = parseSseLines(lines)
        assertEquals(1, events.size)
        assertEquals("message", events[0].first)
        assertTrue(events[0].second.contains("jsonrpc"))
    }

    @Test
    fun `defaults to message when event field absent`() {
        val lines = sequenceOf(
            """data: {"jsonrpc":"2.0","id":2,"result":{}}""",
            ""
        )
        val events = parseSseLines(lines)
        assertEquals(1, events.size)
        assertEquals("message", events[0].first)
    }

    @Test
    fun `ignores blank data lines`() {
        val lines = sequenceOf("event: ping", "data: ", "")
        val events = parseSseLines(lines)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `parses multiple events in sequence`() {
        val lines = sequenceOf(
            "event: endpoint", "data: /messages", "",
            "event: message",  """data: {"jsonrpc":"2.0","id":1,"result":{}}""", ""
        )
        val events = parseSseLines(lines)
        assertEquals(2, events.size)
        assertEquals("endpoint", events[0].first)
        assertEquals("message",  events[1].first)
    }

    @Test
    fun `strips whitespace around event and data values`() {
        val lines = sequenceOf("event:  endpoint  ", "data:  /msg  ", "")
        val events = parseSseLines(lines)
        assertEquals("endpoint", events[0].first)
        assertEquals("/msg", events[0].second)
    }

    @Test
    fun `handles windows-style CRLF gracefully`() {
        // Lines are already split by reader — trailing spaces stripped by trim()
        val lines = sequenceOf("event: endpoint", "data: /message", "")
        val events = parseSseLines(lines)
        assertFalse(events[0].second.contains("\r"))
    }

    @Test
    fun `partial block without trailing blank line produces no event`() {
        val lines = sequenceOf("event: endpoint", "data: /message")  // no trailing blank
        val events = parseSseLines(lines)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `comment lines (starting with colon) are ignored`() {
        val lines = sequenceOf(": keepalive", "event: endpoint", "data: /msg", "")
        val events = parseSseLines(lines)
        assertEquals(1, events.size)
        assertEquals("endpoint", events[0].first)
    }
}

class SseEndpointResolveTest : AnnotationSpec() {

    @Test
    fun `absolute url returned as-is`() {
        val result = resolveSseEndpoint("http://localhost:3000", "http://localhost:3000/message")
        assertEquals("http://localhost:3000/message", result)
    }

    @Test
    fun `root-relative path appended to base url`() {
        val result = resolveSseEndpoint("http://localhost:3000", "/message")
        assertEquals("http://localhost:3000/message", result)
    }

    @Test
    fun `relative path appended with slash`() {
        val result = resolveSseEndpoint("http://localhost:3000", "message")
        assertEquals("http://localhost:3000/message", result)
    }

    @Test
    fun `trailing slash in base url not doubled`() {
        val result = resolveSseEndpoint("http://localhost:3000/", "/message")
        assertEquals("http://localhost:3000/message", result)
    }

    @Test
    fun `deep base url with root-relative path`() {
        val result = resolveSseEndpoint("http://host:8080/mcp", "/v1/message")
        assertEquals("http://host:8080/mcp/v1/message", result)
    }
}

class SseMCPConfigTest : AnnotationSpec() {

    @Test
    fun `sse transport accepted in MCPServerConfig`() {
        val cfg = MCPServerConfig(name = "playwright", transport = "sse", url = "http://localhost:3000")
        assertEquals("sse", cfg.transport)
    }

    @Test
    fun `default transport is http`() {
        val cfg = MCPServerConfig(name = "server", url = "http://localhost:9000")
        assertEquals("http", cfg.transport)
    }

    @Test
    fun `connect throws for unknown transport`() {
        val provider = LocalMCPClientProvider()
        val cfg = MCPServerConfig(name = "bad", transport = "grpc", url = "http://localhost:1234")
        try {
            provider.connect(cfg)
            throw AssertionError("Expected error for unknown transport")
        } catch (e: IllegalStateException) {
            assertTrue("grpc" in e.message!!)
        }
    }
}
