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

interface InferenceEngine {
    /**
     * Executes a local inference. 
     * If [outputSchema] is provided, it attempts to parse the result into that DTO using Koupper's JSON handler.
     */
    suspend fun <T : Any> predict(
        prompt: String, 
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
        prompt: String, 
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = withContext(Dispatchers.IO) {
        
        val agentId = UUID.randomUUID().toString().substring(0, 8)
        
        // 1. Prepare the command based on AgentBudget
        val threads = budget.telemetry.physicalCores
        val modelPath = System.getenv("KOUPPER_LLM_MODEL_PATH") ?: "models/qwen-3b.gguf"
        
        // 2. Logic to wrap the output into a structured format
        val finalPrompt = if (outputSchema != null) {
            "$prompt \n\nIMPORTANT: Respond ONLY with a valid JSON matching this structure: ${outputSchema.simpleName}"
        } else {
            prompt
        }

        // 3. Execution (Simulated for this phase)
        val rawResponse = simulateInference(finalPrompt, agentId, listener)

        // 4. Structured Boundary: Catching hallucinations via Koupper's JSON parser
        if (outputSchema != null) {
            try {
                // Using Koupper's native JSON handler to force the DTO
                val result = jsonHandler.read(rawResponse)
                
                // In a real scenario, we'd map 'result' to 'outputSchema'
                // For now, we return a mock instance of the requested class if it exists in the container,
                // or try to instantiate it.
                return@withContext app.getInstance(outputSchema.kotlin) as T
            } catch (e: Exception) {
                throw IllegalStateException("LLM Hallucination detected: Output does not match ${outputSchema.name}")
            }
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
