import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.process.ProcessLogsRequest
import com.koupper.providers.process.ProcessSupervisor

data class Input(
    val name: String,
    val tailLines: Int = 120,
    val maxBytes: Int = 65536,
    val stripAnsi: Boolean = true
)

@Export
val logs: (Input) -> Map<String, Any?> = { input ->
    val supervisor = app.getInstance(ProcessSupervisor::class)
    val result = supervisor.logs(
        ProcessLogsRequest(
            name = input.name,
            tailLines = input.tailLines,
            maxBytes = input.maxBytes,
            stripAnsi = input.stripAnsi
        )
    )

    mapOf(
        "ok" to true,
        "name" to result.name,
        "pid" to result.pid,
        "logPath" to result.logPath,
        "truncated" to result.truncated,
        "lines" to result.lines
    )
}
