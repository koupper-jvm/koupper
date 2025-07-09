package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.context
import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.Process
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.files.*
import com.koupper.providers.http.HtppClient
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.URL
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

    companion object {
        fun extractExportFunctionName(scriptContent: String): String? {
            val exportPattern = "@Export\\s+val\\s+(\\S+)\\s*:"

            val regex = Regex(exportPattern)

            val matchResult = regex.find(scriptContent)

            return matchResult?.groups?.get(1)?.value
        }

        fun extractExportFunctionSignature(scriptContent: String): Pair<List<String>, String>? {
            val exportPattern = "@Export\\s+val\\s+\\w+\\s*:\\s*\\(([^)]*)\\)\\s*->\\s*([\\w<>,()\\s]+)"

            val regex = Regex(exportPattern)
            val matchResult = regex.find(scriptContent)

            return if (matchResult != null) {
                val parameters = matchResult.groups[1]?.value?.trim() ?: ""
                val returnType = matchResult.groups[2]?.value?.trim() ?: ""

                val parameterList = parseParameters(parameters)

                Pair(parameterList, returnType)
            } else {
                null
            }
        }

        private fun parseParameters(parameters: String): List<String> {
            val paramList = mutableListOf<String>()
            val currentParam = StringBuilder()

            var angleBrackets = 0
            var parentheses = 0
            var squareBrackets = 0

            for (char in parameters) {
                when (char) {
                    '<' -> {
                        angleBrackets++
                        currentParam.append(char)
                    }
                    '>' -> {
                        angleBrackets--
                        currentParam.append(char)
                    }
                    '(' -> {
                        parentheses++
                        currentParam.append(char)
                    }
                    ')' -> {
                        parentheses--
                        currentParam.append(char)
                    }
                    '[' -> {
                        squareBrackets++
                        currentParam.append(char)
                    }
                    ']' -> {
                        squareBrackets--
                        currentParam.append(char)
                    }
                    ',' -> {
                        if (angleBrackets == 0 && parentheses == 0 && squareBrackets == 0) {
                            paramList.add(currentParam.toString().trim())
                            currentParam.clear()
                        } else {
                            currentParam.append(char)
                        }
                    }
                    else -> currentParam.append(char)
                }
            }

            if (currentParam.isNotBlank()) {
                paramList.add(currentParam.toString().trim())
            }

            return paramList
        }
    }

    override fun <T> runFromScriptFile(
        context: String,
        scriptPath: String,
        params: String,
        result: (value: T) -> Unit
    ) {
        val content = app.getInstance(FileHandler::class).load(scriptPath).readText(Charsets.UTF_8)

        this.run(context, content, this.convertStringParamsToListParams(params)) { process: T ->
            result(process)
        }
    }

    override fun <T> runFromUrl(context: String, scriptUrl: String, params: String, result: (value: T) -> Unit) {
        val content = URL(scriptUrl).readText()

        this.run(context, content, this.convertStringParamsToListParams(params)) { process: T ->
            result(process)
        }
    }

    override fun <T> run(context: String, sentence: String, params: Map<String, Any>, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)
        System.setProperty("idea.use.native.fs.for.win", "false")

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            val exportFunctionName = extractExportFunctionName(sentence)

            if (exportFunctionName != null) {
                val exportedFunctionSignature = extractExportFunctionSignature(sentence)

                fun <R> captureOutputAndResult(block: () -> R): String {
                    val originalOut = System.out
                    val originalErr = System.err

                    val outputStream = ByteArrayOutputStream()
                    val printStream = PrintStream(outputStream, true, "UTF-8")

                    return try {
                        System.setOut(printStream)
                        System.setErr(printStream)
                        val resultValue = block()
                        if (resultValue !is Unit) {
                            print(resultValue)
                        }
                        outputStream.toString("UTF-8")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        outputStream.toString("UTF-8")
                    } finally {
                        System.setOut(originalOut)
                        System.setErr(originalErr)
                    }
                }


                val flags = params.filterKeys { it.startsWith("--") || it.startsWith("-") }.keys.toTypedArray()

                if (exportedFunctionSignature?.first!!.isNotEmpty()) {

                    when {
                        isParameterizable(exportedFunctionSignature.first) -> {
                            val callbackResult = captureOutputAndResult {
                                eval(sentence)
                                val targetCallback = eval(exportFunctionName) as (Map<String, Any>) -> T
                                targetCallback.invoke(params)
                            }

                            result(callbackResult as T)
                        }

                        isModuleProcessor(exportedFunctionSignature.first) -> {
                            val processor = ModuleProcessor(context, *flags)

                            val callbackResult = captureOutputAndResult {
                                eval(sentence)
                                when (val paramCount = exportedFunctionSignature.first.size) {
                                    1 -> {
                                        val targetCallback = eval(exportFunctionName) as (Process) -> T
                                        targetCallback.invoke(processor)
                                    }

                                    2 -> {
                                        val targetCallback = eval(exportFunctionName) as (Process, Map<String, Any>) -> T
                                        targetCallback.invoke(processor, params)
                                    }

                                    else -> throw IllegalArgumentException("Unsupported function signature with $paramCount parameters")
                                }
                            }


                            if (processor.isProcessorInfo) {
                                result(processor.getModuleProcessorInfo() as T)
                                return
                            }

                            result(callbackResult as T)
                        }

                        isModuleAnalyzer(exportedFunctionSignature.first) -> {
                            val processor = ModuleAnalyzer(context, *flags)

                            val callbackResult = captureOutputAndResult {
                                eval(sentence)
                                when (val paramCount = exportedFunctionSignature.first.size) {
                                    1 -> {
                                        val targetCallback = eval(exportFunctionName) as (Process) -> T
                                        targetCallback.invoke(processor)
                                    }

                                    2 -> {
                                        val targetCallback = eval(exportFunctionName) as (Process, Map<String, Any>) -> T
                                        targetCallback.invoke(processor, params)
                                    }

                                    else -> throw IllegalArgumentException("Unsupported function signature with $paramCount parameters")
                                }
                            }

                            result(callbackResult as T)
                        }
                    }
                }
                else {
                    val callbackResult = captureOutputAndResult {
                        eval(sentence)

                        val targetCallback = eval(exportFunctionName) as () -> T
                        targetCallback.invoke()
                    }

                    result(callbackResult as T)
                }
            } else {
                result("No function annotated with @Export was found." as T)
            }
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

    private fun convertStringParamsToListParams(args: String): Map<String, Any> {
        if (args.isEmpty() || args == "EMPTY_PARAMS") return emptyMap()

        val params = mutableMapOf<String, Any>()

        args.split(",").forEach { arg ->
            if ("=" in arg) {
                val keyValue = arg.split("=")
                val key = keyValue[0]
                val value = keyValue[1]
                params[key] = value
            } else {
                params[arg] = true
            }
        }

        return params
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
                    this.run(context, scriptContent, emptyMap()) { container: Container ->
                        result(container as T, scriptName)
                    }

                    return@forEach
                }

                this.run(context, scriptContent, params) { container: Container ->
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

                            val parameters = inputData[2]

                            println("📜 Executing script: $scriptPath with params: $parameters")

                            context = inputData[0]

                            processManager.runFromScriptFile(context!!, scriptPath, parameters) { result: Any ->
                                println("✅ Result from script execution: $result")

                                if (result !== "kotlin.Unit" && (result as String).isNotEmpty()) {
                                    writer.write(result.toString())
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
                    println("⚠️ Connection error: ${e.message}")
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
