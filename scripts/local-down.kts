import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessStopRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val names: List<String> = emptyList(),
    val force: Boolean = true
)

@Export
val down: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val targetNames = if (input.names.isNotEmpty()) input.names else supervisor.list().map { it.name }

    val stopped = targetNames.mapNotNull { name ->
        runCatching {
            val result = supervisor.stop(ProcessStopRequest(name = name, force = input.force))
            mapOf(
                "name" to result.name,
                "pid" to result.pid,
                "stopped" to result.stopped,
                "wasRunning" to result.wasRunning,
                "force" to result.force,
                "exitCode" to result.exitCode
            )
        }.getOrNull()
    }

    mapOf(
        "ok" to true,
        "count" to stopped.size,
        "stopped" to stopped
    )
}
