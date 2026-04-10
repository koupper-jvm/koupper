package com.koupper.octopus

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.logging.*
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
    val flags: Set<String>,
    val params: Map<String, String>,
    val positionals: List<String> = emptyList()
)

@Suppress("UNCHECKED_CAST")
private fun <T> castTo(value: Any?): T = value as T

private const val OCTOPUS_HOST = "127.0.0.1"
private const val OCTOPUS_PORT = 9998
private const val OCTOPUS_HOST_PROPERTY = "koupper.octopus.host"
private const val OCTOPUS_HOST_ENV = "KOUPPER_OCTOPUS_HOST"
private const val OCTOPUS_PORT_PROPERTY = "koupper.octopus.port"
private const val OCTOPUS_PORT_ENV = "KOUPPER_OCTOPUS_PORT"
private const val OCTOPUS_AUTH_PROPERTY = "koupper.octopus.token"
private const val OCTOPUS_AUTH_ENV = "KOUPPER_OCTOPUS_TOKEN"
private const val OCTOPUS_ENABLE_URL_PROPERTY = "koupper.octopus.enableRunFromUrl"
private const val OCTOPUS_ENABLE_URL_ENV = "KOUPPER_ENABLE_RUN_FROM_URL"
private const val OCTOPUS_ALLOW_INSECURE_URL_PROPERTY = "koupper.octopus.allowInsecureRunFromUrl"
private const val OCTOPUS_ALLOW_INSECURE_URL_ENV = "KOUPPER_ALLOW_INSECURE_RUN_FROM_URL"
private const val OCTOPUS_DEPLOY_MAX_BYTES_PROPERTY = "koupper.octopus.deploy.maxBytes"
private const val OCTOPUS_DEPLOY_MAX_BYTES_ENV = "KOUPPER_OCTOPUS_DEPLOY_MAX_BYTES"
private const val OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES = 262144

internal enum class ResponseMode { LEGACY, JSON }

private val daemonMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

internal data class DaemonRequest(
    val type: String? = null,
    val requestId: String? = null,
    val context: String? = null,
    val script: String? = null,
    val params: String? = null,
    val scriptContent: String? = null,
    val contentSha256: String? = null
)

internal data class DaemonResponse(
    val type: String,
    val requestId: String? = null,
    val level: String? = null,
    val message: String? = null,
    val result: String? = null,
    val error: String? = null
)

private enum class OutputStreamType {
    STDOUT,
    STDERR
}

private data class DaemonMetricsSnapshot(
    val uptimeMs: Long,
    val activeConnections: Int,
    val totalConnections: Long,
    val totalCommands: Long,
    val totalScripts: Long,
    val successfulScripts: Long,
    val failedScripts: Long,
    val unauthorizedCommands: Long,
    val invalidCommands: Long
)

internal data class IncomingCommand(
    val mode: ResponseMode,
    val requestId: String? = null,
    val commandType: String,
    val context: String = "",
    val scriptPath: String = "",
    val params: String = "EMPTY_PARAMS",
    val scriptContent: String? = null,
    val contentSha256: String? = null
)

private object DaemonMetrics {
    private val startedAt = System.currentTimeMillis()
    private val activeConnections = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private val totalCommands = AtomicLong(0)
    private val totalScripts = AtomicLong(0)
    private val successfulScripts = AtomicLong(0)
    private val failedScripts = AtomicLong(0)
    private val unauthorizedCommands = AtomicLong(0)
    private val invalidCommands = AtomicLong(0)

    fun onConnectionAccepted() {
        activeConnections.incrementAndGet()
        totalConnections.incrementAndGet()
    }

    fun onConnectionClosed() {
        val current = activeConnections.decrementAndGet()
        if (current < 0) {
            activeConnections.set(0)
        }
    }

    fun onCommandReceived() {
        totalCommands.incrementAndGet()
    }

    fun onUnauthorizedCommand() {
        unauthorizedCommands.incrementAndGet()
    }

    fun onInvalidCommand() {
        invalidCommands.incrementAndGet()
    }

    fun onScriptStarted() {
        totalScripts.incrementAndGet()
    }

    fun onScriptSucceeded() {
        successfulScripts.incrementAndGet()
    }

    fun onScriptFailed() {
        failedScripts.incrementAndGet()
    }

    fun snapshot(): DaemonMetricsSnapshot = DaemonMetricsSnapshot(
        uptimeMs = System.currentTimeMillis() - startedAt,
        activeConnections = activeConnections.get(),
        totalConnections = totalConnections.get(),
        totalCommands = totalCommands.get(),
        totalScripts = totalScripts.get(),
        successfulScripts = successfulScripts.get(),
        failedScripts = failedScripts.get(),
        unauthorizedCommands = unauthorizedCommands.get(),
        invalidCommands = invalidCommands.get()
    )
}

private data class ActiveExecution(
    val requestId: String,
    val thread: Thread,
    val scriptPath: String,
    val startedAt: Long = System.currentTimeMillis()
)

private object ActiveExecutions {
    private val executions = ConcurrentHashMap<String, ActiveExecution>()

    fun register(requestId: String, scriptPath: String) {
        executions[requestId] = ActiveExecution(
            requestId = requestId,
            thread = Thread.currentThread(),
            scriptPath = scriptPath
        )
    }

    fun unregister(requestId: String?) {
        if (requestId.isNullOrBlank()) return
        executions.remove(requestId)
    }

    fun cancel(requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val execution = executions[requestId] ?: return false
        execution.thread.interrupt()
        return true
    }
}

private fun structuredEvent(event: String, fields: Map<String, Any?> = emptyMap()): String {
    val pairs = mutableListOf<String>()
    pairs += jsonField("event", event)
    pairs += jsonField("ts", System.currentTimeMillis().toString())

    fields.forEach { (key, value) ->
        pairs += jsonField(key, value?.toString())
    }

    return "{" + pairs.joinToString(",") + "}"
}

internal fun jsonEscape(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

internal fun jsonField(name: String, value: String?): String {
    return if (value == null) {
        "\"$name\":null"
    } else {
        "\"$name\":\"${jsonEscape(value)}\""
    }
}

internal fun daemonResponseJson(
    type: String,
    requestId: String? = null,
    level: String? = null,
    message: String? = null,
    result: String? = null,
    error: String? = null
): String {
    return "{" + listOf(
        jsonField("type", type),
        jsonField("requestId", requestId),
        jsonField("level", level),
        jsonField("message", message),
        jsonField("result", result),
        jsonField("error", error)
    ).joinToString(",") + "}"
}

private fun loggerStreamLevels(annotations: Map<String, Map<String, Any?>>): StreamRoutingConfig {
    val loggerAnnotation = annotations["Logger"].orEmpty()
    val stdoutLevel = LogLevel.parse((loggerAnnotation["stdoutLevel"] as? String), LogLevel.INFO)
    val stderrLevel = LogLevel.parse((loggerAnnotation["stderrLevel"] as? String), LogLevel.ERROR)
    return StreamRoutingConfig(stdout = stdoutLevel, stderr = stderrLevel)
}

internal fun parseJsonCommand(input: String): DaemonRequest? {
    return runCatching {
        daemonMapper.readValue(input, DaemonRequest::class.java)
    }.getOrNull()
}

private fun optionalRuntimeSetting(propertyName: String, envName: String): String? {
    val fromProperty = System.getProperty(propertyName)?.trim()
    if (!fromProperty.isNullOrBlank()) return fromProperty

    val fromEnv = System.getenv(envName)?.trim()
    if (!fromEnv.isNullOrBlank()) return fromEnv

    return null
}

private fun runtimeFlag(propertyName: String, envName: String, default: Boolean): Boolean {
    val raw = optionalRuntimeSetting(propertyName, envName) ?: return default
    return raw.equals("true", ignoreCase = true) || raw == "1" || raw.equals("yes", ignoreCase = true)
}

private fun runtimeOctopusToken(): String? = optionalRuntimeSetting(OCTOPUS_AUTH_PROPERTY, OCTOPUS_AUTH_ENV)

private fun runtimeOctopusHost(): String =
    optionalRuntimeSetting(OCTOPUS_HOST_PROPERTY, OCTOPUS_HOST_ENV) ?: OCTOPUS_HOST

private fun runtimeOctopusPort(): Int =
    optionalRuntimeSetting(OCTOPUS_PORT_PROPERTY, OCTOPUS_PORT_ENV)?.toIntOrNull() ?: OCTOPUS_PORT

private fun isRunFromUrlEnabled(): Boolean =
    runtimeFlag(OCTOPUS_ENABLE_URL_PROPERTY, OCTOPUS_ENABLE_URL_ENV, default = false)

private fun isInsecureRunFromUrlAllowed(): Boolean =
    runtimeFlag(OCTOPUS_ALLOW_INSECURE_URL_PROPERTY, OCTOPUS_ALLOW_INSECURE_URL_ENV, default = false)

private fun runtimeDeployMaxBytes(): Int {
    val value = optionalRuntimeSetting(OCTOPUS_DEPLOY_MAX_BYTES_PROPERTY, OCTOPUS_DEPLOY_MAX_BYTES_ENV)
        ?.toIntOrNull()
        ?: OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES
    return if (value <= 0) OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES else value
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private val deployScriptNameRegex = Regex("^[A-Za-z0-9._-]+\\.(kts|kt)$")

private fun isAllowedScriptUrl(scriptUrl: String): Boolean {
    val uri = runCatching { URI(scriptUrl) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false

    if (scheme == "https") return true

    if (!isInsecureRunFromUrlAllowed()) return false

    if (scheme != "http") return false

    val host = uri.host?.lowercase() ?: return false
    return host == "127.0.0.1" || host == "localhost"
}

private fun parseAuthenticatedCommand(
    firstLine: String,
    reader: java.io.BufferedReader,
    requiredToken: String?
): Pair<Boolean, String?> {
    val trimmed = firstLine.trim()
    val tokenIsRequired = !requiredToken.isNullOrBlank()

    if (trimmed.startsWith("AUTH::")) {
        val providedToken = trimmed.removePrefix("AUTH::").trim()
        if (!tokenIsRequired) {
            return true to reader.readLine()?.trim()
        }

        if (providedToken == requiredToken) {
            return true to reader.readLine()?.trim()
        }

        return false to null
    }

    if (tokenIsRequired) {
        return false to null
    }

    return true to trimmed
}

internal fun parseIncomingCommand(command: String): IncomingCommand? {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("{")) {
        val req = parseJsonCommand(trimmed) ?: return null
        val type = req.type?.trim()?.uppercase() ?: "RUN"

        if (type == "UPDATING_CHECK") {
            return IncomingCommand(
                mode = ResponseMode.JSON,
                requestId = req.requestId,
                commandType = "UPDATING_CHECK"
            )
        }

        if (type == "HEALTH" || type == "HEALTH_CHECK") {
            return IncomingCommand(
                mode = ResponseMode.JSON,
                requestId = req.requestId,
                commandType = "HEALTH_CHECK"
            )
        }

        if (type == "DEPLOY") {
            return IncomingCommand(
                mode = ResponseMode.JSON,
                requestId = req.requestId,
                commandType = "DEPLOY",
                context = req.context?.trim().orEmpty(),
                scriptPath = req.script?.trim().orEmpty(),
                params = req.params?.trim().takeUnless { it.isNullOrBlank() } ?: "EMPTY_PARAMS",
                scriptContent = req.scriptContent,
                contentSha256 = req.contentSha256?.trim()
            )
        }

        if (type == "CANCEL") {
            return IncomingCommand(
                mode = ResponseMode.JSON,
                requestId = req.requestId,
                commandType = "CANCEL"
            )
        }

        return IncomingCommand(
            mode = ResponseMode.JSON,
            requestId = req.requestId,
            commandType = "RUN",
            context = req.context?.trim().orEmpty(),
            scriptPath = req.script?.trim().orEmpty(),
            params = req.params?.trim().takeUnless { it.isNullOrBlank() } ?: "EMPTY_PARAMS"
        )
    }

    val inputData = tokenize(trimmed)
    if (inputData.isEmpty()) return null

    if (inputData[0] == "UPDATING_CHECK") {
        return IncomingCommand(
            mode = ResponseMode.LEGACY,
            commandType = "UPDATING_CHECK"
        )
    }

    if (inputData[0] == "HEALTH_CHECK") {
        return IncomingCommand(
            mode = ResponseMode.LEGACY,
            commandType = "HEALTH_CHECK"
        )
    }

    val scriptContext = inputData[0].replace("\"", "")
    val scriptPath = if (inputData.size > 1) inputData[1].replace("\"", "") else ""
    val parameters = if (inputData.size > 2) {
        inputData.drop(2).joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
    } else {
        "EMPTY_PARAMS"
    }

    return IncomingCommand(
        mode = ResponseMode.LEGACY,
        commandType = "RUN",
        context = scriptContext,
        scriptPath = scriptPath,
        params = parameters
    )
}

class Octopus(private var container: Container) : ScriptExecutor {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()

    init {
        System.setProperty("kotlin.script.classpath", System.getProperty("java.class.path"))

        System.setProperty("idea.use.native.fs.for.win", "false")
    }

    override fun <T> runFromScriptFile(
        context: String,
        scriptPath: String,
        params: String,
        result: (value: T) -> Unit
    ) {
        com.koupper.container.context = context

        val resolvedPath = if (File(scriptPath).isAbsolute) {
            scriptPath
        } else {
            File(context, scriptPath).path
        }

        val content = app.getInstance(FileHandler::class)
            .load(resolvedPath)
            .readText(Charsets.UTF_8)

        this.run(
            context = context,
            scriptPath = resolvedPath,
            sentence = content,
            params = this.parseArgs(params)
        ) { process: T ->
            result(process)
        }
    }

    override fun <T> runFromCallback(
        callable: Callable,
        koTask: KouTask,
        result: (value: T) -> Unit
    ) {
        com.koupper.container.context = ""

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
            result(castTo<T>("Multiple @Export declarations found: $names. Use exactly one @Export entrypoint (recommended: setup)."))
            return
        }

        val (exportedFunctionName, annotations) = extractExportedAnnotations(sentence)
            ?: run {
                result(castTo<T>("No function annotated with @Export was found."))
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
                callable = callable
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
                result(castTo<T>("Script interrupted by cancellation request"))
                return
            }
            e.printStackTrace(System.out)
            var rootCause = e
            while (rootCause.cause != null) {
                rootCause = rootCause.cause!!
            }
            if (rootCause is InterruptedException) {
                result(castTo<T>("Script interrupted by cancellation request"))
                return
            }
            rootCause.printStackTrace(System.out)

            result(castTo<T>("Script error: ${e.message}"))
        }
    }

    override fun <O> call(callable: KProperty0<*>, vararg args: Any?): O {
        val kouTaskInfo = callable.asJob(*args)

        val future = CompletableFuture<Any?>()

        runFromCallback(
            Callable(callable, args),
            kouTaskInfo
        ) { result: Any? ->
            future.complete(result)
        }

        val result = future.get()

        @Suppress("UNCHECKED_CAST")
        return result as O
    }

    data class CallExecution<T>(
        val index: Int,
        val ok: Boolean,
        val value: T? = null,
        val error: Throwable? = null,
        val durationMs: Long
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
                        index = idx,
                        ok = true,
                        value = value,
                        durationMs = (System.nanoTime() - start) / 1_000_000
                    )
                } catch (t: Throwable) {
                    CallExecution(
                        index = idx,
                        ok = false,
                        error = t,
                        durationMs = (System.nanoTime() - start) / 1_000_000
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
            flags = emptySet(),
            params = stringParams,
            positionals = emptyList()
        )
    }

    override fun <T> runScriptFiles(
        context: String,
        scripts: MutableMap<String, Map<String, Any>>,
        result: (value: T, scriptName: String) -> Unit
    ) {
        scripts.forEach { (scriptPath, params) ->
            if (scriptPath.isNotEmpty()) {
                if (".kts" !in scriptPath) {
                    println("\n\u001B[31m The file should be an [kts] extension.\n")

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
                    context = context,
                    scriptPath = finalInitPath,
                    sentence = scriptContent,
                    params = parsed
                ) { container: Container ->
                    result(castTo<T>(container), scriptName)
                }
            }
        }
    }

    fun availableServiceProviders(): List<KClass<*>> {
        return this.registeredServiceProviders
    }

    fun registerBuildInServicesProvidersInContainer(): Map<KClass<*>, Any> {
        this.registeredServiceProviders.forEach { provider ->
            ((provider).constructors.elementAt(0).call() as ServiceProvider).up()
        }

        val typedBindings = mutableMapOf<KClass<*>, Any>()
        this.container.getBindings().forEach { (key, value) ->
            if (key is KClass<*>) {
                typedBindings[key] = value
            }
        }

        return typedBindings
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

private class SessionOutput(private val writer: java.io.BufferedWriter) {
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
                            type = "print",
                            requestId = requestId,
                            level = level.name,
                            message = text
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

    fun error(message: String, mode: ResponseMode = ResponseMode.LEGACY, requestId: String? = null) {
        synchronized(lock) {
            when (mode) {
                ResponseMode.LEGACY -> {
                    writer.write("ERROR::$message")
                    writer.newLine()
                    writer.flush()
                }

                ResponseMode.JSON -> {
                    writer.write(daemonResponseJson(type = "error", requestId = requestId, error = message))
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }
}

private object SessionStdoutBridge {
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

        val routingOut = PrintStream(RoutingOutputStream(OutputStreamType.STDOUT), true, Charsets.UTF_8.name())
        val routingErr = PrintStream(RoutingOutputStream(OutputStreamType.STDERR), true, Charsets.UTF_8.name())
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
        val level = currentLevel(streamType)
        val output = sessionOutput.get()
        if (output != null) {
            output.printLine(text, level, responseMode.get() ?: ResponseMode.LEGACY, requestId.get())
        } else {
            if (reentrantGuard.get()) {
                if (streamType == OutputStreamType.STDERR) {
                    originalErr.print(text)
                } else {
                    originalOut.print(text)
                }
                return
            }

            reentrantGuard.set(true)
            try {
                when (level) {
                    LogLevel.TRACE -> GlobalLogger.log.trace { text }
                    LogLevel.DEBUG -> GlobalLogger.log.debug { text }
                    LogLevel.INFO -> GlobalLogger.log.info { text }
                    LogLevel.WARN -> GlobalLogger.log.warn { text }
                    LogLevel.ERROR -> GlobalLogger.log.error { text }
                }
            } catch (_: Throwable) {
                fallback.print(text)
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

fun main() = runBlocking {
    SessionStdoutBridge.installOnce()

    val processManager = createDefaultConfiguration()

    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    serverScope.launch {
        listenForExternalCommands(processManager, serverScope)
    }

    while (true) delay(1000)
}


fun listenForExternalCommands(
    processManager: ScriptExecutor,
    scope: CoroutineScope,
    maxConnections: Int? = null
) {
    val host = runtimeOctopusHost()
    val port = runtimeOctopusPort()
    val serverSocket = ServerSocket(port, 50, InetAddress.getByName(host))
    val authEnabled = !runtimeOctopusToken().isNullOrBlank()
    app.createSingleton(LoggerCore::class)
        .info { "🔄 Octopus listening on $host:$port (auth=${if (authEnabled) "enabled" else "disabled"})" }

    var acceptedConnections = 0

    try {
        while (maxConnections == null || acceptedConnections < maxConnections) {
            val clientSocket = serverSocket.accept()
            acceptedConnections++
            DaemonMetrics.onConnectionAccepted()
            app.createSingleton(LoggerCore::class)
                .info { "🔗 New connection to Octopus: ${clientSocket.inetAddress.hostAddress}" }

            scope.launch(Dispatchers.IO) {
                clientSocket.use {
                try {
                    val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    val sessionOutput = SessionOutput(writer)
                    val sessionId = java.util.UUID.randomUUID().toString().take(8)
                    val sessionLogger = app.createSingleton(LoggerCore::class)
                    TerminalContext.set(TerminalIO(reader, writer))
                    SessionStdoutBridge.bind(sessionOutput, ResponseMode.LEGACY, null)
                    val firstLine = reader.readLine()?.trim()

                    if (firstLine.isNullOrBlank() || firstLine == "null") return@launch

                    val requiredToken = runtimeOctopusToken()
                    val (authenticated, command) = parseAuthenticatedCommand(firstLine, reader, requiredToken)

                    if (!authenticated) {
                        DaemonMetrics.onUnauthorizedCommand()
                        sessionLogger.warn { "⚠️ [session=$sessionId] Unauthorized command rejected." }
                        sessionLogger.warn {
                            structuredEvent(
                                event = "octopus.auth.rejected",
                                fields = mapOf(
                                    "sessionId" to sessionId,
                                    "remoteAddress" to it.inetAddress.hostAddress
                                )
                            )
                        }
                        sessionOutput.error("Unauthorized: invalid or missing token")
                        return@launch
                    }

                    if (command.isNullOrBlank() || command == "null") return@launch

                    DaemonMetrics.onCommandReceived()

                    sessionLogger.info { "📥 [session=$sessionId] Command received in Octopus: $command" }

                    val parsedCommand = parseIncomingCommand(command)
                    if (parsedCommand == null) {
                        DaemonMetrics.onInvalidCommand()
                        sessionLogger.warn {
                            structuredEvent(
                                event = "octopus.command.invalid",
                                fields = mapOf(
                                    "sessionId" to sessionId,
                                    "payload" to command
                                )
                            )
                        }
                        sessionOutput.error("Invalid command format")
                        return@launch
                    }

                    SessionStdoutBridge.bind(sessionOutput, parsedCommand.mode, parsedCommand.requestId)

                    when {
                        parsedCommand.commandType == "CANCEL" -> {
                            val cancelled = ActiveExecutions.cancel(parsedCommand.requestId)
                            sessionOutput.result(
                                "{\"ok\":${if (cancelled) "true" else "false"},\"requestId\":\"${parsedCommand.requestId ?: ""}\",\"cancelled\":${if (cancelled) "true" else "false"}}",
                                parsedCommand.mode,
                                parsedCommand.requestId
                            )
                        }

                        parsedCommand.commandType == "UPDATING_CHECK" -> {
                            checkForUpdates()
                        }

                        parsedCommand.commandType == "HEALTH_CHECK" -> {
                            val snapshot = DaemonMetrics.snapshot()
                            val health = "{" + listOf(
                                jsonField("status", "ok"),
                                jsonField("uptimeMs", snapshot.uptimeMs.toString()),
                                jsonField("activeConnections", snapshot.activeConnections.toString()),
                                jsonField("totalConnections", snapshot.totalConnections.toString()),
                                jsonField("totalCommands", snapshot.totalCommands.toString()),
                                jsonField("totalScripts", snapshot.totalScripts.toString()),
                                jsonField("successfulScripts", snapshot.successfulScripts.toString()),
                                jsonField("failedScripts", snapshot.failedScripts.toString()),
                                jsonField("unauthorizedCommands", snapshot.unauthorizedCommands.toString()),
                                jsonField("invalidCommands", snapshot.invalidCommands.toString())
                            ).joinToString(",") + "}"

                            sessionLogger.info {
                                structuredEvent(
                                    event = "octopus.health.request",
                                    fields = mapOf(
                                        "sessionId" to sessionId,
                                        "requestId" to parsedCommand.requestId,
                                        "mode" to parsedCommand.mode.name.lowercase()
                                    )
                                )
                            }

                            sessionOutput.result(health, parsedCommand.mode, parsedCommand.requestId)
                        }

                        parsedCommand.commandType == "DEPLOY" -> {
                            if (requiredToken.isNullOrBlank()) {
                                sessionOutput.error(
                                    "DEPLOY requires daemon auth token configuration",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }

                            val deployContent = parsedCommand.scriptContent
                            if (deployContent.isNullOrBlank()) {
                                sessionOutput.error(
                                    "DEPLOY payload is missing scriptContent",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }

                            val deployBytes = deployContent.toByteArray(Charsets.UTF_8)
                            val maxDeployBytes = runtimeDeployMaxBytes()
                            if (deployBytes.size > maxDeployBytes) {
                                sessionOutput.error(
                                    "DEPLOY payload exceeds max size ($maxDeployBytes bytes)",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }

                            val providedSha256 = parsedCommand.contentSha256?.lowercase()
                            if (providedSha256.isNullOrBlank()) {
                                sessionOutput.error(
                                    "DEPLOY payload is missing contentSha256",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }

                            val calculatedSha256 = sha256Hex(deployBytes)
                            if (providedSha256 != calculatedSha256) {
                                sessionOutput.error(
                                    "DEPLOY payload hash mismatch",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }

                            val deployDir = File(System.getProperty("user.home"), ".koupper/deployed")
                            deployDir.mkdirs()
                            val targetFileName = parsedCommand.scriptPath.substringAfterLast("/").substringAfterLast("\\")
                                .ifBlank { "deployed.kts" }
                            if (!deployScriptNameRegex.matches(targetFileName)) {
                                sessionOutput.error(
                                    "DEPLOY script name must be a safe .kts/.kt filename",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                                return@launch
                            }
                            val targetFile = File(deployDir, targetFileName)
                            targetFile.writeBytes(deployBytes)

                            DaemonMetrics.onScriptStarted()
                            val deployStartedAt = System.nanoTime()

                            sessionLogger.info {
                                "📦 [session=$sessionId] Deploying script: $targetFileName params=${parsedCommand.params}"
                            }

                            try {
                                processManager.runFromScriptFile(
                                    deployDir.absolutePath,
                                    targetFile.name,
                                    parsedCommand.params
                                ) { result: Any ->
                                    System.out.flush()
                                    val out = if (result is Unit) "" else result.toString()
                                    DaemonMetrics.onScriptSucceeded()
                                    val durationMs = (System.nanoTime() - deployStartedAt) / 1_000_000
                                    sessionLogger.info {
                                        structuredEvent(
                                            event = "octopus.deploy.completed",
                                            fields = mapOf(
                                                "sessionId" to sessionId,
                                                "requestId" to parsedCommand.requestId,
                                                "script" to targetFileName,
                                                "sizeBytes" to deployBytes.size,
                                                "sha256" to calculatedSha256,
                                                "durationMs" to durationMs,
                                                "status" to "ok"
                                            )
                                        )
                                    }
                                    sessionOutput.result(out, parsedCommand.mode, parsedCommand.requestId)
                                }
                            } catch (e: Exception) {
                                DaemonMetrics.onScriptFailed()
                                val durationMs = (System.nanoTime() - deployStartedAt) / 1_000_000
                                sessionLogger.error {
                                    structuredEvent(
                                        event = "octopus.deploy.failed",
                                        fields = mapOf(
                                            "sessionId" to sessionId,
                                            "requestId" to parsedCommand.requestId,
                                            "script" to targetFileName,
                                            "sizeBytes" to deployBytes.size,
                                            "sha256" to calculatedSha256,
                                            "durationMs" to durationMs,
                                            "status" to "error",
                                            "error" to (e.message ?: e.javaClass.name)
                                        )
                                    )
                                }
                                sessionOutput.error(
                                    e.message ?: "Deploy execution failed",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                            } finally {
                                System.out.flush()
                                it.shutdownOutput()
                            }
                        }

                        parsedCommand.scriptPath.endsWith(".kts") || parsedCommand.scriptPath.endsWith(".kt") -> {
                            DaemonMetrics.onScriptStarted()
                            val startedAt = System.nanoTime()
                            val requestId = parsedCommand.requestId
                            if (!requestId.isNullOrBlank()) {
                                ActiveExecutions.register(requestId, parsedCommand.scriptPath)
                            }

                            try {
                                sessionLogger.info {
                                    "📜 [session=$sessionId] Executing script: ${parsedCommand.scriptPath} with params: ${parsedCommand.params}"
                                }

                                processManager.runFromScriptFile(
                                    parsedCommand.context,
                                    parsedCommand.scriptPath,
                                    parsedCommand.params
                                ) { result: Any ->
                                    System.out.flush()

                                    val out = when (result) {
                                        is Unit -> ""
                                        else -> result.toString()
                                    }

                                    DaemonMetrics.onScriptSucceeded()
                                    val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                                    sessionLogger.info {
                                        structuredEvent(
                                            event = "octopus.script.completed",
                                            fields = mapOf(
                                                "sessionId" to sessionId,
                                                "requestId" to parsedCommand.requestId,
                                                "script" to parsedCommand.scriptPath,
                                                "durationMs" to durationMs,
                                                "status" to "ok"
                                            )
                                        )
                                    }

                                    sessionOutput.result(out, parsedCommand.mode, parsedCommand.requestId)
                                }
                            } catch (e: Exception) {
                                DaemonMetrics.onScriptFailed()
                                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                                sessionLogger.error {
                                    structuredEvent(
                                        event = "octopus.script.failed",
                                        fields = mapOf(
                                            "sessionId" to sessionId,
                                            "requestId" to parsedCommand.requestId,
                                            "script" to parsedCommand.scriptPath,
                                            "durationMs" to durationMs,
                                            "status" to "error",
                                            "error" to (e.message ?: e.javaClass.name)
                                        )
                                    )
                                }
                                sessionOutput.error(e.message ?: "Script execution failed", parsedCommand.mode, parsedCommand.requestId)
                            } finally {
                                ActiveExecutions.unregister(requestId)
                                System.out.flush()
                                it.shutdownOutput()
                            }
                        }

                        else -> {
                            DaemonMetrics.onInvalidCommand()
                            app.createSingleton(LoggerCore::class)
                                .info { "⚠️  Invalid command format: $command" }
                        }
                    }
                } catch (e: Exception) {
                    val traceMessage = e.message ?: e.toString()
                    app.createSingleton(LoggerCore::class)
                        .error { "⚠️ Error: $traceMessage" }
                    try {
                        val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        SessionOutput(writer).error(traceMessage, ResponseMode.LEGACY, null)
                    } catch (_: Exception) {}
                } finally {
                    SessionStdoutBridge.clear()
                    TerminalContext.clear()
                    DaemonMetrics.onConnectionClosed()
                }
                }
            }
        }
    } finally {
        runCatching { serverSocket.close() }
    }
}

fun checkForUpdates(): Boolean {
    val checkForUpdateUrl = env("CHECK_FOR_UPDATED_URL")

    val httpClient = app.getInstance(HtppClient::class)

    val response = httpClient.get {
        url = checkForUpdateUrl
    }

    data class Versioning(val statusCode: String, val body: String)

    val textJsonParser = app.getInstance(JSONFileHandler::class)

    textJsonParser.read(response?.asString()!!)

    val versioning: Versioning = textJsonParser.toType()

    textJsonParser.read(versioning.body)

    data class Project(val name: String, val version: String)

    data class Info(val apps: ArrayList<Project>)

    val info: Info = textJsonParser.toType()

    info.apps.forEach { project ->
        if ((project.name == "octopus" && project.version != env("OCTOPUS_VERSION")) ||
            (project.name == "koupper-installer" && project.version != env("KOUPPER_CLI_VERSION"))
        ) {
            print("AVAILABLE_UPDATES")

            exitProcess(0)
        }
    }

    return false
}

private fun processCallback(context: ScriptExecutor, scriptName: String, result: Any) {
    if (isPrimitiveType(result) || result is String) {
        val logFile = File(System.getProperty("java.io.tmpdir"), "octopus.log")

        logFile.writeText(
            when (result) {
                is String -> result
                is Process -> "\n\rModule ${ANSI_GREEN_155}${result.processName()}$ANSI_RESET created.\u001B[0m\n"
                else -> result.toString()
            }
        )
    } else if (result is Process) {
        println("\n\rModule ${ANSI_GREEN_155}${result.processName()}$ANSI_RESET created.\u001B[0m\n")
    }
}

fun isPrimitiveType(value: Any): Boolean {
    return when (value) {
        is Int, is Double, is Float, is Long, is Short, is Byte, is Boolean, is Char -> true
        else -> false
    }
}

fun createDefaultConfiguration(container: Container = app): ScriptExecutor {
    val octopus = Octopus(container)
    octopus.registerBuildInServicesProvidersInContainer()

    val logsDir = File(System.getProperty("user.home"), ".koupper/logs")

    val appLogger = LoggerFactory.get("Octopus.Main")
    appLogger.clearAppenders(close = true)
    appLogger.level = LogLevel.INFO

    appLogger.addAppender(
        AsyncAppender(
            RollingFileAppender(
                dir = logsDir,
                baseName = "octopus-system"
            )
        )
    )

    app.singleton(LoggerCore::class, { appLogger })
    app.bind(ScriptExecutor::class, { octopus })
    app.bind(com.koupper.shared.monitoring.ExecutionMonitor::class, {
        com.koupper.octopus.monitoring.CompositeExecutionMonitor(
            delegates = listOf(
                JsonlExecutionMonitor(File(logsDir, "octopus-executions.jsonl")),
                com.koupper.octopus.monitoring.ResumenArchivosExecutionMonitor()
            )
        )
    })
    ScriptRunner.monitor = app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class)

    return octopus
}
