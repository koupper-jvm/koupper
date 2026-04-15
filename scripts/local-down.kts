import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessListRequest
import com.koupper.providers.process.ProcessStopManyRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val names: List<String> = emptyList(),
    val force: Boolean = true
)

@Export
val down: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val targetNames = if (input.names.isNotEmpty()) input.names else supervisor.list(ProcessListRequest()).map { it.name }

    val stopped = supervisor.stopMany(
        ProcessStopManyRequest(
            names = targetNames,
            force = input.force
        )
    ).map { result ->
        mapOf(
            "name" to result.name,
            "pid" to result.pid,
            "stopped" to result.stopped,
            "wasRunning" to result.wasRunning,
            "force" to result.force,
            "exitCode" to result.exitCode
        )
    }

    mapOf(
        "ok" to true,
        "count" to stopped.size,
        "stopped" to stopped
    )
}
