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

            // Pass accumulated context through the dedicated handoff channel, not the task prompt.
            val finalConfig = if (sharedContext.isNotBlank()) {
                config.copy(contextFromPrevious = sharedContext)
            } else {
                config
            }

            val instance = orchestrator.dispatchSync(finalConfig)
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
