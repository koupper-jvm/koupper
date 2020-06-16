package io.kup.octopus

interface ProcessManager {
    /**
     * Run a sentence under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> run(sentence: String, result: (value : T) -> Unit)
}
