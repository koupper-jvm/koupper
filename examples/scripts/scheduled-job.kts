import com.koupper.octopus.annotations.Scheduled
import com.koupper.shared.annotations.Export

/**
 * Scheduled job using KSP-processed @Scheduled annotation.
 *
 * KSP extracts both @Scheduled and @Export at compile time.
 * The cron expression is validated by the compiler, not parsed with regex.
 */
@Scheduled(cron = "0 8 * * *")
@Export
val dailyReport: () -> String = {
    println("Running daily report at 8:00 AM")
    // Your daily logic here
    "Daily report completed"
}
