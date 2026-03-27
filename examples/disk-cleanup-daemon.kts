/**
 * Nightly Disk Cleanup Daemon
 * 
 * Demonstrates UNIX-like CRON execution and native FileHandler interop using Koupper's Global App Context.
 * 
 * Features showcased:
 * - @Scheduled(cron = "..."): Uses standard Cron formatting instead of flat MS intervals.
 * - app.getInstance(FileHandler::class): Native filesystem abstraction utility.
 */
import com.koupper.container.app
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled
import com.koupper.providers.files.FileHandler
import java.io.File

@Export
@Logger(destination = "file:disk-maintenance-[yyy-MM-dd]", level = "INFO")
// Evaluates standard CRON schedules -> 0 0 * * * = Midnight every day
@Scheduled(debug = false, cron = "0 0 * * *")
val nightlyLogCleanup: () -> Unit = {
    log.info { "🧹 Nightly Maintenance Triggered! Starting disk cleanup pipeline..." }
    
    try {
        // Fetch Koupper's universal File Handler
        val fileHandler = app.getInstance(FileHandler::class)
        
        val logDir = File(System.getProperty("user.dir"), "logs")
        if (logDir.exists()) {
            val obsoleteFiles = logDir.listFiles()?.filter { 
                it.name.endsWith(".log") && (System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000) // Older than 7 days
            } ?: emptyList()
            
            obsoleteFiles.forEach { file ->
                // Uses the injected handler instead of raw Java IO
                fileHandler.deleteFile(file.absolutePath)
                log.info { "🗑️ Deleted obsolete log: ${file.name}" }
            }
            
            log.info { "✅ Cleanup complete. Released storage from ${obsoleteFiles.size} log files." }
        }
    } catch (e: Exception) {
        log.error { "🚨 Maintenance Daemon Crashed: ${e.message}" }
    }
}
