package com.koupper.providers.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*

/**
 * El SwarmCoordinator gestiona la colaboración entre múltiples agentes.
 * Implementa el protocolo de Handoff para pasar datos estructurados entre ellos.
 */
interface SwarmCoordinator {
    /**
     * Ejecuta una secuencia de agentes, pasando el resultado de uno como contexto al siguiente.
     */
    suspend fun runSequence(agents: List<AgentConfig>): List<AgentInstance>
}

class DefaultSwarmCoordinator(
    private val orchestrator: AgentOrchestrator
) : SwarmCoordinator {

    private val mapper = jacksonObjectMapper()

    override suspend fun runSequence(agents: List<AgentConfig>): List<AgentInstance> {
        val instances = mutableListOf<AgentInstance>()
        var sharedContext = ""

        for (config in agents) {
            println("[SWARM] Handoff -> Preparing agent: ${config.name}")
            
            // Inyectamos el contexto de los agentes anteriores si existe
            val finalPrompt = if (sharedContext.isNotBlank()) {
                "CONTEXT FROM PREVIOUS AGENTS:\n$sharedContext\n\nCURRENT TASK:\n${config.task.prompt}"
            } else {
                config.task.prompt
            }

            // Creamos un nuevo config con el prompt actualizado (Handoff dinámico)
            val swarmConfig = AgentConfig(
                name = config.name,
                role = config.role,
                tools = config.tools,
                task = TaskConfig(
                    outputSchema = config.task.outputSchema,
                    prompt = finalPrompt,
                    onToken = config.task.onToken,
                    onHallucination = config.task.onHallucination
                )
            )

            // Ejecución
            val instance = orchestrator.dispatchSync(swarmConfig)
            instances.add(instance)

            if (instance.state is AgentState.Failed) {
                println("[SWARM] Sequence interrupted: Agent ${config.name} failed.")
                break
            }

            // Actualizamos el contexto compartido con el resultado estructurado
            val resultJson = if (instance.result != null) {
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance.result)
            } else {
                "No output data"
            }
            
            sharedContext += "\n--- Result from ${config.name} ---\n$resultJson\n"
        }

        return instances
    }
}
