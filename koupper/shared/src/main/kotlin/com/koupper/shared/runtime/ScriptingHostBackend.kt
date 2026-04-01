package com.koupper.shared.runtime

import java.io.File
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
                if (extraClasspath.isNotEmpty()) {
                    updateClasspath(extraClasspath)
                }
            }
            // Explicitly force K1 compiler for scripts to bypass K2 FIR Fat-Jar module accessibility bugs
            compilerOptions("-language-version", "1.9")
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
        require(code.isNotBlank()) { "Script code must not be blank" }

        // Use a uniquely generated name so the JVM doesn't collide multiple "Script.class" references during nested invocations.
        val uniqueName = "KoupperScript_${java.util.UUID.randomUUID().toString().replace("-", "")}.kts"
        val result = host.eval(code.toScriptSource(uniqueName), compilationConfig, evalConfig)

        // Reportar diagnósticos antes de lanzar
        result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            .filter { shouldDisplayWarning(it) }
            .forEach { diagnostic ->
                val location = diagnostic.location?.let { loc ->
                    " (line ${loc.start.line}, col ${loc.start.col})"
                } ?: ""
                System.err.println("[ScriptingHost][${diagnostic.severity}]$location ${diagnostic.message}")
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

        val field = fieldCache.getOrPut(clazz to symbol) {
            resolveField(clazz, symbol)
                ?: error("Field '$symbol' not found in ${clazz.name} or any of its superclasses")
        }

        return field.get(instance)
    }

    /**
     * Busca el campo también en superclases, por si el script hereda de una clase base.
     */
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
