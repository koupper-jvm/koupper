package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.process.ProcessSupervisor
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import com.koupper.providers.mcp.MCPServerProvider
import kotlinx.coroutines.runBlocking

class AgentServiceProvider : ServiceProvider() {

    override fun up() {
        println("🐙 [AGENTIC_CORE] Booting AgentServiceProvider...")
        // 1. Register the profiler
        app.bind(EnvironmentProfiler::class, {
            LinuxEnvironmentProfiler()
        })

        // 2. Pre-compute the budget and register it as a singleton immediately.
        // This is required for other components.
        val budget = try {
            val profiler = LinuxEnvironmentProfiler()
            runBlocking { profiler.audit() }
        } catch (e: Exception) {
            AgentBudget(
                tier = HardwareTier.LOW_END,
                maxConcurrentAgents = 1,
                telemetry = HardwareTelemetry(
                    physicalCores = 1,
                    logicalProcessors = 1,
                    totalRamGb = 4.0,
                    freeRamGb = 1.0,
                    hasAvx512Vnni = false,
                    hasAvx2 = false,
                    isNvme = false,
                    hasGpu = false
                )
            )
        }
        
        app.bind(AgentBudget::class, { budget })

        // 3. Register the Tool Executor (Using MCP)
        app.bind(ToolExecutor::class, {
            MCPToolExecutor(app.getInstance(MCPServerProvider::class))
        })

        // 4. Register the Inference Engine
        app.bind(InferenceEngine::class, {
            LlamaCppEngine(
                processSupervisor = app.getInstance(ProcessSupervisor::class),
                jsonHandler = app.getInstance(JSONFileHandler::class),
                budget = app.getInstance(AgentBudget::class)
            )
        })

        // 5. Register the Orchestrator as a singleton instance
        val orchestrator = DefaultAgentOrchestrator(
            engine = app.getInstance(InferenceEngine::class),
            toolExecutor = app.getInstance(ToolExecutor::class),
            mcpProvider = app.getInstance(MCPServerProvider::class),
            jsonHandler = app.getInstance(JSONFileHandler::class),
            budget = app.getInstance(AgentBudget::class)
        )

        app.bind(AgentOrchestrator::class, {
            orchestrator
        })

        // 6. Register the Swarm Coordinator
        app.bind(SwarmCoordinator::class, {
            DefaultSwarmCoordinator(app.getInstance(AgentOrchestrator::class))
        })
    }
}
