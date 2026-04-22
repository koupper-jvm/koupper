package com.koupper.providers.mcp

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MCPServerProviderTest : AnnotationSpec() {

    @Test
    fun `registerTool adds tool to list`() {
        val p = LocalMCPServerProvider()
        p.registerTool("greet", "Greets the user") { args -> "Hello, ${args["name"]}" }
        val tools = p.listTools()
        assertEquals(1, tools.size)
        assertEquals("greet", tools.first().name)
    }

    @Test
    fun `listTools returns tools sorted by name`() {
        val p = LocalMCPServerProvider()
        p.registerTool("zzz", "last") { _ -> }
        p.registerTool("aaa", "first") { _ -> }
        val names = p.listTools().map { it.name }
        assertEquals(listOf("aaa", "zzz"), names)
    }

    @Test
    fun `callTool invokes registered handler and returns result`() {
        val p = LocalMCPServerProvider()
        p.registerTool("add", "Adds two numbers") { args ->
            val a = args["a"].toString().toInt()
            val b = args["b"].toString().toInt()
            a + b
        }
        val result = p.callTool("add", mapOf("a" to 3, "b" to 4))
        assertEquals(7, result)
    }

    @Test
    fun `callTool throws for unregistered tool`() {
        val p = LocalMCPServerProvider()
        assertFailsWith<IllegalStateException> {
            p.callTool("nonexistent")
        }
    }

    @Test
    fun `registerTool overwrites tool with same name`() {
        val p = LocalMCPServerProvider()
        p.registerTool("t", "v1") { "v1" }
        p.registerTool("t", "v2") { "v2" }
        assertEquals(1, p.listTools().size)
        assertEquals("v2", p.callTool("t"))
    }

    @Test
    fun `stop can be called safely when server is not running`() {
        val p = LocalMCPServerProvider()
        p.stop() // should not throw
    }
}
