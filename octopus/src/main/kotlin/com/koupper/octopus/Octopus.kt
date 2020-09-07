package com.koupper.octopus

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.container.interfaces.ScriptManager
import com.koupper.octopus.exceptions.InvalidScriptException
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import java.io.File
import javax.script.ScriptEngineManager
import kotlin.reflect.KClass

class Octopus(private var container: Container, private var config: Config) : ProcessManager {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()
    private val userPath = System.getProperty("user.home")

    override fun <T> run(sentence: String, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", "$userPath/.koupper/libs/octopus-1.0.jar")

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid. $sentence")
            }

            val firstSpaceInSentence = sentence.indexOf(" ")

            when {
                isContainerType(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    val targetCallback = eval(valName) as (Container) -> T

                    result(targetCallback.invoke(container) as T)
                }
                isConFigType(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    val targetCallback = eval(valName) as (ScriptManager) -> ScriptManager

                    result(targetCallback.invoke(config) as T)
                }
                isParameterized(sentence) -> {
                    run(sentence, emptyMap()) { container: Container ->
                        result(container as T)
                    }
                }
                else -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf("=") - 1

                    val valName = sentence.substring(firstSpaceInSentence, endOfVariableNameInSentence).trim()

                    result(eval(valName) as T)
                }
            }
        }
    }

    override fun <T> run(sentence: String, params: Map<String, Any>, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", "$userPath/.koupper/libs/octopus-1.0.jar")

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid.")
            }

            eval(sentence)

            val endOfVariableNameInSentence = sentence.indexOf(":")

            val startOfSentence = sentence.indexOf("val")

            val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

            val targetCallback = eval(valName) as (Container, Map<String, Any>) -> T

            result(targetCallback.invoke(container, params))
        }
    }

    override fun <T> runScriptFile(scriptPath: String, args: String, result: (value: T) -> Unit) {
        val scriptContent = File(scriptPath).readText(Charsets.UTF_8)

        if ("init.kt" in scriptPath) {
            this.run(scriptContent) { scriptManager: ScriptManager ->
                result(scriptManager as T)
            }

            return
        }

        this.run(scriptContent, this.buildParams(args)) { container: Container ->
            result(container as T)
        }
    }

    private fun buildParams(args: String): Map<String, Any> {
        if (args.isEmpty()) emptyMap<String, Any>()

        val params = mutableMapOf<String, Any>()

        val input = args.split(",").forEach { arg ->
            val keyValue = arg.split(":")

            val key = keyValue[0]

            val value = keyValue[1]

            params[key] = value
        }

        return params
    }

    override fun <T> runScriptFiles(scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit) {
        scripts.forEach { (scriptPath, params) ->
            val scriptContent = File(scriptPath).readText(Charsets.UTF_8)

            val scriptName = File(scriptPath).name

            if (params.isEmpty()) {
                this.run(scriptContent) { container: Container ->
                    result(container as T, scriptName)
                }

                return@forEach
            }

            this.run(scriptContent, params) { container: Container ->
                result(container as T, scriptName)
            }
        }
    }

    fun availableServiceProviders(): List<KClass<*>> {
        return this.registeredServiceProviders
    }

    fun registerBuildInServicesProvidersInContainer(): Map<KClass<*>, Any> {
        this.availableServiceProviders().forEach { provider ->
            ((provider).constructors.elementAt(0).call() as ServiceProvider).up()
        }

        return this.container.getBindings() as Map<KClass<*>, Any>
    }

    fun registerExternalServiceProviders(providers: List<ServiceProvider>) {
        providers.forEach { provider ->
            provider.up()
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) print("No parameters provided.")

    val containerImplementation = app

    val octopus = Octopus(containerImplementation, Config())

    octopus.registerBuildInServicesProvidersInContainer()

    var params = ""

    if (args.size > 1) params = args[1]

    octopus.runScriptFile(args[0], params) { result: Any ->
        if (result is ScriptManager) {
            val listScripts = result.listScripts()

            octopus.runScriptFiles(listScripts) { result: Container, nameScript: String ->
                println("script [$nameScript] ->\u001B[38;5;155m executed.\u001B[0m")
            }
        } else if (result is Container) {
            val nameScript = args[0]

            println("\nscript [$nameScript] ->\u001B[38;5;155m executed.\u001B[0m")
        }
    }
}
