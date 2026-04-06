/**
 * Scheduled Demo (No schedules.json)
 *
 * This script demonstrates that @Scheduled works directly from annotation values
 * even when there is no schedules.json in the script context.
 *
 * Run:
 * - koupper run examples/scheduled/no-config/logger-scheduled-no-config.kts "demo"
 */
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled

@Export
@Logger(destination = "file:scheduled-no-config-[yyyy-MM-dd]", level = "DEBUG")
@Scheduled(debug = true, rate = 7000)
val setup: (String) -> String = { value ->
    val input = if (value == "EMPTY_PARAMS") "demo" else value

    log.info { "[no-config] info value='$input'" }
    println("[no-config][stdout] tick value='$input'")
    System.err.println("[no-config][stderr] sample error stream")

    "scheduled-no-config ok"
}
