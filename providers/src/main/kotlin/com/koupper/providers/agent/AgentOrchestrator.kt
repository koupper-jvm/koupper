package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.mcp.MCPServerProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * The runtime instance of an Agent in the Orchestrator's scope.
 */
class AgentInstance(val config: AgentConfig, val taskId: String) {
    @Volatile
    var state: AgentState = AgentState.Idle
        private set

    private val listeners = mutableListOf<(String) -> Unit>()

    internal fun updateState(newState: AgentState) {
        state = newState
    }

    fun onToken(callback: (String) -> Unit) {
        listeners.add(callback)
    }

    internal fun emitToken(token: String) {
        listeners.forEach { it(token) }
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
     * Retrieves an active agent instance by its ID (task ID).
     */
    fun getTask(taskId: String): AgentInstance?

    /**
     * Waits for all dispatched agents to finish their tasks.
     */
    suspend fun awaitCompletion()
}

class DefaultAgentOrchestrator(
    private val engine: InferenceEngine,
    private val toolExecutor: ToolExecutor,
    private val mcpProvider: MCPServerProvider,
    private val jsonHandler: JSONFileHandler<Any>,
    private val budget: AgentBudget
) : AgentOrchestrator {

    private val semaphore = Semaphore(budget.maxConcurrentAgents)
    private val activeJobs = mutableListOf<Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, AgentInstance>()

    override suspend fun dispatch(config: AgentConfig): AgentInstance {
        val taskId = java.util.UUID.randomUUID().toString().substring(0, 8)
        val instance = AgentInstance(config, taskId)
        tasks[taskId] = instance
        
        val job = scope.launch {
            semaphore.withPermit {
                runAgent(instance)
            }
        }
        
        activeJobs.add(job)
        return instance
    }

    override suspend fun dispatchSync(config: AgentConfig): AgentInstance {
        val taskId = "sync-${java.util.UUID.randomUUID().toString().substring(0, 4)}"
        val instance = AgentInstance(config, taskId)
        semaphore.withPermit {
            runAgent(instance)
        }
        return instance
    }

    override fun getTask(taskId: String): AgentInstance? = tasks[taskId]

    private suspend fun runAgent(instance: AgentInstance) {
        val config = instance.config
        val task = config.task

        println("[ORCHESTRATOR] Starting ReAct loop for agent: ${config.name}")
        
        // --- Contexto Inicial con Herramientas Dinámicas (MCP) ---
        val availableTools = mcpProvider.listTools().joinToString("\n") { 
            "- ${it.name}: ${it.description} (Schema: ${it.inputSchema})" 
        }

        val systemPrompt = """
            Identity: ${config.role.identity}
            Goal: ${config.role.goal}
            Instructions: ${config.role.instructions}
            
            TOOLS AVAILABLE:
            $availableTools
            
            FORMAT RULE: If you need to use a tool, respond ONLY with a JSON starting with {"toolName": ...}.
            Otherwise, respond with the final answer or requested JSON schema.
        """.trimIndent()

        val history = mutableListOf<AgentMessage>()
        history.add(AgentMessage("system", systemPrompt))
        history.add(AgentMessage("user", task.prompt))

        val listener = object : TokenListener {
            override fun onToken(token: String, agentId: String) {
                instance.emitToken(token)
                task.onToken?.invoke(token)
            }
        }

        var isFinalAnswer = false
        var turnCount = 0
        val maxTurns = 5 // Evitar loops infinitos

        try {
            while (!isFinalAnswer && turnCount < maxTurns) {
                turnCount++
                instance.updateState(AgentState.Reasoning(turnCount))

                // 1. Razonamiento (Inferencia)
                val rawResponse = engine.predict<String>(
                    history = history,
                    outputSchema = null, // Pedimos crudo para detectar intención de herramienta
                    listener = listener
                )

                // 2. ¿Es una llamada a herramienta?
                if (rawResponse.trim().startsWith("{\"toolName\"")) {
                    try {
                        val toolCall = jsonHandler.read(rawResponse).let {
                            // En una implementación real usaríamos mapper.convertValue(it, ToolCall::class.java)
                            // Aquí simulamos el parseo a mano por simplicidad del bridge actual
                            ToolCall("file-handler", "read", mapOf("path" to "metrics.json"))
                        }

                        // 3. Acción (Ejecución de herramienta)
                        instance.updateState(AgentState.Executing(toolCall.toolName))
                        val toolResult = toolExecutor.execute(toolCall)

                        // 4. Observación (Re-inyección de contexto)
                        history.add(AgentMessage("assistant", rawResponse, toolCall))
                        history.add(AgentMessage("tool", "result: ${toolResult.output}"))
                        
                        println("[ORCHESTRATOR] Tool result injected. Resuming reasoning.")
                    } catch (e: Exception) {
                        println("[ORCHESTRATOR] Failed to parse ToolCall: ${e.message}")
                        isFinalAnswer = true
                    }
                } else {
                    // No hay herramienta, es la respuesta final
                    isFinalAnswer = true
                    
                    // 5. Frontera Estricta (Validación Final si aplica)
                    if (task.outputSchema != Any::class.java) {
                        try {
                            jsonHandler.read(rawResponse)
                            instance.updateState(AgentState.Idle)
                        } catch (e: Exception) {
                            instance.updateState(AgentState.Failed("Hallucination: ${e.message}"))
                            task.onHallucination?.invoke(e, rawResponse)
                        }
                    } else {
                        instance.updateState(AgentState.Idle)
                    }
                }
            }
        } catch (e: Exception) {
            println("[ORCHESTRATOR] Loop error: ${e.message}")
            instance.updateState(AgentState.Failed(e.message ?: "Unknown"))
        }
    }

    override suspend fun awaitCompletion() {
        activeJobs.joinAll()
    }
}
