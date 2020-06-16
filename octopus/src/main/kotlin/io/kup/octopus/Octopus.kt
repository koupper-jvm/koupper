package io.kup.octopus

import io.kup.container.Container
import io.kup.octopus.exceptions.InvalidScriptException
import javax.script.ScriptEngineManager

class Octopus(private var container: Container) : ProcessManager {

    override fun <T> run(sentence: String, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid.")
            }

            val firstSpaceInSentence = sentence.indexOf(" ")

            if (isTyped(sentence)) {
                if (dependesOfContainer(sentence)) {
                    eval("import io.kup.container.Container")

                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val valName = sentence.substring(firstSpaceInSentence, endOfVariableNameInSentence).trim()

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
}
