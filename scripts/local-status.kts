import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessStatusRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val names: List<String> = emptyList(),
    val includeHealth: Boolean = true,
    val healthTimeoutMs: Long = 1500
)

@Export
val status: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val candidates = supervisor.list()
    val targetNames = if (input.names.isEmpty()) candidates.map { it.name } else input.names

    val statuses = targetNames.mapNotNull { name ->
        runCatching {
            val result = supervisor.status(
                ProcessStatusRequest(
                    name = name,
                    healthTimeoutMs = input.healthTimeoutMs
                )
            )

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
        }.getOrNull()
    }

    mapOf(
        "ok" to true,
        "count" to statuses.size,
        "processes" to statuses
    )
}
