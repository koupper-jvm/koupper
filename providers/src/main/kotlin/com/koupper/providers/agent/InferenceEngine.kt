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

    private val modelPath = System.getenv("KOUPPER_LLM_MODEL_PATH") ?: "/home/tdn-dell/develop/llama.cpp/modelo_prueba.gguf"
    private val executablePath = System.getenv("KOUPPER_LLM_EXECUTABLE") ?: "/home/tdn-dell/develop/llama.cpp/build/bin/llama-server"
    
    // Persistent Sidecar (llama-server)
    private val sidecar = LlamaServerSidecar(budget, modelPath, executablePath)

    override suspend fun <T : Any> predict(
        history: List<AgentMessage>, 
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = withContext(Dispatchers.IO) {
        
        val agentId = UUID.randomUUID().toString().substring(0, 8)
        val fullResponse = StringBuilder()

        // --- REAL INFERENCE VIA PERSISTENT SIDECAR (SSE over HTTP) ---
        sidecar.infer(history).collect { token ->
            fullResponse.append(token)
            listener?.onToken(token, agentId)
        }

        val rawResponse = fullResponse.toString()

        // Structured Boundary: Catching hallucinations via Koupper's JSON parser
        if (outputSchema != null && outputSchema != String::class.java) {
            try {
                jsonHandler.read(rawResponse)
                @Suppress("UNCHECKED_CAST")
                return@withContext app.getInstance(outputSchema.kotlin) as T
            } catch (e: Exception) {
                throw IllegalStateException("LLM Hallucination detected: Output does not match ${outputSchema.name}. Raw: $rawResponse")
            }
        }

        @Suppress("UNCHECKED_CAST")
        return@withContext rawResponse as T
    }
}
