package com.koupper.logging

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

object LoggerHolder {
    lateinit var LOGGER: KLogger

    fun initLogger(context: String) {
        val logSpec = LogSpec(
            context,
            level = "INFO",
            destination = "console",
            mdc = mapOf(
                "LOGGING_LOGS" to UUID.randomUUID().toString(),
                "context" to context
            ),
            async = true
        )

        captureLogs("LoggerHolder.Dispatcher", logSpec) { log ->
            LOGGER = log
            "initialized"
        }
    }
}

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
        if (e.level.priority >= LogLevel.WARN.priority) System.err.print(line) else System.out.print(line)
    }
}

class RollingFileAppender(
    private val dir: File,
    private val baseName: String,
    private val datePattern: String = "yyyy-MM-dd",
    private val formatter: LogFormatter = PatternFormatter()
) : Appender {

    private var currentDate: String = ""
    private var raf: RandomAccessFile? = null
    private val lock = Any()

    init { dir.mkdirs() }

    private fun rotateIfNeeded(ts: Long) {
        val sdf = SimpleDateFormat(datePattern, Locale.US)
        val newDate = sdf.format(Date(ts))
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

interface LoggerCore {
    fun addAppender(a: Appender): LoggerCore
    fun addFilter(f: LogFilter): LoggerCore

    fun clearAppenders(close: Boolean = false): LoggerCore
    fun appendersSnapshot(): List<Appender>
    fun clearFilters(): LoggerCore

    fun removeAppender(a: Appender): LoggerCore
    fun removeFilter(f: LogFilter): LoggerCore

    fun trace(msg: () -> String)
    fun debug(msg: () -> String)
    fun info(msg: () -> String)
    fun warn(msg: () -> String)
    fun error(msg: () -> String)
    fun error(t: Throwable, msg: () -> String)
}

class KLogger(
    private val _name: String,
    @Volatile var level: LogLevel = LogLevel.INFO,
    private val appenders: MutableList<Appender> = mutableListOf(Appenders.console()),
    private val filters: MutableList<LogFilter> = mutableListOf()
) : LoggerCore {
    val name: String
        get() = _name.uppercase()

    override fun addAppender(a: Appender): KLogger = apply { appenders.add(a) }
    override fun addFilter(f: LogFilter): KLogger = apply { filters.add(f) }

    private fun emit(level: LogLevel, msg: String, t: Throwable? = null) {
        if (level.priority < this.level.priority) return
        val ev = LogEvent(level = level, logger = name, message = msg, throwable = t)
        if (filters.any { !it(ev) }) return
        appenders.forEach { it.append(ev) }
    }

    override fun clearAppenders(close: Boolean): KLogger = apply {
        if (close) appenders.forEach { it.close() }
        appenders.clear()
    }
    override fun appendersSnapshot(): List<Appender> = appenders.toList()
    override fun clearFilters(): KLogger = apply { filters.clear() }

    override fun removeAppender(a: Appender): KLogger = apply { appenders.remove(a) }
    override fun removeFilter(f: LogFilter): KLogger = apply { filters.remove(f) }

    override fun trace(msg: () -> String) = emit(LogLevel.TRACE, msg())
    override fun debug(msg: () -> String) = emit(LogLevel.DEBUG, msg())
    override fun info (msg: () -> String) = emit(LogLevel.INFO, msg())
    override fun warn (msg: () -> String) = emit(LogLevel.WARN, msg())
    override fun error(msg: () -> String) = emit(LogLevel.ERROR, msg())

    override fun error(t: Throwable, msg: () -> String) = emit(LogLevel.ERROR, msg(), t)
}

object Appenders {
    fun console(pattern: String = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%ex%n") =
        ConsoleAppender(PatternFormatter(pattern))

    fun consoleJson() = ConsoleAppender(JsonFormatter())

    fun rollingFile(
        dir: String = "logs",
        baseName: String = "app",
        pattern: String = "yyyy-MM-dd",
        linePattern: String = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%ex%n"
    ): Appender {
        return RollingFileAppender(File(dir), baseName, pattern, PatternFormatter(linePattern))
    }

    fun rollingJson(dir: String = "logs", baseName: String = "app") =
        RollingFileAppender(File(dir), baseName, formatter = JsonFormatter())
}

object LoggerFactory {
    private val registry = mutableMapOf<String, KLogger>()
    @Synchronized fun get(name: String): KLogger =
        registry.getOrPut(name) { KLogger(name) }
}

data class LogSpec(
    val context: String,
    val level: String = "INFO",
    val destination: String = "console",
    val mdc: Map<String, String> = emptyMap(),
    val async: Boolean = true
)

private fun configure(kLogger: KLogger, spec: LogSpec): () -> Unit {
    val prevLevel = kLogger.level

    val prevAppenders = kLogger.appendersSnapshot()

    kLogger.clearFilters()

    kLogger.clearAppenders(close = false)

    kLogger.level = LogLevel.parse(spec.level)

    val newAppenders = mutableListOf<Appender>()

    fun addNew(a: Appender) {
        val wrapped = if (spec.async) AsyncAppender(a) else a
        newAppenders.add(wrapped)
        kLogger.addAppender(wrapped)
    }

    when {
        spec.destination.equals("console", ignoreCase = true) ->
            addNew(Appenders.console())

        spec.destination.equals("console-json", ignoreCase = true) ->
            addNew(Appenders.consoleJson())

        spec.destination.equals("file", ignoreCase = true) ->
            addNew(Appenders.rollingFile(dir = spec.context + File.separator + "logs/",baseName = kLogger.name)) // -> logs/app.YYYY-MM-DD.log

        spec.destination.lowercase().startsWith("file:") -> {
            val (base, datePat) = parseFileDestination(spec.destination)

            val baseFile = File(spec.context + File.separator + base)
            val dir = baseFile.parentFile ?: File(".")
            val baseName = baseFile.name

            addNew(Appenders.rollingFile(
                dir = dir.absolutePath,
                baseName = baseName,
                pattern = datePat
            ))
        }


        spec.destination.equals("json", ignoreCase = true) ->
            addNew(Appenders.rollingJson())

        spec.destination.equals("both", ignoreCase = true) -> {
            addNew(Appenders.console())
            addNew(Appenders.rollingFile())
        }

        spec.destination.equals("all", ignoreCase = true) -> {
            addNew(Appenders.console())
            addNew(Appenders.rollingFile())
            addNew(Appenders.rollingJson())
        }

        else -> addNew(Appenders.console())
    }


    return {
        newAppenders.forEach { it.close() }

        kLogger.clearAppenders(close = false)

        prevAppenders.forEach { kLogger.addAppender(it) }

        kLogger.level = prevLevel
    }
}

private fun parseFileDestination(dest: String): Pair<String, String> {
    val rest = dest.substringAfter("file", "")
        .removePrefix(":")
        .trim()

    val hasPattern = rest.contains('[') && rest.contains(']')

    val rawPath = if (hasPattern) {
        rest.substringBefore("[", "").trim()
    } else {
        rest.trim()
    }

    val base = if (rawPath.isBlank()) {
        "app"
    } else if (rawPath.contains('\\') || rawPath.contains('/') || rawPath.contains(':')) {
        rawPath
    } else {
        rawPath.replace(Regex("""[^\w\-.]"""), "_")
    }

    val rawPattern = if (hasPattern) {
        rest.substringAfter("[", "")
            .substringBefore("]", "")
            .trim()
    } else {
        ""
    }

    val pattern = if (rawPattern.isBlank()) "yyyy-MM-dd" else validateDatePattern(rawPattern)

    return base to pattern
}

private fun validateDatePattern(pat: String): String {
    if (canFormatWith(pat)) return pat

    val fixed = pat.replace('m', 'M')
    if (fixed != pat && canFormatWith(fixed)) {
        println("⚠️ Logger: date pattern '$pat' invalid; using '$fixed' (months use 'MM').")
        return fixed
    }

    println("⚠️ Logger: date pattern '$pat' invalid; falling back to 'yyyy-MM-dd'.")
    return "yyyy-MM-dd"
}

private fun canFormatWith(pattern: String): Boolean =
    try {
        SimpleDateFormat(pattern, Locale.US).format(Date())
        true
    } catch (_: IllegalArgumentException) {
        false
    }


fun <T> withLogging(
    loggerName: String,
    spec: LogSpec,
    block: (KLogger) -> T
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
    block: (KLogger) -> T
): Pair<T, String> {
    val logger = LoggerFactory.get(loggerName)

    val restore = configure(logger, spec)

    val mem = MemoryAppender(
        PatternFormatter("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%ex%n")
    )

    val captureFilter: LogFilter = { logEvent ->
        spec.mdc.isEmpty() || spec.mdc.all { (k, v) -> logEvent.mdc[k] == v }
    }

    try {
        logger.addFilter(captureFilter)
        logger.addAppender(mem)

        val result = ScopedMDC(spec.mdc).use {
            block(logger)
        }
        return result to mem.content()
    } finally {
        logger.removeAppender(mem)
        logger.removeFilter(captureFilter)
        mem.close()
        restore()
    }
}


