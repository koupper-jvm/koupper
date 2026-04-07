package com.koupper.providers.n8n

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class N8NTriggerResult(
    val ok: Boolean,
    val statusCode: Int,
    val executionId: String?,
    val body: String
)

data class N8NExecutionResult(
    val ok: Boolean,
    val statusCode: Int,
    val executionId: String,
    val status: String,
    val payload: Map<String, Any?>
)

interface N8NProvider {
    fun triggerWorkflow(payload: Map<String, Any?>, webhookUrl: String? = null): N8NTriggerResult
    fun getExecution(executionId: String): N8NExecutionResult
    fun waitForExecution(executionId: String, pollSeconds: Long = 3, timeoutSeconds: Long = 120): N8NExecutionResult
}

class N8NHttpProvider(
    private val mode: String = "mock",
    private val webhookUrl: String? = null,
    private val apiBaseUrl: String? = null,
    private val apiKey: String? = null,
    private val timeoutSeconds: Long = 30
) : N8NProvider {

    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    override fun triggerWorkflow(payload: Map<String, Any?>, webhookUrl: String?): N8NTriggerResult {
        if (mode == "mock") {
            return N8NTriggerResult(
                ok = true,
                statusCode = 200,
                executionId = "mock-${UUID.randomUUID()}",
                body = mapper.writeValueAsString(mapOf("mode" to "mock", "payload" to payload))
            )
        }

        val targetUrl = webhookUrl ?: this.webhookUrl ?: error("webhookUrl is required in live mode")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val executionId = parseExecutionId(response.body())
        return N8NTriggerResult(
            ok = response.statusCode() in 200..299,
            statusCode = response.statusCode(),
            executionId = executionId,
            body = response.body()
        )
    }

    override fun getExecution(executionId: String): N8NExecutionResult {
        if (mode == "mock") {
            return N8NExecutionResult(
                ok = true,
                statusCode = 200,
                executionId = executionId,
                status = "success",
                payload = mapOf("mode" to "mock", "id" to executionId)
            )
        }

        val apiUrl = apiBaseUrl ?: error("N8N_API_BASE_URL is required in live mode")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${apiUrl.trimEnd('/')}/executions/$executionId"))
            .header("Content-Type", "application/json")
            .header("X-N8N-API-KEY", apiKey ?: error("N8N_API_KEY is required in live mode"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val payload = parseMap(response.body())
        val status = payload["status"]?.toString() ?: inferExecutionStatus(payload)
        return N8NExecutionResult(
            ok = response.statusCode() in 200..299,
            statusCode = response.statusCode(),
            executionId = executionId,
            status = status,
            payload = payload
        )
    }

    override fun waitForExecution(executionId: String, pollSeconds: Long, timeoutSeconds: Long): N8NExecutionResult {
        val started = System.currentTimeMillis()
        var latest = getExecution(executionId)

        while (latest.status.lowercase() in setOf("running", "queued", "new")) {
            val elapsedSeconds = (System.currentTimeMillis() - started) / 1000
            if (elapsedSeconds >= timeoutSeconds) {
                return latest.copy(ok = false)
            }
            Thread.sleep(pollSeconds * 1000)
            latest = getExecution(executionId)
        }

        return latest
    }

    private fun parseExecutionId(body: String): String? {
        return try {
            val map = parseMap(body)
            map["executionId"]?.toString() ?: map["id"]?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseMap(body: String): Map<String, Any?> {
        if (body.isBlank()) return emptyMap()
        return mapper.readValue(body)
    }

    private fun inferExecutionStatus(payload: Map<String, Any?>): String {
        val finished = payload["finished"] as? Boolean
        val stoppedAt = payload["stoppedAt"]
        return when {
            finished == true -> "success"
            stoppedAt != null -> "stopped"
            else -> "running"
        }
    }
}
