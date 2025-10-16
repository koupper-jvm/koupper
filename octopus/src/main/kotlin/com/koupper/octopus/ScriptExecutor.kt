package com.koupper.octopus

import com.koupper.container.interfaces.Container

interface ScriptExecutor {
    /**
     * Loads a script from file, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param context where script-based operations will happen
     * @param scriptPath
     * @param params the script params
     * @param result a callback
     */
    fun <T> runFromScriptFile(context: String, scriptPath: String, params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script from URL, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param context where script-based operations will happen
     * @param scriptUrl
     * @param params the script params
     * @param result a callback
     */
    fun <T> runFromUrl(context: String, scriptUrl: String = "undefined", params: String = "", result: (value: T) -> Unit)

    /**
     * Loads a script files from paths, running it under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param context where script-based operations will happen
     * @param scripts a map of script paths
     * @param result a callback
     */
    fun <T> runScriptFiles(
        context: String,
        scripts: MutableMap<String, Map<String, Any>>,
        result: (value: T, scriptName: String) -> Unit
    )

    /**
     * Runs a sentence with params under ScriptEngineManager implementation and
     * callback the resulting execution.
     *
     * @param context where script-based operations will happen
     * @param scriptPath the script path
     * @param sentence the script sentence
     * @param params the script params
     * @param result a callback
     */
    fun <T> run(context: String, scriptPath: String? = null, sentence: String, params: ParsedParams?, result: (value: T) -> Unit)

    /**
     * call a  function
     *
     * @param callable the function
     * @param params the params
     */
    fun <T> call(callable: (params: Map<String, Any>) -> T, params: Map<String, Any>): T

    /**
     * call an array of functions
     *
     * @param callables the functions
     */
    fun <T> call(callables: Array<Pair<(Map<String, Any>) -> T, Map<String, Any>>>)

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
