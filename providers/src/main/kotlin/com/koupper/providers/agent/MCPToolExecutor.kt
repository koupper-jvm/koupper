package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.mcp.MCPServerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ToolExecutor implementation that delegates to Koupper's native MCPServerProvider (MCP).
 * This allows agents to use any tool registered in the global MCP catalog.
 */
class MCPToolExecutor(
    private val mcpProvider: MCPServerProvider
) : ToolExecutor {

    override suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        println("[MCP_EXECUTOR] Calling tool: ${call.toolName}")
        
        try {
            // Buscamos la herramienta en el catálogo de MCP de Koupper
            val result = mcpProvider.callTool(call.toolName, call.arguments)
            
            ToolResult(
                toolName = call.toolName,
                output = result?.toString() ?: "Success (null output)",
                success = true
            )
        } catch (e: Exception) {
            println("[MCP_EXECUTOR] Error executing ${call.toolName}: ${e.message}")
            ToolResult(
                toolName = call.toolName,
                output = "Error: ${e.message}",
                success = false
            )
        }
    }
}
