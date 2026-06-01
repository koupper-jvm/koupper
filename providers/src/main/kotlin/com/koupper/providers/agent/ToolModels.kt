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

// ── Native function calling ───────────────────────────────────────────────────

/**
 * Describes a tool for native OpenAI-style function calling.
 * Maps directly to the `tools[].function` object in the API request.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )
)

/**
 * A single tool call requested by the model in a native function calling response.
 */
data class NativeToolCall(
    val id: String,          // tool_call_id returned by the API (needed for tool result message)
    val name: String,        // function name
    val arguments: Map<String, Any?>   // parsed JSON arguments
)

/**
 * The full response from a native function calling inference.
 */
data class NativeInferenceResult(
    val text: String,                        // assistant text (may be empty)
    val toolCalls: List<NativeToolCall>      // tool calls requested (may be empty)
)

