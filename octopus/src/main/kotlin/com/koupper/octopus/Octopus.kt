package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.Process
import com.koupper.octopus.modules.http.Route
import com.koupper.octopus.process.ScriptProcessor
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.files.*
import com.koupper.providers.http.HtppClient
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.*
import javax.script.ScriptEngineManager
import kotlin.reflect.KClass
import kotlin.system.exitProcess

fun String.toCamelCase(): String {
    return split(" ").joinToString("") { it.lowercase().replaceFirstChar { it.titlecase() } }
}

val isRelativeScriptFile: (String) -> Boolean = {
    it.matches("^[a-zA-Z0-9_-]+\\.kts$".toRegex())
}

class Octopus(private var container: Container) : ScriptExecutor {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()

    override fun <T> runFromScriptFile(scriptPath: String, params: String, result: (value: T) -> Unit) {
        val content = app.getInstance(FileHandler::class).load(scriptPath).readText(Charsets.UTF_8)

        this.run(content, this.convertStringParamsToListParams(params)) { process: T ->
            result(process)
        }
    }

    override fun <T> runFromUrl(scriptUrl: String, params: String, result: (value: T) -> Unit) {
        val content = URL(scriptUrl).readText()

        this.run(content, this.convertStringParamsToListParams(params)) { process: T ->
            result(process)
        }
    }

    override fun <T> run(sentence: String, params: Map<String, Any>, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)
        System.setProperty("idea.use.native.fs.for.win", "false")

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            val exportFunctionName = extractExportFunctionName(sentence)

            if (exportFunctionName != null) {
                val exportFunctionSignature = extractExportFunctionSignature(sentence) ?: ""

                fun <R> captureOutputAndResult(block: () -> R): String {
                    val originalOut = System.out
                    val originalErr = System.err
                    val logFile = File("callback_${System.currentTimeMillis()}.log").apply { createNewFile() }

                    return try {
                        val ps = PrintStream(logFile, StandardCharsets.UTF_8.name()).apply {
                            System.setOut(this)
                            System.setErr(this)
                        }

                        val resultValue = block()

                        ps.println(resultValue)

                        logFile.absolutePath
                    } catch (e: Exception) {
                        e.printStackTrace(PrintStream(logFile, StandardCharsets.UTF_8.name()))
                        logFile.absolutePath
                    } finally {
                        System.setOut(originalOut)
                        System.setErr(originalErr)
                    }
                }

                when {
                    isParameterizable(exportFunctionSignature) -> {
                        eval(sentence)
                        val targetCallback = eval(exportFunctionName) as (Map<String, Any>) -> T

                        val callbackResult = captureOutputAndResult {
                            targetCallback.invoke(params)
                        }
                        result(callbackResult as T)
                    }

                    isScriptProcess(exportFunctionSignature) -> {
                        eval(sentence)

                        val callbackResult = captureOutputAndResult {
                            if (params.isEmpty()) {
                                val targetCallback = eval(exportFunctionName) as (Process) -> T
                                targetCallback.invoke(ScriptProcessor(container))
                            } else {
                                val targetCallback = eval(exportFunctionName) as (Process, Map<String, Any>) -> T
                                targetCallback.invoke(ScriptProcessor(container), params)
                            }
                        }
                        result(callbackResult as T)
                    }

                    isRoute(exportFunctionSignature) -> {
                        eval(sentence)

                        val callbackResult = captureOutputAndResult {
                            if (params.isEmpty()) {
                                val targetCallback = eval(exportFunctionName) as (Route) -> T
                                targetCallback.invoke(Route(container))
                            } else {
                                val targetCallback = eval(exportFunctionName) as (Route, Map<String, Any>) -> T
                                targetCallback.invoke(Route(container), params)
                            }
                        }
                        result(callbackResult as T)
                    }

                    else -> {
                        eval(sentence)
                        val targetCallback = eval(exportFunctionName) as () -> T

                        val callbackResult = captureOutputAndResult {
                            targetCallback.invoke()
                        }
                        result(callbackResult as T)
                    }
                }
            } else {
                println("No se encontró una función anotada con @Export.")
            }
        }
    }

    private fun extractExportFunctionName(script: String): String? {
        val exportPattern = "@Export\\s+val\\s+(\\S+)\\s*:"
        val regex = Regex(exportPattern)

        val matchResult = regex.find(script)
        return matchResult?.groups?.get(1)?.value
    }

    private fun extractExportFunctionSignature(script: String): String? {
        val exportPattern = "@Export\\s+val\\s+\\w+\\s*:\\s*([^=]+)"
        val regex = Regex(exportPattern)

        val matchResult = regex.find(script)
        return matchResult?.groups?.get(1)?.value?.trim()
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

    private fun convertStringParamsToListParams(args: String): Map<String, Any> {
        if (args.isEmpty() || args == "EMPTY_PARAMS") return emptyMap()

        val params = mutableMapOf<String, Any>()

        args.split(",").forEach { arg ->
            val keyValue = arg.split("=")

            val key = keyValue[0]

            val value = keyValue[1]

            params[key] = value
        }

        return params
    }

    override fun <T> runScriptFiles(
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
                    this.run(scriptContent, emptyMap()) { container: Container ->
                        result(container as T, scriptName)
                    }

                    return@forEach
                }

                this.run(scriptContent, params) { container: Container ->
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

    private fun extractFunctionNameWithExportAnnotation(sentence: String): String? {
        val lines = sentence.lines()

        for (i in lines.indices) {
            if (lines[i].trim().startsWith("@Export")) {
                val nextLine = lines.getOrNull(i + 1)?.trim() ?: continue

                if (nextLine.startsWith("val") || nextLine.startsWith("fun")) {
                    return nextLine.split(" ")[1].split(":").first()
                }
            }
        }

        return null
    }
}

fun main() = runBlocking {
    val processManager = createDefaultConfiguration()

    launch { listenForExternalCommands(processManager) }

    while (true) delay(1000)
}

fun listenForExternalCommands(processManager: ScriptExecutor) {
    val serverSocket = ServerSocket(9998)
    println("🔄 Octopus escuchando en el puerto 9998...")

    while (true) {
        val clientSocket = serverSocket.accept()
        println("🔗 Nueva conexión a octopus: ${clientSocket.inetAddress.hostAddress}")

        CoroutineScope(Dispatchers.IO).launch {
            clientSocket.use {
                try {
                    val reader = it.getInputStream().bufferedReader()
                    val writer = it.getOutputStream().bufferedWriter()

                    val command = reader.readLine()?.trim()
                    if (command.isNullOrBlank() || command == "null") {
                        return@launch
                    }

                    println("📥 Comando recibido en octopus: $command")

                    when {
                        command == "UPDATING_CHECK" -> {
                            val updateMessage = checkForUpdates()
                            //writer.write(updateMessage + "\n")
                            //writer.flush()
                        }
                        command.contains(".kts ") -> {
                            val scriptPath = command.substringBefore(".kts ") + ".kts"
                            val parameters = command.substringAfter(".kts ").trim()

                            println("📜 Ejecutando script: $scriptPath con params: $parameters")

                            processManager.runFromScriptFile(scriptPath, parameters) { result: Any ->
                                println("✅ Resultado del script: $result")

                                writer.write(result.toString() + "\n")
                                writer.flush()
                            }
                        }
                        else -> {
                            val errorMsg = "⚠️ Formato inválido en el comando: $command"
                            writer.write(errorMsg + "\n")
                            writer.flush()
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ Error en la conexión: ${e.message}")
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

    return octopus
}
