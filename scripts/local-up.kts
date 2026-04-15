import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessHealthPolicy
import com.koupper.providers.process.ProcessStartRequest
import com.koupper.providers.process.ProcessSupervisor

data class ServiceInput(
    val name: String,
    val shellCommand: String? = null,
    val executable: String? = null,
    val args: List<String> = emptyList(),
    val workingDirectory: String = ".",
    val healthUrl: String? = null,
    val ensureHealthyOnStart: Boolean = false,
    val healthAcceptedStatusCodes: Set<Int> = emptySet(),
    val healthRetries: Int = 0,
    val healthRetryDelayMs: Long = 250,
    val healthTimeoutMs: Long = 1500,
    val env: Map<String, String> = emptyMap()
)

data class Input(
    val services: List<ServiceInput> = listOf(
        ServiceInput(name = "frontend-dev", shellCommand = "npm run dev", workingDirectory = "."),
        ServiceInput(name = "backend-dev", shellCommand = "./gradlew run", workingDirectory = ".")
    )
)

@Export
val up: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val started = supervisor.startMany(
        input.services.map { service ->
            ProcessStartRequest(
                name = service.name,
                shellCommand = service.shellCommand,
                executable = service.executable,
                args = service.args,
                workingDirectory = service.workingDirectory,
                healthUrl = service.healthUrl,
                healthPolicy = ProcessHealthPolicy(
                    acceptedStatusCodes = service.healthAcceptedStatusCodes,
                    retries = service.healthRetries,
                    retryDelayMs = service.healthRetryDelayMs,
                    timeoutMs = service.healthTimeoutMs
                ),
                ensureHealthyOnStart = service.ensureHealthyOnStart,
                environment = service.env
            )
        }
    ).map { result ->

        mapOf(
            "name" to result.name,
            "processId" to result.processId,
                "alreadyRunning" to result.alreadyRunning,
                "healthyAtStart" to result.healthyAtStart,
                "startedAt" to result.startedAt,
                "logPath" to result.logPath,
                "command" to result.command
            )
    }

    mapOf(
        "ok" to true,
        "count" to started.size,
        "started" to started
    )
}
