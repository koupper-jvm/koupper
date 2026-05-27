package com.koupper.providers.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

    @Volatile
    var result: Any? = null
        internal set

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
     * Executes an agent task. Designed to be called from Koupper Workers.
     * Returns the final result object.
     */
    suspend fun execute(config: AgentConfig): Any?

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
    private val jsonHandler: JSONFileHandler<*>,
    private val budget: AgentBudget
) : AgentOrchestrator {

    private val mapper = jacksonObjectMapper()

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

    override suspend fun execute(config: AgentConfig): Any? {
        val instance = AgentInstance(config, "worker-${java.util.UUID.randomUUID().toString().substring(0, 4)}")
        semaphore.withPermit {
            runAgent(instance)
        }
        return instance.result
    }

    override fun getTask(taskId: String): AgentInstance? = tasks[taskId]

    private suspend fun runAgent(instance: AgentInstance) {
        val config = instance.config
        val task = config.task

        println("[ORCHESTRATOR] Running Agent: ${config.name}")
        
        val availableTools = mcpProvider.listTools().joinToString("\n") { 
            "- ${it.name}: ${it.description} (Schema: ${it.inputSchema})" 
        }

        // Inyectamos el contexto de handoffs anteriores si existe
        val historyContext = if (config.contextFromPrevious != null) {
            "\nCONTEXT FROM PREVIOUS AGENT:\n${config.contextFromPrevious}\n"
        } else ""

        val systemPrompt = """
            Identity: ${config.role.identity}
            Goal: ${config.role.goal}
            Instructions: ${config.role.instructions}
            $historyContext
            
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
                println("TOKEN: $token")
                instance.emitToken(token)
            }
        }

        var isFinalAnswer = false
        var turnCount = 0
        val maxTurns = 5

        try {
            while (!isFinalAnswer && turnCount < maxTurns) {
                turnCount++
                instance.updateState(AgentState.Reasoning(turnCount))

                val rawResponse = engine.predict<String>(
                    history = history,
                    outputSchema = null,
                    listener = listener
                )

                if (rawResponse.trim().startsWith("{\"toolName\"")) {
                    try {
                        // Simulación de ToolCall via JSON
                        val toolCall = ToolCall("hardware-checker", "execute") 

                        instance.updateState(AgentState.Executing(toolCall.toolName))
                        val toolResult = toolExecutor.execute(toolCall)

                        history.add(AgentMessage("assistant", rawResponse, toolCall))
                        history.add(AgentMessage("tool", "result: ${toolResult.output}"))
                        
                    } catch (e: Exception) {
                        isFinalAnswer = true
                    }
                } else {
                    isFinalAnswer = true
                    
                    // Final result storage
                    if (task.outputSchema != Any::class.java) {
                        try {
                            jsonHandler.read(rawResponse) // structural validation
                            @Suppress("UNCHECKED_CAST")
                            instance.result = mapper.readValue(rawResponse, task.outputSchema as Class<Any>)
                            instance.updateState(AgentState.Idle)
                        } catch (e: Exception) {
                            instance.updateState(AgentState.Failed(
                                "Schema mismatch for ${task.outputSchema.simpleName}: ${e.message}"
                            ))
                        }
                    } else {
                        instance.result = rawResponse
                        instance.updateState(AgentState.Idle)
                    }
                }
            }
        } catch (e: Exception) {
            instance.updateState(AgentState.Failed(e.message ?: "Unknown"))
        }
    }

    override suspend fun awaitCompletion() {
        activeJobs.joinAll()
    }
}
