package com.koupper.providers.agent

import com.koupper.container.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Fallback executor used when MCPToolExecutor is not available.
// Routes known Koupper SP names to their real implementations.
class DefaultToolExecutor : ToolExecutor {

    override suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        println("[TOOL_EXECUTOR] Executing ${call.toolName}.${call.action}")
        try {
            when (call.toolName) {
                "file-handler" -> {
                    val path = call.arguments["path"] as? String
                        ?: return@withContext ToolResult(call.toolName, "Error: 'path' argument required", false)
                    val f = File(path)
                    when (call.action) {
                        "read"   -> if (f.exists()) ToolResult(call.toolName, f.readText())
                                    else ToolResult(call.toolName, "Error: file not found: $path", false)
                        "exists" -> ToolResult(call.toolName, f.exists().toString())
                        "list"   -> if (f.isDirectory) ToolResult(call.toolName, f.list()?.joinToString("\n") ?: "")
                                    else ToolResult(call.toolName, "Error: not a directory: $path", false)
                        else     -> ToolResult(call.toolName, "Error: unsupported action '${call.action}'", false)
                    }
                }
                else -> ToolResult(call.toolName, "Error: tool '${call.toolName}' not registered in DefaultToolExecutor", false)
            }
        } catch (e: Exception) {
            ToolResult(call.toolName, "Exception: ${e.message}", false)
        }
    }
}
