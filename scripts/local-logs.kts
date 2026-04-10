import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessLogsRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val name: String,
    val tailLines: Int = 120
)

@Export
val logs: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val result = supervisor.logs(
        ProcessLogsRequest(
            name = input.name,
            tailLines = input.tailLines
        )
    )

    mapOf(
        "ok" to true,
        "name" to result.name,
        "pid" to result.pid,
        "logPath" to result.logPath,
        "lines" to result.lines
    )
}
