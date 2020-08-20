package com.koupper.octopus

interface ProcessManager {
    /**
     * Runs a sentence under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> run(sentence: String, result: (value : T) -> Unit)

    /**
     * Loads a script from file, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> runScriptFile(scriptPath: String, result: (value : T) -> Unit)
}
