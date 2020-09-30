package com.koupper.octopus

import com.koupper.container.interfaces.Container

interface ProcessManager {
    /**
     * Runs a sentence without params under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param sentence the script sentence
     * @param result a callback
     */
    fun <T> run(sentence: String, result: (value: T) -> Unit)

    /**
     * Runs a sentence with params under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param sentence the script sentence
     * @param params the script params
     * @param result a callback
     */
    fun <T> run(sentence: String, params: Map<String, Any>, result: (value: T) -> Unit)

    /**
     * Loads a script from file, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scriptPath
     * @param args the script arguments
     * @param result a callback
     */
    fun <T> runScriptFile(scriptPath: String, args: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script from URL, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scriptUrl
     * @param args the script arguments
     * @param result a callback
     */
    fun <T> runScriptFileFromUrl(scriptUrl: String, args: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script files from paths, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scripts a map of script paths
     * @param result a callback
     */
    fun <T> runScriptFiles(scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit)

    /**
     * Build a deployable resource.
     *
     * @param scriptManager
     */
    fun buildFrom(scriptManager: ScriptManager)

    /**
     * Build a deployable resource.
     *
     * @param callable
     * @param params
     */
    fun execute(callable: (container: Container, params: Map<String, Any>) -> Container, params: String)
}
