import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.Pipeline

/**
 * Pipeline script using KSP-processed @Pipeline annotation.
 *
 * Defines a multi-stage pipeline that chains agent scripts.
 * KSP extracts @Pipeline metadata at compile time.
 */
@Pipeline(
    cron = "0 9 * * 1",
    chain = "DataFetcher.kts,DataProcessor.kts,Notifier.kts",
    id = "weekly-pipeline"
)
@Export
val weeklyPipeline: () -> Unit = {
    println("Weekly pipeline coordinator starting...")
}
