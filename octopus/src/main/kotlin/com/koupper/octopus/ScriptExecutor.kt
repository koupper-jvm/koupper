package com.koupper.octopus

import com.koupper.container.interfaces.Container

interface ScriptExecutor {
    /**
     * Loads a script from file, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scriptPath
     * @param params the script params
     * @param result a callback
     */
    fun <T> runFromScriptFile(scriptPath: String, params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script from URL, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scriptUrl
     * @param params the script params
     * @param result a callback
     */
    fun <T> runFromUrl(scriptUrl: String, params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script files from paths, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param scripts a map of script paths
     * @param result a callback
     */
    fun <T> runScriptFiles(
        scripts: MutableMap<String, Map<String, Any>>,
        result: (value: T, scriptName: String) -> Unit
    )

    /**
     * Runs a sentence with params under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param sentence the script sentence
     * @param params the script params
     * @param result a callback
     */
    fun <T> run(sentence: String, params: Map<String, Any> = emptyMap(), result: (value: T) -> Unit)

    /**
     * call a  function
     *
     * @param callable the function
     * @param params the params
     */
    fun <T> call(callable: (params: Map<String, Any>) -> T, params: Map<String, Any>): T

    /**
     * Calls a  function
     *
     * @return A type
     */
    fun <T> call(callable: () -> T): T

    /**
     * Calls a  function without a return
     */
    fun call(callable: () -> Unit)
}
