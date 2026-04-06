import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.iac.IaCProvider

data class Input(
    val path: String = "examples/terraform/sample",
    val action: String = "plan"
)

@Export
val iacDemo: (Input) -> Map<String, Any?> = { input ->
    val iac = app.getInstance(IaCProvider::class)
    val result = when (input.action.lowercase()) {
        "plan" -> iac.terraformPlan(input.path)
        "output" -> iac.terraformOutput(input.path)
        "drift" -> iac.driftCheck(input.path)
        else -> error("Unsupported action '${input.action}'. Use plan, output, drift.")
    }

    mapOf(
        "ok" to (result.exitCode == 0),
        "command" to result.command,
        "exitCode" to result.exitCode,
        "stdout" to result.stdout,
        "stderr" to result.stderr
    )
}
