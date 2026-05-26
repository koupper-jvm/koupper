package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.process.ProcessSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Interface to listen for tokens during inference.
 * This avoids a direct dependency on Koupper's EventBus in the providers module.
 */
interface TokenListener {
    fun onToken(token: String, agentId: String)
}

/**
 * Representa un mensaje en la conversación del agente.
 */
data class AgentMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val toolCall: ToolCall? = null
)

interface InferenceEngine {
    /**
     * Executes a local inference with full conversation history. 
     */
    suspend fun <T : Any> predict(
        history: List<AgentMessage>, 
        outputSchema: Class<T>? = null, 
        listener: TokenListener? = null
    ): T
}

class LlamaCppEngine(
    private val processSupervisor: ProcessSupervisor,
    private val jsonHandler: JSONFileHandler<Any>,
    private val budget: AgentBudget
) : InferenceEngine {

    override suspend fun <T : Any> predict(
        history: List<AgentMessage>, 
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = withContext(Dispatchers.IO) {
        
        val agentId = UUID.randomUUID().toString().substring(0, 8)
        
        // Simulación: Buscamos si el último mensaje pide una herramienta
        val lastMessage = history.last().content.lowercase()
        
        val rawResponse = when {
            // Mock: Si el prompt menciona 'hardware', el LLM emite una llamada a herramienta MCP
            lastMessage.contains("hardware") && !lastMessage.contains("result:") -> {
                """{"toolName": "hardware-checker", "action": "execute", "arguments": {}}"""
            }
            else -> simulateInference(history.last().content, agentId, listener)
        }

        @Suppress("UNCHECKED_CAST")
        return@withContext rawResponse as T
    }

    private fun simulateInference(prompt: String, agentId: String, listener: TokenListener?): String {
        // Emit events through the listener with a small delay to simulate real LLM generation
        Thread.sleep(100)
        listener?.onToken("{", agentId)
        Thread.sleep(100)
        listener?.onToken("\"status\": \"done\"", agentId)
        Thread.sleep(100)
        listener?.onToken("}", agentId)
        
        return "{\"status\": \"done\"}"
    }
}
