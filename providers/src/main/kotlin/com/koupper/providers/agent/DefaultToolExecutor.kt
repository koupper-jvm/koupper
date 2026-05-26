package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.files.FileHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultToolExecutor : ToolExecutor {

    override suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        println("[TOOL_EXECUTOR] Executing ${call.toolName}.${call.action}")
        
        try {
            when (call.toolName) {
                "file-handler" -> {
                    val handler = app.getInstance(FileHandler::class)
                    val path = call.arguments["path"] as? String ?: ""
                    
                    val content = if (call.action == "read") {
                        // Simulación de lectura de archivo
                        "{\"metrics\": [{\"type\": \"cpu\", \"value\": 0.8}]}"
                    } else "Unsupported action"
                    
                    ToolResult(call.toolName, content)
                }
                "command-runner" -> {
                    // Simulación de ejecución de comando
                    ToolResult(call.toolName, "ls output: AgenticCore.kt, InferenceEngine.kt")
                }
                else -> {
                    ToolResult(call.toolName, "Error: Tool not found", false)
                }
            }
        } catch (e: Exception) {
            ToolResult(call.toolName, "Exception: ${e.message}", false)
        }
    }
}
