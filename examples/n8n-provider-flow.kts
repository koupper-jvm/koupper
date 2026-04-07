import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.n8n.N8NProvider

data class Input(
    val action: String = "trigger",
    val executionId: String? = null,
    val webhookUrl: String? = null,
    val payload: Map<String, Any?> = mapOf("source" to "koupper", "event" to "smoke"),
    val pollSeconds: Long = 2,
    val timeoutSeconds: Long = 20
)

@Export
val n8nOps: (Input) -> Map<String, Any?> = { input ->
    val n8n = app.getInstance(N8NProvider::class)

    when (input.action.lowercase()) {
        "trigger" -> {
            val result = n8n.triggerWorkflow(payload = input.payload, webhookUrl = input.webhookUrl)
            mapOf("ok" to result.ok, "statusCode" to result.statusCode, "executionId" to result.executionId, "body" to result.body)
        }

        "status" -> {
            val id = input.executionId ?: error("executionId is required for status")
            val result = n8n.getExecution(id)
            mapOf("ok" to result.ok, "statusCode" to result.statusCode, "executionId" to result.executionId, "status" to result.status)
        }

        "wait" -> {
            val id = input.executionId ?: error("executionId is required for wait")
            val result = n8n.waitForExecution(id, pollSeconds = input.pollSeconds, timeoutSeconds = input.timeoutSeconds)
            mapOf("ok" to result.ok, "statusCode" to result.statusCode, "executionId" to result.executionId, "status" to result.status)
        }

        else -> error("Unsupported action '${input.action}'. Use trigger, status, wait.")
    }
}
