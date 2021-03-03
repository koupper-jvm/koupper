package com.koupper.octopus.managers

import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.ModuleProcess

interface ProcessExecutionManager {
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
     * @param params the script params
     * @param result a callback
     */
    fun <T> runScriptFile(scriptPath: String, params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script from URL, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scriptUrl
     * @param params the script params
     * @param result a callback
     */
    fun <T> runScriptFileFromUrl(scriptUrl: String, params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script files from paths, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scripts a map of script paths
     * @param result a callback
     */
    fun <T> runScriptFiles(scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit)

    /**
     * Build a deployable project.
     *
     * @param scriptManager
     */
    fun buildProjectFrom(moduleProcess: ModuleProcess)

    /**
     * Execute a callable code passing a map of params.
     *
     * @param callable
     * @param params
     */
    fun execute(callable: (container: Container, params: Map<String, Any>) -> Container, params: Map<String, String>)
}
