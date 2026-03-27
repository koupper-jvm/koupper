package com.koupper.octopus

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
import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.providers.files.toType
import com.koupper.providers.http.HtppClient
import com.koupper.providers.io.TerminalContext
import com.koupper.providers.io.TerminalIO
import com.koupper.shared.monitoring.JsonlExecutionMonitor
import com.koupper.shared.octopus.extractExportedAnnotations
import com.koupper.shared.octopus.toCliArgs
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
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
        val (exportedFunctionName, annotations) = extractExportedAnnotations(sentence)
            ?: run {
                result("No function annotated with @Export was found." as T)
                return
            }

        if ("Export" !in annotations) {
            result("No function annotated with @Export was found." as T)
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


            FunctionDispatcher.dispatch<T>(dispatcherInputParams) {
                result(it)
            }
        } catch (e: Throwable) {
            e.printStackTrace(System.out)
            var rootCause = e
            while (rootCause.cause != null) {
                rootCause = rootCause.cause!!
            }
            rootCause.printStackTrace(System.out)

            result("Script error: ${e.message}" as T)
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

        val tokens = tokenize(args)

        for (rawToken in tokens) {
            val token = rawToken.trim()
            if (token.isEmpty()) continue

            when {
                token.startsWith("--") || (token.startsWith("-") && token.length > 1 && !token.startsWith("-{")) -> {
                    flags += token
                }

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
                    result(container as T, scriptName)
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

        return this.container.getBindings() as Map<KClass<*>, Any>
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

fun main() = runBlocking {
    // 1. SILENCE THE DAEMON (Essential for preventing terminal ghosting)
    // We redirect System.out and System.err to dummy streams so background logs never touch the console.
    val nullStream = java.io.PrintStream(object : java.io.OutputStream() { override fun write(b: Int) {} })
    System.setOut(nullStream)
    System.setErr(nullStream)

    val processManager = createDefaultConfiguration()

    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    serverScope.launch {
        listenForExternalCommands(processManager, serverScope)
    }

    while (true) delay(1000)
}


fun listenForExternalCommands(
    processManager: ScriptExecutor,
    scope: CoroutineScope
) {
    val serverSocket = ServerSocket(9998)
    app.createSingleton(LoggerCore::class)
        .info { "🔄 Octopus listening on port 9998..." }

    while (true) {
        val clientSocket = serverSocket.accept()
        app.createSingleton(LoggerCore::class)
            .info { "🔗 New connection to Octopus: ${clientSocket.inetAddress.hostAddress}" }

        scope.launch(Dispatchers.IO) {
            clientSocket.use {
                try {
                    val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    TerminalContext.set(TerminalIO(reader, writer))
                    val command = reader.readLine()?.trim()

                    if (command.isNullOrBlank() || command == "null") return@launch

                    app.createSingleton(LoggerCore::class)
                        .info { "📥 Command received in Octopus: $command" }

                    val inputData = tokenize(command)

                    if (inputData.isEmpty()) return@launch

                    val scriptContext = inputData[0].replace("\"", "")
                    val scriptPath = if (inputData.size > 1) inputData[1].replace("\"", "") else ""

                    when {
                        inputData[0] == "UPDATING_CHECK" -> {
                            checkForUpdates()
                        }

                        inputData.size >= 2 && (scriptPath.endsWith(".kts") || scriptPath.endsWith(".kt")) -> {
                            val parameters = if (inputData.size > 2) {
                                inputData.drop(2).joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
                            } else {
                                "EMPTY_PARAMS"
                            }

                            val originalOut = System.out
                            val customOut = java.io.PrintStream(object : java.io.OutputStream() {
                                private val buffer = java.io.ByteArrayOutputStream()

                                override fun write(b: Int) {
                                    if (b == '\r'.code) return // Strip \r
                                    if (b == '\n'.code) {
                                        flushBuffer()
                                    } else {
                                        buffer.write(b)
                                    }
                                }

                                override fun flush() {
                                    flushBuffer()
                                }

                                private fun flushBuffer() {
                                    if (buffer.size() > 0) {
                                        val text = buffer.toString("UTF-8")
                                        writer.write("PRINT::$text")
                                        writer.newLine()
                                        writer.flush()
                                        buffer.reset()
                                    }
                                }
                            }, true, "UTF-8")
                            System.setOut(customOut)

                            try {
                                app.createSingleton(LoggerCore::class)
                                    .info { "📜 Executing script: $scriptPath with params: $parameters" }

                                processManager.runFromScriptFile(scriptContext, scriptPath, parameters) { result: Any ->
                                    customOut.flush() // Force flush any remaining log buffer

                                    val out = when (result) {
                                        is Unit -> ""
                                        else -> result.toString()
                                    }

                                    writer.write("RESULT_BEGIN\n")
                                    writer.write("${out}\n")
                                    writer.write("RESULT_END\n")
                                    writer.flush()
                                }
                            } finally {
                                customOut.flush()
                                System.setOut(originalOut)
                                it.shutdownOutput()
                            }
                        }

                        else -> {
                            app.createSingleton(LoggerCore::class)
                                .info { "⚠️  Invalid command format: $command" }
                        }
                    }
                } catch (e: Exception) {
                    val traceMessage = e.message ?: e.toString()
                    app.createSingleton(LoggerCore::class)
                        .error { "⚠️ Error: $traceMessage" }
                    try {
                        val writer = it.getOutputStream().bufferedWriter()
                        writer.write("ERROR::$traceMessage\n")
                        writer.flush()
                    } catch (_: Exception) {}
                } finally {
                    TerminalContext.clear()
                }
            }
        }
    }
}

fun checkForUpdates(): Boolean {
    val checkForUpdateUrl = env("CHECK_FOR_UPDATED_URL")

    val httpClient = app.getInstance(HtppClient::class)

    val response = httpClient.get {
        url = checkForUpdateUrl
    }

    data class Versioning(val statusCode: String, val body: String)

    val textJsonParser = app.getInstance(JSONFileHandler::class) as JSONFileHandlerImpl<Versioning>

    textJsonParser.read(response?.asString()!!)

    val versioning: Versioning = textJsonParser.toType()

    textJsonParser.read(versioning.body)

    data class Project(val name: String, val version: String)

    data class Info(val apps: ArrayList<Project>)

    (textJsonParser.toType() as Info).apps.forEach { project ->
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
    app.bind(com.koupper.shared.monitoring.ExecutionMonitor::class, { com.koupper.octopus.monitoring.ResumenArchivosExecutionMonitor() })

    return octopus
}
