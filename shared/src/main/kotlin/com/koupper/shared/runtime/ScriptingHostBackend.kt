package com.koupper.shared.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

private const val SCRIPT_WARNINGS_PROPERTY = "koupper.scripting.showWarnings"
private const val SCRIPT_WARNINGS_ENV = "KOUPPER_SCRIPT_WARNINGS"
private const val SCRIPT_NOISY_WARNINGS_PROPERTY = "koupper.scripting.showNoisyWarnings"
private const val SCRIPT_NOISY_WARNINGS_ENV = "KOUPPER_SCRIPT_NOISY_WARNINGS"

private fun runtimeFlag(propertyName: String, envName: String, default: Boolean): Boolean {
    val fromProperty = System.getProperty(propertyName)?.trim()
    val fromEnv = System.getenv(envName)?.trim()
    val raw = fromProperty?.takeIf { it.isNotBlank() } ?: fromEnv?.takeIf { it.isNotBlank() } ?: return default

    return raw.equals("true", ignoreCase = true) || raw == "1" || raw.equals("yes", ignoreCase = true)
}

private fun shouldDisplayWarning(diagnostic: ScriptDiagnostic): Boolean {
    val showWarnings = runtimeFlag(SCRIPT_WARNINGS_PROPERTY, SCRIPT_WARNINGS_ENV, default = true)
    if (!showWarnings) return false

    val showNoisyWarnings = runtimeFlag(SCRIPT_NOISY_WARNINGS_PROPERTY, SCRIPT_NOISY_WARNINGS_ENV, default = false)
    if (!showNoisyWarnings && diagnostic.severity == ScriptDiagnostic.Severity.WARNING) {
        val message = diagnostic.message.lowercase()
        if (message.contains("new faster version of jar fs")) {
            return false
        }
    }

    return true
}

/**
 * Production-grade scripting host with support for external classpaths,
 * lazy classloader caching, diagnostics, and safe resource cleanup.
 *
 * @param extraClasspath JARs o directorios adicionales a incluir en el classpath
 *                       (e.g. build/libs/app.jar, build/classes/kotlin/main)
 */
class ScriptingHostBackend(
    private val extraClasspath: List<File> = emptyList()
) : ScriptBackend, AutoCloseable {

    // ──────────────────────────────────────────────
    // Core
    // ──────────────────────────────────────────────

    private val host = BasicJvmScriptingHost()

    private var lastInstance: Any? = null
    private var lastScriptClass: Class<*>? = null

    // ──────────────────────────────────────────────
    // ClassLoader (lazy, cached, thread-safe)
    // ──────────────────────────────────────────────

    private val customClassLoader: ClassLoader by lazy {
        val validated = extraClasspath.onEach { file ->
            require(file.exists()) {
                "Classpath entry does not exist: ${file.absolutePath}"
            }
        }

        if (validated.isEmpty()) {
            Thread.currentThread().contextClassLoader
        } else {
            URLClassLoader(
                validated.map { it.toURI().toURL() }.toTypedArray(),
                Thread.currentThread().contextClassLoader
            )
        }
    }

    override val classLoader: ClassLoader
        get() = customClassLoader

    // ──────────────────────────────────────────────
    // Compilation & Evaluation configs (lazy, reused)
    // ──────────────────────────────────────────────

    private val compilationConfig: ScriptCompilationConfiguration by lazy {
        ScriptCompilationConfiguration {
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
                jvmTarget("17")
                
                // Si estamos en un FatJar, a veces dependenciesFromCurrentContext no es suficiente
                // Intentamos encontrar el JAR actual y añadirlo explícitamente
                val selfJar = this::class.java.protectionDomain.codeSource?.location?.toURI()?.let { File(it) }
                if (selfJar != null && selfJar.exists() && selfJar.extension == "jar") {
                    updateClasspath(listOf(selfJar))
                }

                if (extraClasspath.isNotEmpty()) {
                    updateClasspath(extraClasspath)
                }
            }
            compilerOptions("-jvm-target", "17")
        }
    }

    private val evalConfig: ScriptEvaluationConfiguration by lazy {
        ScriptEvaluationConfiguration {
            jvm { baseClassLoader(customClassLoader) }
        }
    }

    // ──────────────────────────────────────────────
    // Symbol cache (evita reflection repetida)
    // ──────────────────────────────────────────────

    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, java.lang.reflect.Field>()

    // ──────────────────────────────────────────────
    // eval
    // ──────────────────────────────────────────────

    override fun eval(code: String): Any {
        return evalWithSource(code, sourceName = null)
    }

    fun eval(code: String, sourceName: String): Any {
        return evalWithSource(code, sourceName)
    }

    private fun evalWithSource(code: String, sourceName: String?): Any {
        require(code.isNotBlank()) { "Script code must not be blank" }

        val scriptSourceName = sourceName?.takeIf { it.isNotBlank() }
            ?: "KoupperScript_${java.util.UUID.randomUUID().toString().replace("-", "")}.kts"
        
        println("[DEBUG] Compiling $scriptSourceName with JVM target 17")
        val result = host.eval(code.toScriptSource(scriptSourceName), compilationConfig, evalConfig)

        // Reportar diagnósticos antes de lanzar
        result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            .filter { shouldDisplayWarning(it) }
            .forEach { diagnostic ->
                val location = diagnostic.location?.let { loc ->
                    " (line ${loc.start.line}, col ${loc.start.col})"
                } ?: ""
                val source = sourceName?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                System.err.println("[ScriptingHost][${diagnostic.severity}]$source$location ${diagnostic.message}")
            }

        val evalRes = result.valueOrThrow()

        lastInstance  = evalRes.returnValue.scriptInstance
        lastScriptClass = lastInstance?.javaClass

        return evalRes.returnValue
    }

    // ──────────────────────────────────────────────
    // getSymbol
    // ──────────────────────────────────────────────

    override fun getSymbol(symbol: String): Any? {
        require(symbol.isNotBlank()) { "Symbol name must not be blank" }

        val instance = lastInstance
            ?: error("No script has been evaluated yet — call eval() first")

        val clazz = instance.javaClass
        val key = clazz to symbol

        val field = fieldCache[key] ?: resolveField(clazz, symbol)?.also { fieldCache[key] = it }
        if (field != null) return field.get(instance)

        // K2 compiles top-level @Export fun declarations as JVM methods, not fields
        val method = resolveMethod(clazz, symbol)
            ?: error("Symbol '$symbol' not found as field or method in ${clazz.name} or any of its superclasses")

        return wrapMethodAsCallable(instance, method)
    }

    private fun resolveField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun resolveMethod(clazz: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull { it.name == name && !it.isSynthetic }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun wrapMethodAsCallable(instance: Any, method: java.lang.reflect.Method): Any {
        val arity = method.parameterCount
        val functionInterface = Class.forName("kotlin.jvm.functions.Function$arity")
        return Proxy.newProxyInstance(
            instance.javaClass.classLoader,
            arrayOf(functionInterface)
        ) { _, proxyMethod, args ->
            if (proxyMethod.name == "invoke") {
                method.isAccessible = true
                method.invoke(instance, *(args ?: emptyArray()))
            } else null
        }
    }

    // ──────────────────────────────────────────────
    // Estado / introspección
    // ──────────────────────────────────────────────

    /** True si ya se evaluó al menos un script exitosamente. */
    val hasEvaluated: Boolean
        get() = lastInstance != null

    /** Clase del último script evaluado, útil para diagnóstico. */
    val lastScriptClassName: String?
        get() = lastScriptClass?.name

    /** Lista de campos disponibles en el último script evaluado. */
    val availableSymbols: List<String>
        get() = lastScriptClass
            ?.declaredFields
            ?.map { it.name }
            ?: emptyList()

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────

    override fun close() {
        fieldCache.clear()
        lastInstance = null
        lastScriptClass = null
        (customClassLoader as? URLClassLoader)?.close()
    }
}
