package com.koupper.providers.aillmops

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AILlmOpsProviderTest : AnnotationSpec() {

    private fun mockProvider() = DefaultAILlmOpsProvider(ai = null, mode = "mock")

    @Test
    fun `chat in mock mode returns mock-prefixed response containing input`() {
        val result = mockProvider().chat(ChatRequest(input = "hello kotlin"))
        assertTrue(result.startsWith("mock:"))
        assertTrue(result.contains("hello kotlin"))
    }

    @Test
    fun `embed in mock mode returns one vector per input text`() {
        val result = mockProvider().embed(EmbedRequest(texts = listOf("alpha", "beta", "gamma")))
        assertEquals(3, result.size)
        assertTrue(result.all { it.isNotEmpty() })
    }

    @Test
    fun `embed in mock mode returns char-code based values between 0 and 1`() {
        val result = mockProvider().embed(EmbedRequest(texts = listOf("abc")))
        val vector = result.first()
        assertTrue(vector.all { it in 0.0..1.0 })
    }

    @Test
    fun `rerank sorts docs by query token relevance`() {
        val ranked = mockProvider().rerank(
            query = "kotlin test",
            docs = listOf("unrelated content", "kotlin unit test framework", "java code")
        )
        assertEquals("kotlin unit test framework", ranked.first())
    }

    @Test
    fun `toolCall returns tool name arguments and response`() {
        val result = mockProvider().toolCall(
            ToolCallRequest(tool = "deploy", arguments = mapOf("env" to "prod"))
        )
        assertEquals("deploy", result["tool"])
        assertEquals(mapOf("env" to "prod"), result["arguments"])
        assertTrue(result.containsKey("response"))
    }

    @Test
    fun `structured returns map with raw key when mock response is not valid JSON`() {
        val result = mockProvider().structured(
            StructuredRequest(input = "what is x?", schemaHint = "string")
        )
        // mock chat returns "mock:..." which is not JSON — parser falls back to raw map
        assertTrue(result.containsKey("raw") || result.containsKey("schemaHint"))
    }
}
