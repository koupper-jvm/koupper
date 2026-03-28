package com.koupper.octopus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OctopusProtocolTest {

    @Test
    fun `should parse JSON run command and preserve escaped windows path`() {
        val command =
            """{"type":"RUN","requestId":"req-1","context":"C:\\\\repo","script":".\\\\examples\\\\raw-args-parser.kts","params":"9 joder"}"""

        val parsed = parseIncomingCommand(command)
        assertNotNull(parsed)

        assertEquals(ResponseMode.JSON, parsed.mode)
        assertEquals("RUN", parsed.commandType)
        assertEquals("req-1", parsed.requestId)
        assertEquals("C:\\\\repo", parsed.context)
        assertEquals(".\\\\examples\\\\raw-args-parser.kts", parsed.scriptPath)
        assertEquals("9 joder", parsed.params)
    }

    @Test
    fun `should parse health check from JSON command`() {
        val parsed = parseIncomingCommand("""{"type":"HEALTH","requestId":"h-1"}""")
        assertNotNull(parsed)

        assertEquals(ResponseMode.JSON, parsed.mode)
        assertEquals("HEALTH_CHECK", parsed.commandType)
        assertEquals("h-1", parsed.requestId)
    }

    @Test
    fun `should parse deploy command with content hash`() {
        val parsed = parseIncomingCommand(
            """{"type":"DEPLOY","requestId":"d-1","script":"worker.kts","scriptContent":"@Export val ok=1","contentSha256":"abc123","params":"--force"}"""
        )
        assertNotNull(parsed)

        assertEquals(ResponseMode.JSON, parsed.mode)
        assertEquals("DEPLOY", parsed.commandType)
        assertEquals("d-1", parsed.requestId)
        assertEquals("worker.kts", parsed.scriptPath)
        assertEquals("@Export val ok=1", parsed.scriptContent)
        assertEquals("abc123", parsed.contentSha256)
        assertEquals("--force", parsed.params)
    }

    @Test
    fun `should parse health check from legacy command`() {
        val parsed = parseIncomingCommand("HEALTH_CHECK")
        assertNotNull(parsed)

        assertEquals(ResponseMode.LEGACY, parsed.mode)
        assertEquals("HEALTH_CHECK", parsed.commandType)
    }

    @Test
    fun `daemon response JSON should keep requestId and escape payload`() {
        val json = daemonResponseJson(
            type = "print",
            requestId = "req-42",
            message = "line1\nline2 \"quoted\""
        )

        assertTrue(json.contains("\"type\":\"print\""))
        assertTrue(json.contains("\"requestId\":\"req-42\""))
        assertTrue(json.contains("line1\\nline2"))
        assertTrue(json.contains("\\\"quoted\\\""))
    }

    @Test
    fun `should ignore unknown JSON fields while parsing protocol command`() {
        val parsed = parseIncomingCommand(
            """{"type":"RUN","requestId":"req-2","context":"ctx","script":"a.kts","params":"x","extra":"ignored"}"""
        )
        assertNotNull(parsed)

        assertEquals(ResponseMode.JSON, parsed.mode)
        assertEquals("RUN", parsed.commandType)
        assertEquals("req-2", parsed.requestId)
        assertEquals("ctx", parsed.context)
        assertEquals("a.kts", parsed.scriptPath)
        assertEquals("x", parsed.params)
    }

    @Test
    fun `should return null for malformed JSON command`() {
        val parsed = parseIncomingCommand("""{"type":"RUN","context":"ctx"""")
        assertNull(parsed)
    }
}
