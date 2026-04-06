import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.observability.ObservabilityProvider

data class Input(
    val metricName: String = "job.duration",
    val metricValue: Double = 12.5
)

@Export
val observabilityOps: (Input) -> Map<String, Any?> = { input ->
    val obs = app.getInstance(ObservabilityProvider::class)
    obs.emitMetric(input.metricName, input.metricValue, tags = mapOf("source" to "example"))
    obs.emitEvent("job.executed", mapOf("name" to "provider-smoke", "ok" to true))
    obs.emitTrace("job.span", attributes = mapOf("phase" to "smoke"), durationMs = 42)
    mapOf("ok" to true, "counters" to obs.snapshotCounters())
}
