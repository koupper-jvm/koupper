import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessHealthPolicy
import com.koupper.providers.process.ProcessListRequest
import com.koupper.providers.process.ProcessStatusManyRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val names: List<String> = emptyList(),
    val includeHealth: Boolean = true,
    val autoPruneStale: Boolean = true,
    val acceptedStatusCodes: Set<Int> = emptySet(),
    val healthRetries: Int = 0,
    val healthRetryDelayMs: Long = 250,
    val healthTimeoutMs: Long = 1500
)

@Export
val status: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val candidates = supervisor.list(ProcessListRequest(autoPruneStale = input.autoPruneStale))
    val targetNames = if (input.names.isEmpty()) candidates.map { it.name } else input.names

    val statuses = runCatching {
        supervisor.statusMany(
            ProcessStatusManyRequest(
                names = targetNames,
                autoPruneStale = input.autoPruneStale,
                healthPolicy = ProcessHealthPolicy(
                    acceptedStatusCodes = input.acceptedStatusCodes,
                    retries = input.healthRetries,
                    retryDelayMs = input.healthRetryDelayMs,
                    timeoutMs = input.healthTimeoutMs
                )
            )
        )
    }.getOrDefault(emptyList()).map { result ->

            mapOf(
                "name" to result.name,
                "pid" to result.pid,
                "running" to result.running,
                "uptimeMs" to result.uptimeMs,
                "exitCode" to result.exitCode,
                "health" to if (input.includeHealth) result.health else null,
                "command" to result.command,
                "logPath" to result.logPath
            )
    }

    mapOf(
        "ok" to true,
        "count" to statuses.size,
        "processes" to statuses
    )
}
