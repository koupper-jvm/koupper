package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.context
import com.koupper.container.interfaces.Container
import com.koupper.logging.*
import com.koupper.octopus.process.Process
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.providers.files.toType
import com.koupper.providers.http.HtppClient
import com.koupper.shared.octopus.extractExportedAnnotations
import com.koupper.shared.runtime.ScriptingHostBackend
import kotlinx.coroutines.*
import java.io.File
import java.lang.reflect.Method
import java.net.ServerSocket
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
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

    override fun <T> runFromScriptFile(
        context: String,
        scriptPath: String,
        params: String,
        result: (value: T) -> Unit
    ) {
        val content = app.getInstance(FileHandler::class).load(scriptPath).readText(Charsets.UTF_8)

        this.run(context = context, scriptPath, sentence = content, params = this.parseArgs(params)) { process: T ->
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
        result: (value: T) -> Unit
    ) {
        System.setProperty("kotlin.script.classpath", System.getProperty("java.class.path"))

        System.setProperty("idea.use.native.fs.for.win", "false")

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
                sentence = sentence
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

    override fun <T> call(callable: (params: Map<String, Any>) -> T, params: Map<String, Any>): T {
        return callable(params)
    }

    override fun <T> call(callable: () -> T): T {
        return callable()
    }

    override fun call(callable: () -> Unit) {
        callable()
    }

    override fun <T> call(callables: Array<Pair<(Map<String, Any>) -> T, Map<String, Any>>>) {
        runBlocking {
            callables.forEach { (callable, params) ->
                launch(Dispatchers.Default) {
                    callable(params)
                }
            }
        }
    }

    private fun parseArgs(args: String): ParsedParams {
        if (args.isBlank() || args == "EMPTY_PARAMS") return ParsedParams(emptySet(), emptyMap())

        val flags = linkedSetOf<String>()
        val params = linkedMapOf<String, String>()
        val positionals = mutableListOf<String>()

        val tokenRegex = Regex("""(?:"([^"]*)"|'([^']*)'|[^\s,]+)""")
        val tokens = tokenRegex.findAll(args).map { m ->
            m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
        }

        for (tok in tokens) {
            val t = tok.trim()
            if (t.isEmpty()) continue

            if ('=' in t) {
                val (k, v) = t.split('=', limit = 2)
                params[k.trim()] = v.trim()
            } else if (t.startsWith("--") || (t.startsWith("-") && t.length > 1)) {
                flags += t
            } else {
                positionals += t
            }
        }

        return ParsedParams(flags, params, positionals)
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

                if (params.isEmpty()) {
                    this.run(context = context, sentence = scriptContent, params = null) { container: Container ->
                        result(container as T, scriptName)
                    }

                    return@forEach
                }

                this.run(context = context, sentence = scriptContent, params = null) { container: Container ->
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

fun main() = runBlocking {
    val processManager = createDefaultConfiguration()

    launch {
        listenForExternalCommands(processManager)
    }

    while (true) delay(1000)
}

fun listenForExternalCommands(processManager: ScriptExecutor) {
    val serverSocket = ServerSocket(9998)
    println("🔄 Octopus listening on port 9998...")

    while (true) {
        val clientSocket = serverSocket.accept()
        println("🔗 New connection to Octopus: ${clientSocket.inetAddress.hostAddress}")

        CoroutineScope(Dispatchers.IO).launch {
            clientSocket.use {
                try {
                    val reader = it.getInputStream().bufferedReader()
                    val writer = it.getOutputStream().bufferedWriter()
                    val command = reader.readLine()?.trim()

                    if (command.isNullOrBlank() || command == "null") {
                        return@launch
                    }

                    println("📥 Command received in Octopus: $command")

                    val inputData = command.split(" ").toTypedArray()

                    when {
                        inputData[0] == "UPDATING_CHECK" -> {
                            val updateMessage = checkForUpdates()
                            //writer.write(updateMessage + "\n")
                            //writer.flush()
                        }

                        inputData[1].endsWith(".kts") || inputData[1].endsWith(".kt") -> {
                            val scriptPath = inputData[1]

                            val parameters = inputData.drop(2).joinToString(" ")

                            app.createSingletonOf(LoggerCore::class).info { "📜 Executing script: $scriptPath with params: $parameters" }

                            context = inputData[0]

                            processManager.runFromScriptFile(context!!, scriptPath, parameters) { result: Any ->
                                app.createSingletonOf(LoggerCore::class).info { "✅ Result from script execution: $result" }

                                when (result) {
                                    is Unit -> writer.write("")
                                    is String -> writer.write(result)
                                    is Number -> writer.write(result.toString())
                                    is Boolean -> writer.write(result.toString())
                                    else -> writer.write(result.toString())
                                }

                                writer.flush()
                            }
                        }

                        else -> {
                            val errorMsg = "⚠️  Invalid command format: $command"
                            writer.write(errorMsg + "\n")
                            writer.flush()
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ Connection error: ${e.printStackTrace()}")
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

    val logsDir   = File(System.getProperty("user.home"), ".koupper/logs")

    val appLogger = LoggerFactory.get("Octopus.Dispatcher")
    appLogger.clearAppenders(close = true)
    appLogger.level = LogLevel.INFO
    appLogger.addAppender(
        AsyncAppender(
            RollingFileAppender(
                dir = logsDir,
                baseName = "Octopus.Dispatcher"
            )
        )
    )

    app.singleton(LoggerCore::class) {
        appLogger
    }

    return octopus
}
