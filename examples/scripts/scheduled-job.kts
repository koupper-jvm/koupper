import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.Scheduled

/**
 * Scheduled job using KSP-processed @Scheduled annotation.
 *
 * KSP extracts both @Scheduled and @Export at compile time.
 * The cron expression is validated by the compiler, not parsed with regex.
 */
@Scheduled(cron = "0 8 * * *")
@Export
val dailyReport: () -> Unit = {
    println("Running daily report at 8:00 AM")
    // Your daily logic here
}
