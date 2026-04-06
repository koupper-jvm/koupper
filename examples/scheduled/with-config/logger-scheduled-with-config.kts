/**
 * Scheduled Demo (With schedules.json override)
 *
 * This script demonstrates that schedules.json overrides annotation timing/debug
 * when target/export matches this script.
 *
 * Run:
 * - koupper run examples/scheduled/with-config/logger-scheduled-with-config.kts "demo"
 */
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled

@Export
@Logger(destination = "file:scheduled-with-config-[yyyy-MM-dd]", level = "DEBUG")
@Scheduled(debug = true, rate = 20000)
val setup: (String) -> String = { value ->
    val input = if (value == "EMPTY_PARAMS") "demo" else value

    log.info { "[with-config] info value='$input'" }
    println("[with-config][stdout] tick value='$input'")
    System.err.println("[with-config][stderr] sample error stream")

    "scheduled-with-config ok"
}
