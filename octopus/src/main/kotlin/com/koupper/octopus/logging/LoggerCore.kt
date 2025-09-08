package com.koupper.octopus.logging

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/* --------- Nivel --------- */
enum class LogLevel(val priority: Int) {
    TRACE(10), DEBUG(20), INFO(30), WARN(40), ERROR(50);
    companion object {
        fun parse(s: String?, default: LogLevel = INFO): LogLevel =
            values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: default
    }
}

/* --------- Evento --------- */
data class LogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val logger: String,
    val message: String,
    val throwable: Throwable? = null,
    val mdc: Map<String, String> = LoggerContext.getMDC(),
    val thread: String = Thread.currentThread().name
)

/* --------- MDC (por hilo) --------- */
object LoggerContext {
    private val mdcTL = ThreadLocal.withInitial { mutableMapOf<String, String>() }
    fun put(key: String, value: String) = mdcTL.get().put(key, value)
    fun remove(key: String) = mdcTL.get().remove(key)
    fun clear() = mdcTL.get().clear()
    fun getMDC(): Map<String, String> = HashMap(mdcTL.get())
}

class ScopedMDC(private val pairs: Map<String, String>) : AutoCloseable {
    init { pairs.forEach { (k, v) -> LoggerContext.put(k, v) } }
    override fun close() { pairs.keys.forEach(LoggerContext::remove) }
}

/* --------- Formateadores --------- */
interface LogFormatter { fun format(e: LogEvent): String }

class PatternFormatter(
    private val pattern: String =
        "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%ex%n"
) : LogFormatter {

    override fun format(e: LogEvent): String {
        var out = pattern

        fun ts(fmt: String): String {
            val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            return sdf.format(Date(e.timestamp))
        }

        out = out.replace("%level", e.level.name)
            .replace("%thread", e.thread)
            .replace("%logger", e.logger)
            .replace("%msg", e.message)
            .replace("%n", System.lineSeparator())

        // %d{...}
        val dRegex = Regex("%d\\{([^}]+)}")
        out = dRegex.replace(out) { m -> ts(m.groupValues[1]) }

        // %ex stacktrace si existe
        out = out.replace("%ex", e.throwable?.let { "\n" + it.stackTraceToString() } ?: "")

        // %mdc imprime como k=v pares
        val mdcText =
            if (e.mdc.isEmpty()) ""
            else e.mdc.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        out = out.replace("%mdc", mdcText)

        return out
    }

}

class JsonFormatter : LogFormatter {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    override fun format(e: LogEvent): String {
        val mdcJson = e.mdc.entries.joinToString(",") { "\"${esc(it.key)}\":\"${esc(it.value)}\"" }
        val ex = e.throwable?.stackTraceToString()?.let { "\"exception\":\"${esc(it)}\"," } ?: ""
        return buildString {
            append('{')
            append("\"ts\":").append(e.timestamp).append(',')
            append("\"level\":\"").append(e.level.name).append("\",")
            append("\"logger\":\"").append(esc(e.logger)).append("\",")
            append("\"thread\":\"").append(esc(e.thread)).append("\",")
            append("\"message\":\"").append(esc(e.message)).append("\",")
            append(ex)
            append("\"mdc\":{").append(mdcJson).append("}")
            append('}')
        }
    }
}

/* --------- Appenders --------- */
interface Appender : AutoCloseable {
    fun append(e: LogEvent)
    override fun close() {}
}

class ConsoleAppender(private val formatter: LogFormatter = PatternFormatter()) : Appender {
    override fun append(e: LogEvent) {
        val line = formatter.format(e)
        // mandamos WARN/ERROR a err, resto a out (no redirigimos System.*)
        if (e.level.priority >= LogLevel.WARN.priority) System.err.print(line) else System.out.print(line)
    }
}

/* Simple rolling diario por fecha en nombre */
class RollingFileAppender(
    private val dir: File,
    private val baseName: String,
    private val formatter: LogFormatter = PatternFormatter()
) : Appender {

    private var currentDate: String = ""
    private var raf: RandomAccessFile? = null
    private val lock = Any()

    init { dir.mkdirs() }

    private fun rotateIfNeeded(ts: Long) {
        val newDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
        if (newDate != currentDate) {
            raf?.close()
            currentDate = newDate
            val file = File(dir, "$baseName.$currentDate.log")
            raf = RandomAccessFile(file, "rw").apply { seek(length()) }
        }
    }

    override fun append(e: LogEvent) {
        synchronized(lock) {
            rotateIfNeeded(e.timestamp)
            raf!!.write(formatter.format(e).toByteArray(Charsets.UTF_8))
        }
    }

    override fun close() { synchronized(lock) { raf?.close(); raf = null } }
}

class AsyncAppender(private val delegate: Appender, capacity: Int = 4096) : Appender {
    private val queue = LinkedBlockingQueue<LogEvent>(capacity)
    private val running = AtomicBoolean(true)
    private val worker = Thread {
        while (running.get() || !queue.isEmpty()) {
            val e = queue.poll()
            if (e != null) delegate.append(e) else Thread.sleep(1)
        }
    }.apply { isDaemon = true; name = "AsyncAppender-${delegate.javaClass.simpleName}"; start() }

    override fun append(e: LogEvent) { queue.offer(e) }
    override fun close() { running.set(false); worker.join(2000); delegate.close() }
}

/* Memoria: captura en StringBuilder (útil para devolver logs) */
class MemoryAppender(private val formatter: LogFormatter = PatternFormatter()) : Appender {
    private val sb = StringBuilder()
    override fun append(e: LogEvent) { sb.append(formatter.format(e)) }
    fun content(): String = sb.toString()
}

/* --------- Filtros --------- */
typealias LogFilter = (LogEvent) -> Boolean

/* --------- Logger --------- */
class Logger(
    private val name: String,
    @Volatile var level: LogLevel = LogLevel.INFO,
    private val appenders: MutableList<Appender> = mutableListOf(Appenders.console()),
    private val filters: MutableList<LogFilter> = mutableListOf()
) {
    fun addAppender(a: Appender): Logger = apply { appenders.add(a) }
    fun addFilter(f: LogFilter): Logger = apply { filters.add(f) }

    private fun emit(level: LogLevel, msg: String, t: Throwable? = null) {
        if (level.priority < this.level.priority) return
        val ev = LogEvent(level = level, logger = name, message = msg, throwable = t)
        if (filters.any { !it(ev) }) return
        appenders.forEach { it.append(ev) }
    }

    fun clearAppenders(close: Boolean = false): Logger = apply {
        if (close) appenders.forEach { it.close() }
        appenders.clear()
    }
    fun appendersSnapshot(): List<Appender> = appenders.toList()
    fun clearFilters(): Logger = apply { filters.clear() }

    fun removeAppender(a: Appender): Logger = apply { appenders.remove(a) }
    fun removeFilter(f: LogFilter): Logger = apply { filters.remove(f) }

    fun trace(msg: () -> String) = emit(LogLevel.TRACE, msg())
    fun debug(msg: () -> String) = emit(LogLevel.DEBUG, msg())
    fun info (msg: () -> String) = emit(LogLevel.INFO , msg())
    fun warn (msg: () -> String) = emit(LogLevel.WARN , msg())
    fun error(msg: () -> String) = emit(LogLevel.ERROR, msg())

    fun error(t: Throwable, msg: () -> String) = emit(LogLevel.ERROR, msg(), t)
}

/* --------- Fábrica/registry y presets --------- */
object Appenders {
    fun console(pattern: String = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%ex%n") =
        ConsoleAppender(PatternFormatter(pattern))

    fun consoleJson() = ConsoleAppender(JsonFormatter())

    fun rollingFile(dir: String = "logs", baseName: String = "app",
                    pattern: String = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%ex%n") =
        RollingFileAppender(File(dir), baseName, PatternFormatter(pattern))

    fun rollingJson(dir: String = "logs", baseName: String = "app") =
        RollingFileAppender(File(dir), baseName, JsonFormatter())
}

object LoggerFactory {
    private val registry = mutableMapOf<String, Logger>()
    @Synchronized fun get(name: String): Logger =
        registry.getOrPut(name) { Logger(name) }
}

/* --------- Especificación dinámica y helpers de ejecución --------- */
data class LogSpec(
    val level: String = "INFO",
    val destination: String = "console",
    val mdc: Map<String, String> = emptyMap(),
    val async: Boolean = true
)

private fun configure(logger: Logger, spec: LogSpec): () -> Unit {
    val prevLevel = logger.level
    val prevAppenders = logger.appendersSnapshot()

    logger.clearFilters()

    logger.clearAppenders(close = false)

    logger.level = LogLevel.parse(spec.level)

    val newAppenders = mutableListOf<Appender>()

    fun addNew(a: Appender) {
        val wrapped = if (spec.async) AsyncAppender(a) else a
        newAppenders.add(wrapped)
        logger.addAppender(wrapped)
    }

    when (spec.destination.lowercase()) {
        "console"      -> addNew(Appenders.console())
        "console-json" -> addNew(Appenders.consoleJson())
        "file"         -> addNew(Appenders.rollingFile())
        "json"         -> addNew(Appenders.rollingJson())
        "both"         -> { addNew(Appenders.console()); addNew(Appenders.rollingFile()) }
        "all"          -> { addNew(Appenders.console()); addNew(Appenders.rollingFile()); addNew(Appenders.rollingJson()) }
        else           -> addNew(Appenders.console())
    }

    return {
        newAppenders.forEach { it.close() }

        logger.clearAppenders(close = false)

        prevAppenders.forEach { logger.addAppender(it) }

        logger.level = prevLevel
    }
}

fun <T> withLogging(
    loggerName: String,
    spec: LogSpec,
    block: (Logger) -> T
): T {
    val logger = LoggerFactory.get(loggerName)
    val restore = configure(logger, spec)
    try {
        ScopedMDC(spec.mdc).use {
            logger.debug { "Entering block with spec=$spec" }
            return block(logger).also {
                logger.debug { "Exiting block (ok)" }
            }
        }
    } catch (t: Throwable) {
        ScopedMDC(spec.mdc).use { logger.error(t) { "Exiting block (failed)" } }
        throw t
    } finally {
        restore()
    }
}

fun <T> captureLogs(
    loggerName: String,
    spec: LogSpec,
    block: (Logger) -> T
): Pair<T, String> {
    val logger = LoggerFactory.get(loggerName)
    val restore = configure(logger, spec)

    val mem = MemoryAppender(
        PatternFormatter("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%ex%n")
    )

    val fileAppender = if (spec.destination.equals("file", ignoreCase = true)) {
        val dir = File(System.getProperty("user.home"), ".koupper/logs")
        dir.mkdirs()
        val exportName = spec.mdc["export"] ?: loggerName
        RollingFileAppender(
            dir = dir,
            baseName = exportName,
            formatter = PatternFormatter("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%ex%n")
        )
    } else null

    val captureFilter: LogFilter = { e ->
        spec.mdc.isEmpty() || spec.mdc.all { (k, v) -> e.mdc[k] == v }
    }

    try {
        logger.addFilter(captureFilter)
        logger.addAppender(mem)
        fileAppender?.let { logger.addAppender(it) }

        val result = ScopedMDC(spec.mdc).use {
            block(logger)
        }
        return result to mem.content()
    } finally {
        logger.removeAppender(mem)
        fileAppender?.let {
            logger.removeAppender(it)
            it.close()
        }
        logger.removeFilter(captureFilter)
        mem.close()
    }
}


