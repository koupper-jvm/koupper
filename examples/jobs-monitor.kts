/**
 * Local Jobs Queue Monitor
 * 
 * Demonstrates leveraging @Scheduled to act as an aggressive internal health-check 
 * monitor that actively parses Koupper's own jobs.json configuration to detect queue bloat.
 * 
 * Features:
 * - Constant internal FileHandler injection.
 * - System parsing logic.
 */
import com.koupper.container.app
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled
import com.koupper.providers.files.FileHandler
import java.io.File

@Export
@Logger(destination = "file:queue-health-monitor-[yyyy-MM-dd]", level = "WARN")
@Scheduled(debug = false, rate = 30000) // Runs every 30 seconds
val monitorLocalQueue: () -> Unit = {
    try {
        val fileHandler = app.getInstance(FileHandler::class)
        val jobsFile = File(System.getProperty("user.dir"), "jobs/jobs.json")
        
        if (!jobsFile.exists()) {
            log.info { "No jobs.json detected on root. Queue system appears dormant." }
        } else {
            // Using the native file handler to fetch the JSON string
            val content = fileHandler.getAbsolutePath(jobsFile.absolutePath)
            
            // Highly simplistic manual read count (as an example of system parsing)
            val queueSize = content.split("\"id\"").size - 1
            
            if (queueSize > 50) {
                log.error { "🚨 CRITICAL: Jobs queue is overflowing! Currently $queueSize raw jobs pending in jobs.json" }
            } else {
                log.info { "✅ Queue health normal: $queueSize pending tasks." }
            }
        }
    } catch (e: Exception) {
        log.error { "Failed to monitor queue health: ${e.message}" }
    }
}
