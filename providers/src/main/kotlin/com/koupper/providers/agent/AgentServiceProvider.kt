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
        
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        // 1. Register the profiler
        app.bind(EnvironmentProfiler::class, {
            if (isLinux) LinuxEnvironmentProfiler() else GenericEnvironmentProfiler()
        })

        // 2. Pre-compute the budget and register it as a singleton immediately.
        // This is required for other components.
        val budget = try {
            val profiler = if (isLinux) LinuxEnvironmentProfiler() else GenericEnvironmentProfiler()
            runBlocking { profiler.audit() }
        } catch (e: Exception) {
            AgentBudget(
                tier = HardwareTier.LOW_END,
                maxConcurrentAgents = 1,
                telemetry = HardwareTelemetry(
                    physicalCores = Runtime.getRuntime().availableProcessors() / 2,
                    logicalProcessors = Runtime.getRuntime().availableProcessors(),
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
        // Switch via KOUPPER_LLM_PROVIDER env var:
        //   openai → OpenAICompatibleEngine (NVIDIA, OpenAI, Groq, Together, Ollama…)
        //   (default) → LlamaCppEngine (local llama-server)
        val engine: InferenceEngine = when (System.getenv("KOUPPER_LLM_PROVIDER")?.lowercase()) {
            "openai", "openai-compatible", "nvidia", "groq", "together" ->
                OpenAICompatibleEngine()
            else ->
                LlamaCppEngine(
                    processSupervisor = app.getInstance(ProcessSupervisor::class),
                    jsonHandler = app.getInstance(JSONFileHandler::class),
                    budget = app.getInstance(AgentBudget::class)
                )
        }
        app.bind(InferenceEngine::class, { engine })

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
