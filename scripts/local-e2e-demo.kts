import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.locale2e.HttpCheck
import com.koupper.providers.locale2e.LocalE2E
import com.koupper.providers.locale2e.PersistenceCheck

data class Input(
    val processNames: List<String> = emptyList(),
    val context: String = ".",
    val configId: String? = null,
    val jobId: String? = null,
    val httpChecks: List<HttpCheck> = emptyList(),
    val persistenceChecks: List<PersistenceCheck> = emptyList()
)

@Export
val runE2E: (Input) -> Map<String, Any?> = { input ->
    val flow = app.getInstance(LocalE2E::class)
    val result = flow.runAll(
        processNames = input.processNames,
        httpChecks = input.httpChecks,
        context = input.context,
        configId = input.configId,
        jobId = input.jobId,
        persistenceChecks = input.persistenceChecks
    )

    mapOf(
        "ok" to result.ok,
        "stages" to result.stages
    )
}
