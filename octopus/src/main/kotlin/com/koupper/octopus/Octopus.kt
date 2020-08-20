package com.koupper.octopus

import com.koupper.container.KupContainer
import com.koupper.container.interfaces.Container
import com.koupper.octopus.exceptions.InvalidScriptException
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import java.io.File
import javax.script.ScriptEngineManager
import kotlin.reflect.KClass

class Octopus(private var container: Container) : ProcessManager {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()

    override fun <T> run(sentence: String, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid.")
            }

            val firstSpaceInSentence = sentence.indexOf(" ")

            if (isTyped(sentence)) {
                if (dependesOfContainer(sentence)) {
                    eval("import com.koupper.container.interfaces.Container")

                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    val targetCallback = eval(valName) as (Container) -> T

                    result(targetCallback.invoke(container) as T)
                } else {

                }
            } else {
                eval(sentence)

                val endOfVariableNameInSentence = sentence.indexOf("=") - 1

                val valName = sentence.substring(firstSpaceInSentence, endOfVariableNameInSentence).trim()

                result(eval(valName) as T)
            }
        }
    }

    override fun <T> runScriptFile(scriptPath: String, result: (value: T) -> Unit) {
        val scriptContent = File(scriptPath).readText(Charsets.UTF_8)

        this.run(scriptContent) { container: Container ->
            result(container as T)
        }
    }

    fun availableServiceProviders(): List<KClass<*>> {
        return this.registeredServiceProviders
    }

    fun registerBuildInBindingsInContainer(): Map<KClass<*>, Any> {
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

fun main() {
    Octopus(KupContainer())
}
