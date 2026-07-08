package com.koupper.octopus

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.logging.*
import com.koupper.logging.GlobalLogger
import com.koupper.octopus.process.Process
import com.koupper.orchestrator.KouTask
import com.koupper.orchestrator.ScriptRunner
import com.koupper.orchestrator.asJob
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.toType
import com.koupper.providers.http.HtppClient
import com.koupper.providers.io.TerminalContext
import com.koupper.providers.io.TerminalIO
import com.koupper.shared.monitoring.JsonlExecutionMonitor
import com.koupper.shared.octopus.extractExportedDeclarations
import com.koupper.shared.octopus.extractExportedAnnotations
import com.koupper.shared.octopus.toCliArgs
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import kotlin.reflect.KProperty0
import kotlin.system.exitProcess

fun String.toCamelCase(): String {
    return split(" ").joinToString("") { it.lowercase().replaceFirstChar { it.titlecase() } }
}

val isRelativeScriptFile: (String) -> Boolean = {
    it.matches("^[a-zA-Z0-9_-]+\\.kts$".toRegex())
}

data class ParsedParams(
    val flags: Set<String>, val params: Map<String, String>, val positionals: List<String> = emptyList()
)

@Suppress("UNCHECKED_CAST")
private fun <T> castTo(value: Any?): T = value as T

private fun loggerStreamLevels(annotations: Map<String, Map<String, Any?>>): StreamRoutingConfig {
    val loggerAnnotation = annotations["Logger"].orEmpty()
    val stdoutLevel = LogLevel.parse((loggerAnnotation["stdoutLevel"] as? String), LogLevel.INFO)
    val stderrLevel = LogLevel.parse((loggerAnnotation["stderrLevel"] as? String), LogLevel.ERROR)
    return StreamRoutingConfig(stdout = stdoutLevel, stderr = stderrLevel)
}

class Octopus(private var container: Container) : ScriptExecutor {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()

    init {
        System.setProperty("kotlin.script.classpath", System.getProperty("java.class.path"))

        System.setProperty("idea.use.native.fs.for.win", "false")
    }

    override fun <T> runFromScriptFile(
        context: String, scriptPath: String, params: String, result: (value: T) -> Unit
    ) {
        com.koupper.container.context = context
        com.koupper.os.scriptContext = context

        val resolvedPath = if (File(scriptPath).isAbsolute) {
            scriptPath
        } else {
            File(context, scriptPath).path
        }

        val content = app.getInstance(FileHandler::class).load(resolvedPath).readText(Charsets.UTF_8)

        this.run(
            context = context, scriptPath = resolvedPath, sentence = content, params = this.parseArgs(params)
        ) { process: T ->
            result(process)
        }
    }

    override fun <T> runFromCallback(
        callable: Callable, koTask: KouTask, result: (value: T) -> Unit
    ) {
        com.koupper.container.context = ""
        com.koupper.os.scriptContext = koTask.context

        val content = app.getInstance(FileHandler::class).load(koTask.scriptPath!!).readText(Charsets.UTF_8)

        this.run(
            context = koTask.context!!,
            koTask.scriptPath,
            sentence = content,
            params = this.parseArgs(koTask.params.toCliArgs()),
            callable
        ) { process: T ->
            result(process)
        }
    }

    override fun <T> runFromUrl(context: String, scriptUrl: String, params: String, result: (value: T) -> Unit) {
        if (!isRunFromUrlEnabled()) {
            app.createSingleton(LoggerCore::class).warn {
                "⚠️ Blocked runFromUrl. Enable with -D$OCTOPUS_ENABLE_URL_PROPERTY=true or $OCTOPUS_ENABLE_URL_ENV=true"
            }
            result(castTo<T>("Script URL execution is disabled by default. Enable it explicitly to use runFromUrl."))
            return
        }

        if (!isAllowedScriptUrl(scriptUrl)) {
            app.createSingleton(LoggerCore::class).warn {
                "⚠️ Rejected script URL: $scriptUrl"
            }
            result(castTo<T>("Rejected script URL. Allowed: https, or http://localhost when insecure mode is enabled."))
            return
        }

        val content = URL(scriptUrl).readText()

        this.run(context, sentence = content, params = this.parseArgs(params)) { process: T ->
            result(process)
        }
    }

    override fun <T> run(
        context: String,
        scriptPath: String?,
        sentence: String,
        params: ParsedParams?,
        callable: Callable?,
        result: (value: T) -> Unit,
    ) {
        val exportedDeclarations = extractExportedDeclarations(sentence)
        if (exportedDeclarations.size > 1) {
            val names = exportedDeclarations.joinToString(", ") { it.name }
            result(castTo<T>("[ERR_EXPORT_MULTIPLE] Multiple @Export declarations found: $names. Use exactly one @Export entrypoint (recommended: setup)."))
            return
        }

        val (exportedFunctionName, annotations) = extractExportedAnnotations(sentence) ?: run {
            result(castTo<T>("[ERR_EXPORT_MISSING] No @Export entrypoint found. Add exactly one:\n  @Export\n  val setup: () -> String = { \"hello\" }"))
            return
        }

        if ("Export" !in annotations) {
            result(castTo<T>("No function annotated with @Export was found."))
            return
        }

        try {
            val dispatcherInputParams = DispatcherInputParams(
                scriptContext = context,
                scriptPath = scriptPath,
                annotations = annotations,
                functionName = exportedFunctionName,
                params = params,
                sentence = sentence,
                callable = callable,
                traceId = TraceContext.get()
            )


            val previousLevels = SessionStdoutBridge.currentStreamLevels()
            SessionStdoutBridge.setStreamLevels(loggerStreamLevels(annotations))

            try {
                FunctionDispatcher.dispatch<T>(dispatcherInputParams) {
                    result(it)
                }
            } finally {
                SessionStdoutBridge.setStreamLevels(previousLevels)
            }
        } catch (e: Throwable) {
            if (e is InterruptedException) {
                result(castTo<T>("[ERR_CANCELLED] Script interrupted by cancellation request"))
                return
            }

            GlobalLogger.log.error(e) { "Unhandled error during script execution" }

            var rootCause = e
            while (rootCause.cause != null) {
                rootCause = rootCause.cause!!
            }
            if (rootCause is InterruptedException) {
                result(castTo<T>("[ERR_CANCELLED] Script interrupted by cancellation request"))
                return
            }

            result(castTo<T>("[ERR_COMPILE] Script compilation failed: ${e.message}"))
        }
    }

    override fun <O> call(callable: KProperty0<*>, vararg args: Any?): O {
        val kouTaskInfo = callable.asJob(*args)

        val future = CompletableFuture<Any?>()

        runFromCallback(
            Callable(callable, args), kouTaskInfo
        ) { result: Any? ->
            future.complete(result)
        }

        val result = future.get()

        @Suppress("UNCHECKED_CAST") return result as O
    }

    data class CallExecution<T>(
        val index: Int, val ok: Boolean, val value: T? = null, val error: Throwable? = null, val durationMs: Long
    )

    fun <T> callWithReport(
        callables: Array<Pair<(Map<String, Any>) -> T, Map<String, Any>>>
    ): List<CallExecution<T>> = runBlocking {
        callables.mapIndexed { idx, (callable, params) ->
            async(Dispatchers.Default + SupervisorJob()) {
                val start = System.nanoTime()
                try {
                    val value = callable(params)
                    CallExecution(
                        index = idx, ok = true, value = value, durationMs = (System.nanoTime() - start) / 1_000_000
                    )
                } catch (t: Throwable) {
                    CallExecution(
                        index = idx, ok = false, error = t, durationMs = (System.nanoTime() - start) / 1_000_000
                    )
                }
            }
        }.awaitAll()
    }

    fun parseArgs(args: String): ParsedParams {
        if (args.isBlank() || args == "EMPTY_PARAMS") {
            return ParsedParams(emptySet(), emptyMap(), emptyList())
        }

        val flags = linkedSetOf<String>()
        val params = linkedMapOf<String, String>()
        val positionals = mutableListOf<String>()
        var autoArgIndex = 0

        fun stripOuterQuotes(value: String): String {
            val v = value.trim()
            if (v.length >= 2) {
                val first = v.first()
                val last = v.last()
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return v.substring(1, v.length - 1)
                }
            }
            return v
        }

        fun findEqualsOutsideStructures(token: String): Int {
            var inSingleQuote = false
            var inDoubleQuote = false
            var escape = false
            var braceDepth = 0
            var bracketDepth = 0

            token.forEachIndexed { index, ch ->
                if (escape) {
                    escape = false
                    return@forEachIndexed
                }

                if (ch == '\\') {
                    escape = true
                    return@forEachIndexed
                }

                if (inSingleQuote) {
                    if (ch == '\'') inSingleQuote = false
                    return@forEachIndexed
                }

                if (inDoubleQuote) {
                    if (ch == '"') inDoubleQuote = false
                    return@forEachIndexed
                }

                when (ch) {
                    '\'' -> inSingleQuote = true
                    '"' -> inDoubleQuote = true
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '=' -> {
                        if (braceDepth == 0 && bracketDepth == 0) {
                            return index
                        }
                    }
                }
            }

            return -1
        }

        fun isNegativeNumberLiteral(token: String): Boolean {
            return token.matches(Regex("^-\\d+(\\.\\d+)?([eE][+-]?\\d+)?$"))
        }

        fun isFlagToken(token: String): Boolean {
            if (token.length <= 1 || !token.startsWith("-")) return false
            if (token.startsWith("--")) return token.length > 2
            if (token.startsWith("-{")) return false
            if (isNegativeNumberLiteral(token)) return false

            return token.drop(1).all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }

        val tokens = tokenize(args)

        for (rawToken in tokens) {
            val token = rawToken.trim()
            if (token.isEmpty()) continue

            when {
                findEqualsOutsideStructures(token) >= 0 -> {
                    val eqIndex = findEqualsOutsideStructures(token)
                    val key = token.substring(0, eqIndex).trim()
                    val rawValue = token.substring(eqIndex + 1).trim()
                    val value = stripOuterQuotes(rawValue)

                    val idx = key.removePrefix("arg").toIntOrNull()
                    if (idx != null && idx >= autoArgIndex) {
                        autoArgIndex = idx + 1
                    }

                    params[key] = value
                }

                isFlagToken(token) -> {
                    flags += token
                }

                else -> {
                    val unquoted = stripOuterQuotes(token)

                    positionals += unquoted
                }
            }
        }

        return ParsedParams(flags, params, positionals)
    }

    private fun mapToParsedParams(params: Map<String, Any>): ParsedParams {
        val stringParams = params.mapValues { (_, v) -> v?.toString() ?: "" }
        return ParsedParams(
            flags = emptySet(), params = stringParams, positionals = emptyList()
        )
    }

    override fun <T> runScriptFiles(
        context: String, scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit
    ) {
        scripts.forEach { (scriptPath, params) ->
            if (scriptPath.isNotEmpty()) {
                if (".kts" !in scriptPath) {
                    GlobalLogger.log.error { "The file should be an [kts] extension." }

                    exitProcess(7)
                }

                var finalInitPath = ""

                finalInitPath += if (isRelativeScriptFile(scriptPath)) {
                    Paths.get("").toAbsolutePath().toString() + "/$scriptPath "
                } else {
                    scriptPath
                }.trim()

                val scriptContent = File(finalInitPath).readText(Charsets.UTF_8)

                val scriptName = File(finalInitPath).name

                val parsed = if (params.isEmpty()) null else mapToParsedParams(params)

                this.run(
                    context = context, scriptPath = finalInitPath, sentence = scriptContent, params = parsed
                ) { container: Container ->
                    result(castTo<T>(container), scriptName)
                }
            }
        }
    }

    fun availableServiceProviders(): List<KClass<*>> {
        return this.registeredServiceProviders
    }

    /**
     * Topologically sorts providers so that providers with declared dependencies
     * are initialized after the providers they depend on.
     */
    private fun topologicalSort(
        nodes: List<KClass<*>>, dependencyMap: Map<KClass<*>, Set<KClass<*>>>
    ): List<KClass<*>> {
        if (nodes.isEmpty()) return nodes

        val result = mutableListOf<KClass<*>>()
        val visited = mutableSetOf<KClass<*>>()
        val visiting = mutableSetOf<KClass<*>>()

        fun dfs(node: KClass<*>) {
            if (node in visited) return
            if (node in visiting) return
            visiting.add(node)
            dependencyMap[node]?.forEach { dep -> if (dep in nodes) dfs(dep) }
            visiting.remove(node)
            visited.add(node)
            result.add(node)
        }

        for (node in nodes) {
            if (node !in visited) dfs(node)
        }

        return result
    }

    fun registerBuildInServicesProvidersInContainer(): Map<KClass<*>, Any> {
        val providerInstances = mutableMapOf<KClass<*>, ServiceProvider>()

        for (providerClass in this.registeredServiceProviders) {
            try {
                providerInstances[providerClass] = providerClass.constructors.elementAt(0).call() as ServiceProvider
            } catch (e: Exception) {
                GlobalLogger.log.warn { "[Koupper] Provider ${providerClass.simpleName} instantiation failed: ${e.message}" }
            }
        }

        val dependencyMap = mutableMapOf<KClass<*>, MutableSet<KClass<*>>>()
        for ((providerClass, instance) in providerInstances) {
            val deps = instance.dependencies().filter { it in providerInstances }.map { it as KClass<*> }.toMutableSet()
            dependencyMap[providerClass] = deps
        }

        val sorted = topologicalSort(providerInstances.keys.toList(), dependencyMap)

        val providers = mutableListOf<ServiceProvider>()
        for (providerClass in sorted) {
            val provider = providerInstances[providerClass] ?: continue
            try {
                provider.up()
                providers.add(provider)
            } catch (e: Exception) {
                GlobalLogger.log.warn { "[Koupper] Provider ${providerClass.simpleName}.up() failed: ${e.message}" }
            }
        }

        val allFunctions = providers.flatMap { it.topLevelFunctions().values }.filter { it.isNotBlank() }

        val imports = mutableSetOf<String>()
        val bodies = mutableListOf<String>()

        // Always include the correct @Export import to simplify script authoring
        imports.add("import com.koupper.shared.annotations.Export")

        allFunctions.forEach { block ->
            block.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    imports.add(trimmed)
                } else if (trimmed.isNotEmpty()) {
                    bodies.add("    " + line)
                }
            }
            bodies.add("")
        }

        val namespace = System.getProperty("koupper.scripting.namespace") ?: "koupper"

        // Top-level shortcuts available in every script — no import needed
        val topLevelShortcuts = listOf(
            "val KOUPPER_VERSION = \"$koupperVersion\"",
            "val log  = com.koupper.logging.GlobalLogger.log",
            "val home = System.getProperty(\"user.home\") ?: \"\"",
            "fun env(name: String, default: String = \"\") = com.koupper.os.envOptional(name, default)",
            "fun emit(text: String) = println(text)"
        )

        providerPreamble =
            (imports.sorted() + "" + topLevelShortcuts + "" + "object $namespace {" + bodies + "}").joinToString("\n")

        providerPreambleVersion = koupperVersion

        val typedBindings = mutableMapOf<KClass<*>, Any>()
        this.container.getBindings().forEach { (key, value) ->
            if (key is KClass<*>) {
                typedBindings[key] = value
            }
        }

        return typedBindings
    }

    companion object {
        const val koupperVersion = "7.1.1"
        var providerPreamble: String = ""
        var providerPreambleVersion: String = koupperVersion
    }
}

fun tokenize(input: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()

    var inDoubleQuote = false
    var escape = false
    var braceDepth = 0
    var bracketDepth = 0

    fun flush() {
        val token = current.toString().trim()
        if (token.isNotEmpty()) {
            if (token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                val inner = token.substring(1, token.length - 1)
                tokens.add(inner.replace("\\\"", "\""))
            } else {
                tokens.add(token)
            }
        }
        current.setLength(0)
    }

    for (ch in input) {
        if (escape) {
            current.append(ch)
            escape = false
            continue
        }

        if (ch == '\\') {
            current.append(ch)
            escape = true
            continue
        }

        when (ch) {
            '"' -> {
                inDoubleQuote = !inDoubleQuote
                current.append(ch)
            }

            '{' -> {
                if (!inDoubleQuote) braceDepth++
                current.append(ch)
            }

            '}' -> {
                if (!inDoubleQuote && braceDepth > 0) braceDepth--
                current.append(ch)
            }

            '[' -> {
                if (!inDoubleQuote) bracketDepth++
                current.append(ch)
            }

            ']' -> {
                if (!inDoubleQuote && bracketDepth > 0) bracketDepth--
                current.append(ch)
            }

            ' ', '\t', '\n', '\r' -> {
                if (!inDoubleQuote && braceDepth == 0 && bracketDepth == 0) {
                    flush()
                } else {
                    current.append(ch)
                }
            }

            else -> {
                current.append(ch)
            }
        }
    }

    flush()
    return tokens
}

internal class SessionOutput(private val writer: java.io.BufferedWriter) {
    private val lock = Any()

    fun printLine(
        text: String,
        level: LogLevel = LogLevel.INFO,
        mode: ResponseMode = ResponseMode.LEGACY,
        requestId: String? = null
    ) {
        synchronized(lock) {
            when (mode) {
                ResponseMode.LEGACY -> {
                    val prefix = when {
                        level.priority >= LogLevel.WARN.priority -> "PRINT_ERR::"
                        level.priority <= LogLevel.DEBUG.priority -> "PRINT_DEBUG::"
                        else -> "PRINT::"
                    }
                    writer.write("$prefix$text")
                    writer.newLine()
                    writer.flush()
                }

                ResponseMode.JSON -> {
                    writer.write(
                        daemonResponseJson(
                            type = "print", requestId = requestId, level = level.name, message = text
                        )
                    )
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }

    fun result(out: String, mode: ResponseMode = ResponseMode.LEGACY, requestId: String? = null) {
        synchronized(lock) {
            when (mode) {
                ResponseMode.LEGACY -> {
                    writer.write("RESULT_BEGIN")
                    writer.newLine()
                    writer.write(out)
                    writer.newLine()
                    writer.write("RESULT_END")
                    writer.newLine()
                    writer.flush()
                }

                ResponseMode.JSON -> {
                    writer.write(daemonResponseJson(type = "result", requestId = requestId, result = out))
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }

    fun error(
        message: String, mode: ResponseMode = ResponseMode.LEGACY, requestId: String? = null, errorCode: String? = null
    ) {
        synchronized(lock) {
            when (mode) {
                ResponseMode.LEGACY -> {
                    val prefix = if (errorCode != null) "[$errorCode] " else ""
                    writer.write("ERROR::$prefix$message")
                    writer.newLine()
                    writer.flush()
                }

                ResponseMode.JSON -> {
                    writer.write(
                        daemonResponseJson(
                            type = "error", requestId = requestId, error = message, errorCode = errorCode
                        )
                    )
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }
}

internal object SessionStdoutBridge {
    private val installed = AtomicBoolean(false)
    private val sessionOutput = ThreadLocal<SessionOutput?>()
    private val responseMode = ThreadLocal<ResponseMode?>()
    private val requestId = ThreadLocal<String?>()
    private val stdoutThreadBuffer = ThreadLocal.withInitial { java.io.ByteArrayOutputStream() }
    private val stderrThreadBuffer = ThreadLocal.withInitial { java.io.ByteArrayOutputStream() }
    private val originalOut = System.out
    private val originalErr = System.err
    private val fallback = PrintStream(object : OutputStream() {
        override fun write(b: Int) {}
    })
    private val reentrantGuard = ThreadLocal.withInitial { false }

    fun installOnce() {
        if (!installed.compareAndSet(false, true)) return

        val routingOut = PrintStream(RoutingOutputStream(OutputStreamType.STDOUT), false, Charsets.UTF_8.name())
        val routingErr = PrintStream(RoutingOutputStream(OutputStreamType.STDERR), false, Charsets.UTF_8.name())
        System.setOut(routingOut)
        System.setErr(routingErr)
    }

    fun setStreamLevels(config: StreamRoutingConfig) {
        StreamRoutingContext.set(config)
    }

    fun currentStreamLevels(): StreamRoutingConfig = StreamRoutingContext.get()

    private fun currentLevel(streamType: OutputStreamType): LogLevel {
        val config = StreamRoutingContext.get()
        return if (streamType == OutputStreamType.STDERR) config.stderr else config.stdout
    }

    private fun bufferFor(streamType: OutputStreamType): java.io.ByteArrayOutputStream {
        return if (streamType == OutputStreamType.STDERR) stderrThreadBuffer.get() else stdoutThreadBuffer.get()
    }

    fun bind(output: SessionOutput, mode: ResponseMode = ResponseMode.LEGACY, currentRequestId: String? = null) {
        sessionOutput.set(output)
        responseMode.set(mode)
        requestId.set(currentRequestId)
    }

    fun clear() {
        flushCurrentThreadBuffer(OutputStreamType.STDOUT)
        flushCurrentThreadBuffer(OutputStreamType.STDERR)
        sessionOutput.remove()
        responseMode.remove()
        requestId.remove()
        StreamRoutingContext.clear()
        stdoutThreadBuffer.remove()
        stderrThreadBuffer.remove()
    }

    private fun flushCurrentThreadBuffer(streamType: OutputStreamType) {
        val buffer = bufferFor(streamType)
        if (buffer.size() <= 0) return
        val text = buffer.toString(Charsets.UTF_8.name())
        buffer.reset()
        emit(text, streamType)
    }

    private fun emit(text: String, streamType: OutputStreamType) {
        val redacted = SecretRedactor.redact(text)
        val level = currentLevel(streamType)
        val output = sessionOutput.get()
        if (output != null) {
            output.printLine(redacted, level, responseMode.get() ?: ResponseMode.LEGACY, requestId.get())
        } else {
            if (reentrantGuard.get()) {
                if (streamType == OutputStreamType.STDERR) {
                    originalErr.print(redacted)
                } else {
                    originalOut.print(redacted)
                }
                return
            }

            reentrantGuard.set(true)
            try {
                when (level) {
                    LogLevel.TRACE -> GlobalLogger.log.trace { redacted }
                    LogLevel.DEBUG -> GlobalLogger.log.debug { redacted }
                    LogLevel.INFO -> GlobalLogger.log.info { redacted }
                    LogLevel.WARN -> GlobalLogger.log.warn { redacted }
                    LogLevel.ERROR -> GlobalLogger.log.error { redacted }
                }
            } catch (_: Throwable) {
                fallback.print(redacted)
            } finally {
                reentrantGuard.set(false)
            }
        }
    }

    private class RoutingOutputStream(private val streamType: OutputStreamType) : OutputStream() {
        override fun write(b: Int) {
            if (b == '\r'.code) return

            val buffer = bufferFor(streamType)

            if (b == '\n'.code) {
                if (buffer.size() > 0) {
                    val text = buffer.toString(Charsets.UTF_8.name())
                    buffer.reset()
                    emit(text, streamType)
                }
                return
            }

            buffer.write(b)
        }

        override fun flush() {
            flushCurrentThreadBuffer(streamType)
        }
    }
}
// Daemon bootstrap, protocol parsing, and runtime configuration
// are in OctopusBootstrap.kt and OctopusProtocol.kt

