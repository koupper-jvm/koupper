package com.koupper.providers.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class InferenceConfig(
    val maxTokens: Int    = 512,
    val temperature: Double = 0.7,
    val topP: Double      = 0.95,
    val stream: Boolean   = true
)

/**
 * LlamaServerSidecar manages the lifecycle of a background 'llama-server' process.
 * It ensures the model is resident in RAM for fast multi-turn inference.
 */
class LlamaServerSidecar(
    private val budget: AgentBudget,
    private val modelPath: String,
    private val executablePath: String = "llama-server",
    private val port: Int = 8081,
    private val config: InferenceConfig = InferenceConfig()
) {
    private var process: Process? = null
    private val isStarted = AtomicBoolean(false)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val mapper = jacksonObjectMapper()

    /**
     * Starts the llama-server if it's not already running.
     * Blocks (suspend) until /health endpoint returns 200.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isStarted.get()) return@withContext

        val threads = budget.telemetry.physicalCores
        val gpuLayers = if (budget.tier is HardwareTier.GPU_ACCELERATED) 35 else 0

        val command = listOf(
            executablePath,
            "-m", modelPath,
            "-t", threads.toString(),
            "-ngl", gpuLayers.toString(),
            "--port", port.toString(),
            "--log-disable"
        )

        println("[SIDECAR] Launching persistent server: ${command.joinToString(" ")}")

        val builder = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        process = builder.start()

        // Shutdown Hook to prevent zombies
        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })

        // Health Check Polling
        waitForHealth()
        isStarted.set(true)
        println("[SIDECAR] Llama Server is ready at port $port")
    }

    /**
     * Stops the background process.
     */
    fun stop() {
        process?.let {
            if (it.isAlive) {
                println("[SIDECAR] Shutting down llama-server...")
                it.destroy()
                if (!it.waitFor(5, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                }
                isStarted.set(false)
            }
        }
    }

    private suspend fun waitForHealth() {
        val healthUrl = "http://127.0.0.1:$port/health"
        var attempts = 0
        val maxAttempts = 60 // 60 seconds timeout

        while (attempts < maxAttempts) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .GET()
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) return
            } catch (e: Exception) {
                // Server not ready yet
            }
            delay(1000)
            attempts++
        }
        throw IllegalStateException("Llama Server failed to start after 60 seconds")
    }

    /**
     * Performs inference via HTTP SSE.
     */
    fun infer(history: List<AgentMessage>): Flow<String> = flow {
        if (!isStarted.get()) {
            start()
        }

        val url = "http://127.0.0.1:$port/v1/chat/completions"
        
        // Map AgentMessage to OpenAI-compatible format
        val messages = history.map { 
            mapOf("role" to it.role, "content" to it.content)
        }

        val requestBody = mapOf(
            "messages"    to messages,
            "stream"      to config.stream,
            "n_predict"   to config.maxTokens,
            "temperature" to config.temperature,
            "top_p"       to config.topP
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
        
        if (response.statusCode() != 200) {
            throw IllegalStateException("Inference failed with status ${response.statusCode()}")
        }

        // Use a for loop to avoid suspension issues inside forEach lambda
        for (line in response.body()) {
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                
                try {
                    val json = mapper.readTree(data)
                    val contentNode = json.get("choices")?.get(0)?.get("delta")?.get("content")
                    val content = if (contentNode != null && !contentNode.isNull) contentNode.asText() else ""
                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                } catch (e: Exception) {
                    // Ignore malformed chunks
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
