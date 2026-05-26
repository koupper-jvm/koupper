package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.files.JSONFileHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * The runtime instance of an Agent in the Orchestrator's scope.
 */
class AgentInstance(val config: AgentConfig) {
    @Volatile
    var state: AgentState = AgentState.Idle
        private set

    internal fun updateState(newState: AgentState) {
        state = newState
    }
}

interface AgentOrchestrator {
    /**
     * Submits an agent definition for execution.
     */
    suspend fun dispatch(config: AgentConfig): AgentInstance

    /**
     * Submits an agent definition for synchronous execution.
     */
    suspend fun dispatchSync(config: AgentConfig): AgentInstance
    
    /**
     * Waits for all dispatched agents to finish their tasks.
     */
    suspend fun awaitCompletion()
}

class DefaultAgentOrchestrator(
    private val engine: InferenceEngine,
    private val jsonHandler: JSONFileHandler<Any>,
    private val budget: AgentBudget
) : AgentOrchestrator {

    // Elastic concurrency based on Universal Budget (Fase 1)
    private val semaphore = Semaphore(budget.maxConcurrentAgents)
    private val activeJobs = mutableListOf<Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun dispatch(config: AgentConfig): AgentInstance {
        val instance = AgentInstance(config)
        
        val job = scope.launch {
            // Wait for hardware availability (Universal Scalability)
            semaphore.withPermit {
                runAgent(instance)
            }
        }
        
        activeJobs.add(job)
        return instance
    }

    override suspend fun dispatchSync(config: AgentConfig): AgentInstance {
        val instance = AgentInstance(config)
        semaphore.withPermit {
            runAgent(instance)
        }
        return instance
    }

    private suspend fun runAgent(instance: AgentInstance) {
        val config = instance.config
        val task = config.task

        println("[ORCHESTRATOR] Starting agent: ${config.name}")
        instance.updateState(AgentState.Reasoning(0))

        // System Prompt Construction
        val context = """
            Identity: ${config.role.identity}
            Goal: ${config.role.goal}
            Instructions: ${config.role.instructions}
            Tools: ${config.tools.joinToString(", ")}
        """.trimIndent()

        val finalPrompt = "$context\n\nTask: ${task.prompt}"

        // Token Listener Bridge
        val listener = object : TokenListener {
            override fun onToken(token: String, agentId: String) {
                println("[ORCHESTRATOR] Token received: $token")
                task.onToken?.invoke(token)
            }
        }

        var rawResponse = ""
        try {
            // Execution Phase (Delegation to Inference Engine)
            // We request raw response to handle the hallucination logic ourselves
            val result = engine.predict<Any>(
                prompt = finalPrompt,
                outputSchema = null, // We handle the "Strict Boundary" here
                listener = listener
            )
            rawResponse = result as String
            println("[ORCHESTRATOR] Inference completed. Response size: ${rawResponse.length}")

            // State: Finalizing
            instance.updateState(AgentState.Executing("validating-schema"))

            // --- STRICT BOUNDARY (Fase 3: Validation) ---
            try {
                // Use Koupper's JSON parser
                jsonHandler.read(rawResponse)
                println("[ORCHESTRATOR] JSON validation successful.")
                
                instance.updateState(AgentState.Idle)
            } catch (e: Exception) {
                // Hallucination detected! (The JSON is invalid or doesn't match schema)
                println("[ORCHESTRATOR] Hallucination detected: ${e.message}")
                instance.updateState(AgentState.Failed("Hallucination: ${e.message}"))
                task.onHallucination?.invoke(e, rawResponse)
            }

        } catch (e: Exception) {
            println("[ORCHESTRATOR] Execution failed: ${e.message}")
            instance.updateState(AgentState.Failed(e.message ?: "Execution Error"))
            // Generic Error handling
            if (task.onHallucination != null) {
                task.onHallucination.invoke(e, rawResponse)
            }
        }
    }

    override suspend fun awaitCompletion() {
        activeJobs.joinAll()
    }
}
