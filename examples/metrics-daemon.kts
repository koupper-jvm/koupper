/**
 * System Metrics Daemon
 * 
 * Demonstrates Koupper's asynchronous background scheduling capabilities.
 * 
 * Features showcased:
 * - @Export: Exposes the execution loop.
 * - @Scheduled: Registers this script as an asynchronous daemon running independently (e.g. every 10 seconds).
 * - @Logger: Scopes stdout away from the interactive terminal, routing standard logs into a rotating daily file.
 */
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled
import com.koupper.logging.GlobalLogger.log
import java.lang.management.ManagementFactory

@Export
@Logger(destination = "file:metrics-daemon-[yyy-MM-dd]", level = "INFO")
@Scheduled(debug = false, rate = 10000)
val collectMetrics: () -> Unit = {
    val osInfo = ManagementFactory.getOperatingSystemMXBean()
    val memory = Runtime.getRuntime()
    
    val usedMemMb = (memory.totalMemory() - memory.freeMemory()) / (1024 * 1024)
    val totalMemMb = memory.totalMemory() / (1024 * 1024)
    
    // Using Koupper's GlobalLogger instead of println() ensures background tracing
    log.info { "📊 [SYSTEM METRICS] Memory Usage: $usedMemMb MB / $totalMemMb MB | Threads Alive: ${Thread.activeCount()}" }
}
