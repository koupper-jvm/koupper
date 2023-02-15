package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_WHITE
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.ModuleMaker
import com.koupper.octopus.process.Process
import com.koupper.octopus.routes.Route
import com.koupper.os.env
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.files.*
import com.koupper.providers.http.HtppClient
import java.io.*
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
    it.matches("^[a-zA-Z0-9]+.kts$".toRegex())
}

class Octopus(private var container: Container) : ScriptExecutor {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()

    override fun <T> runFromScriptFile(scriptPath: String, params: String, result: (value: T) -> Unit) {
        val content = File(scriptPath).readText(Charsets.UTF_8)

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

    override fun <T> run(sentence : String, params: Map<String, Any>, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            val endOfVariableNameInSentence = sentence.indexOf(":")

            val startOfSentence = sentence.indexOf("val")

            val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

            when {
                isContainerType(sentence) -> {
                    eval(sentence)

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (Container) -> T

                        result(targetCallback.invoke(container))
                    } else {
                        val targetCallback = eval(valName) as (Container, Map<String, Any>) -> T

                        result(targetCallback.invoke(container, params))
                    }

                }
                isModuleProcess(sentence) -> {
                    eval(sentence)

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (Process) -> T

                        result(targetCallback.invoke(ModuleMaker(container)))
                    } else {
                        val targetCallback = eval(valName) as (Process, Map<String, Any>) -> T

                        result(targetCallback.invoke(ModuleMaker(container), params))
                    }
                }
                isRoute(sentence) -> {
                    eval(sentence)

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (Route) -> T

                        result(targetCallback.invoke(Route(container)))
                    } else {
                        val targetCallback = eval(valName) as (Route, Map<String, Any>) -> T

                        result(targetCallback.invoke(Route(container), params))
                    }
                }
                else -> {
                    eval(sentence)

                    result(eval(sentence.substring(sentence.indexOf(" "), sentence.indexOf("=") - 1).trim()) as T)
                }
            }
        }
    }

    override fun execute(
        callable: (container: Container, params: Map<String, Any>) -> Any,
        params: Map<String, Any>
    ) {
        callable(container, params)
    }

    private fun convertStringParamsToListParams(args: String): Map<String, Any> {
        if (args.isEmpty() || args == "EMPTY_PARAMS") return emptyMap()

        val params = mutableMapOf<String, Any>()

        args.split(",").forEach { arg ->
            val keyValue = arg.split(":")

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
}

fun main(args: Array<String>) {
    val processManager = createDefaultConfiguration()

    if (args.isNotEmpty() && args[0] == "UPDATING_CHECK") {
        checkForUpdates()
    } else {
        var params = ""

        if (args.size > 1) params = args[1]

        val scriptPath = args[0]

        processManager.runFromScriptFile(scriptPath, params) { result: Any ->
            processCallback(processManager, scriptPath, result)
        }
    }
}

fun checkForUpdates(): Boolean {
    val checkForUpdateUrl = env("CHECK_FOR_UPDATED_URL")

    val httpClient = app.createInstanceOf(HtppClient::class)

    val response = httpClient.get {
        url = checkForUpdateUrl!!
    }

    val textJsonParser = app.createInstanceOf(JSONFileHandler::class) as JSONFileHandlerImpl<*>

    textJsonParser.read(response?.asString()!!)

    data class Versioning(val statusCode: String, val body: String)

    val versioning = textJsonParser.toType() as Versioning

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
    if (result is Container) {
        println("\nscript [$scriptName] ->\u001B[38;5;155m was executed b .\u001B[0m")
    } else if (result is Process) {
        println("\r${ANSI_GREEN_155}📦 module ${ANSI_WHITE}${result.processName()}$ANSI_GREEN_155 was created.\u001B[0m\n")
    }
}

fun createDefaultConfiguration(container: Container = app): ScriptExecutor {
    val octopus = Octopus(container)
    octopus.registerBuildInServicesProvidersInContainer()

    return octopus
}
