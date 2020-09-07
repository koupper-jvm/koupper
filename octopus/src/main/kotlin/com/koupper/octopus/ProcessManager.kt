package com.koupper.octopus

interface ProcessManager {
    /**
     * Runs a sentence without params under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> run(sentence: String, result: (value : T) -> Unit)

    /**
     * Runs a sentence with params under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> run(sentence: String, params: Map<String, Any>, result: (value : T) -> Unit)

    /**
     * Loads a script from file, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> runScriptFile(scriptPath: String, args: String = "", result: (value : T) -> Unit)

    /**
     * Loads a script files from paths, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     */
    fun <T> runScriptFiles(scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit)
}
