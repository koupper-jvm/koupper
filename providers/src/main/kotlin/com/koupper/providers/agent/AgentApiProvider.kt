package com.koupper.providers.agent

import com.koupper.container.app
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import com.koupper.providers.runtime.router.StreamResponse
import kotlinx.coroutines.runBlocking

class AgentApiProvider(
    private val orchestrator: AgentOrchestrator,
    private val budget: AgentBudget,
    private val router: RuntimeRouterProvider
) {

    fun registerRoutes() {
        router.registerRouter {
            path { "/api/v1" }

            // 1. GET /system/budget
            get<Unit> {
                path { "/system/budget" }
                script {
                    { _: Unit ->
                        budget
                    }
                }
            }

            // 2. POST /agents/run
            post<AgentRunRequest> {
                path { "/agents/run" }
                script {
                    { request: AgentRunRequest ->
                        val config = agent {
                            name = request.name
                            role {
                                identity = request.role
                                goal = request.goal
                            }
                            task<Map<String, Any>> {
                                prompt = request.prompt
                            }
                        }

                        val instance = runBlocking { orchestrator.dispatch(config) }
                        
                        mapOf(
                            "status" to "accepted",
                            "taskId" to instance.taskId
                        )
                    }
                }
            }

            // 3. GET /agents/stream/{taskId} (SSE)
            get<String> {
                path { "/agents/stream/{taskId}" }
                script {
                    { taskId: String ->
                        val instance = orchestrator.getTask(taskId)
                            ?: throw IllegalArgumentException("Task not found")

                        object : StreamResponse {
                            override fun onData(callback: (String) -> Unit) {
                                instance.onToken { token ->
                                    callback(token)
                                }
                            }

                            override fun onClose(callback: () -> Unit) {
                                // Logic to cleanup if needed
                            }
                        }
                    }
                }
            }
        }
    }
}

data class AgentRunRequest(
    val name: String,
    val role: String,
    val goal: String,
    val prompt: String
)
