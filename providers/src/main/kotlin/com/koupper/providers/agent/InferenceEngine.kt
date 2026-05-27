package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.process.ProcessSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Interface to listen for tokens during inference.
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

    private val modelPath = System.getenv("KOUPPER_LLM_MODEL_PATH") ?: "models/qwen-3b.gguf"
    private val executablePath = System.getenv("KOUPPER_LLM_EXECUTABLE") ?: "llama-cli"
    
    private val sidecar = LlamaCppSidecar(budget, modelPath, executablePath)

    override suspend fun <T : Any> predict(
        history: List<AgentMessage>, 
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = withContext(Dispatchers.IO) {
        
        val agentId = UUID.randomUUID().toString().substring(0, 8)
        
        // 1. ReAct Intent Detection (Check for ToolCall request)
        val lastMessage = history.last().content.lowercase()
        
        // --- REAL INFERENCE VIA SIDECAR ---
        val prompt = buildFinalPrompt(history, outputSchema)
        
        // Ejecución sincrónica para evitar deadlocks en el motor de scripts
        val rawResponse = sidecar.inferSync(prompt)
        
        // Emitimos la respuesta al listener si existe
        listener?.onToken(rawResponse, agentId)

        // 2. Structured Boundary: Catching hallucinations via Koupper's JSON parser
        if (outputSchema != null && outputSchema != String::class.java) {
            try {
                jsonHandler.read(rawResponse)
                
                // Note: In a real scenario, we'd map the result to the DTO.
                // staying consistent with our previous phases.
                @Suppress("UNCHECKED_CAST")
                return@withContext app.getInstance(outputSchema.kotlin) as T
            } catch (e: Exception) {
                throw IllegalStateException("LLM Hallucination detected: Output does not match ${outputSchema.name}. Raw: $rawResponse")
            }
        }

        @Suppress("UNCHECKED_CAST")
        return@withContext rawResponse as T
    }

    private fun buildFinalPrompt(history: List<AgentMessage>, outputSchema: Class<*>?): String {
        val promptBuilder = StringBuilder()
        history.forEach { msg ->
            val prefix = when(msg.role) {
                "system" -> "### System:\n"
                "user" -> "### User:\n"
                "assistant" -> "### Assistant:\n"
                "tool" -> "### Observation:\n"
                else -> ""
            }
            promptBuilder.append("$prefix${msg.content}\n\n")
        }
        
        if (outputSchema != null) {
            promptBuilder.append("Respond ONLY with a JSON matching this structure: ${outputSchema.simpleName}\n")
        }
        
        promptBuilder.append("### Assistant:\n")
        return promptBuilder.toString()
    }
}
