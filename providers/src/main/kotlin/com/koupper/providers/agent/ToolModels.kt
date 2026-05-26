package com.koupper.providers.agent

/**
 * Representa la intención del LLM de ejecutar una acción en el mundo real.
 */
data class ToolCall(
    val toolName: String,
    val action: String,
    val arguments: Map<String, Any?> = emptyMap()
)

/**
 * El resultado de la ejecución de una herramienta de Koupper.
 */
data class ToolResult(
    val toolName: String,
    val output: String,
    val success: Boolean = true
)

/**
 * Interface para ejecutar herramientas dinámicamente usando los Service Providers de Koupper.
 */
interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}
