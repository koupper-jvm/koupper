/**
 * Logger + Scheduled Demo
 *
 * Purpose:
 * - Demonstrate @Logger + @Scheduled behavior for log levels.
 * - Shows explicit log levels and stream mapping:
 *   - log.info/log.debug/log.warn/log.error keep their own level.
 *   - println (stdout) is captured as DEBUG.
 *   - System.err.println (stderr) is captured as ERROR.
 *
 * Run:
 * - koupper run examples/logger-scheduled-demo.kts "hello"
 *
 * Stop:
 * - Ctrl+C in the CLI process or stop the Octopus daemon session.
 */
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.Scheduled

@Export
@Logger(destination = "file:logger-scheduled-demo-[yyyy-MM-dd]", level = "DEBUG")
@Scheduled(debug = true, rate = 5000)
val setup: (String) -> String = { param ->
    val value = if (param == "EMPTY_PARAMS") "demo" else param

    log.info { "[info] processing value='$value'" }
    log.debug { "[debug] internal state ready" }
    log.warn { "[warn] warning example for '$value'" }

    println("[stdout] println example for '$value'")
    System.err.println("[stderr] error stream example for '$value'")

    "logger+scheduled ok"
}
