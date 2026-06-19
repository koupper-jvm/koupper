package com.koupper.shared.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlinx.coroutines.runBlocking

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

private fun ByteArray.md5hex(): String =
    java.security.MessageDigest.getInstance("MD5")
        .digest(this).joinToString("") { "%02x".format(it) }

// Process-level compiled script cache — survives within a single daemon session.
// Key: MD5 of script content. Scripts are never mutated after first load in production.
private val compiledScriptCache = ConcurrentHashMap<String, CompiledScript>()

private val diskCacheDir = File(System.getProperty("user.home"), ".koupper/cache/compiled-scripts")
    .also { it.mkdirs() }

private fun loadFromDisk(hash: String): CompiledScript? = runCatching {
    val f = File(diskCacheDir, "$hash.bin")
    if (!f.exists()) return null
    java.io.ObjectInputStream(java.io.BufferedInputStream(f.inputStream())).use {
        it.readObject() as CompiledScript
    }
}.getOrNull()

private fun saveToDisk(hash: String, compiled: CompiledScript) = runCatching {
    val f = File(diskCacheDir, "$hash.bin")
    java.io.ObjectOutputStream(java.io.BufferedOutputStream(f.outputStream())).use {
        it.writeObject(compiled)
    }
}.getOrNull()

/**
 * Production-grade scripting host with support for external classpaths,
 * lazy classloader caching, diagnostics, safe resource cleanup, and
 * in-process compiled script caching (eliminates re-compilation of unchanged scripts).
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
                jvmTarget("17")

                // Use the runtime JAR (octopus.jar) as the explicit compilation classpath.
                // Avoids scanning the full classloader hierarchy which can hit JARs with bad LOC headers.
                val selfJar = this::class.java.protectionDomain.codeSource?.location?.toURI()?.let { File(it) }
                if (selfJar != null && selfJar.exists() && selfJar.extension == "jar") {
                    updateClasspath(listOf(selfJar))
                } else {
                    // Fallback: scan whole classpath (may fail in some JVM configurations)
                    dependenciesFromCurrentContext(wholeClasspath = true)
                }

                if (extraClasspath.isNotEmpty()) {
                    updateClasspath(extraClasspath)
                }
            }
            defaultImports("com.koupper.shared.*")
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
        return evalWithSource(code, sourceName = null, lineOffset = 0)
    }

    fun eval(code: String, sourceName: String): Any {
        return evalWithSource(code, sourceName, lineOffset = 0)
    }

    fun eval(code: String, sourceName: String?, lineOffset: Int): Any {
        return evalWithSource(code, sourceName, lineOffset)
    }

    private fun evalWithSource(code: String, sourceName: String?, lineOffset: Int = 0): Any {
        require(code.isNotBlank()) { "Script code must not be blank" }

        val scriptSourceName = sourceName?.takeIf { it.isNotBlank() }
            ?: "KoupperScript_${java.util.UUID.randomUUID().toString().replace("-", "")}.kts"

        val cacheKey   = code.toByteArray(Charsets.UTF_8).md5hex()
        val source     = code.toScriptSource(scriptSourceName)

        // Try to retrieve a previously compiled script (same content = same bytecode).
        // Check: 1) in-process cache, 2) disk cache, 3) compile fresh.
        val compiled: CompiledScript = compiledScriptCache[cacheKey]?.also {
            println("[DEBUG] Loading $scriptSourceName from in-process cache")
        } ?: loadFromDisk(cacheKey)?.also {
            compiledScriptCache[cacheKey] = it
            println("[DEBUG] Loading $scriptSourceName from disk cache")
        } ?: run {
            println("[DEBUG] Compiling $scriptSourceName with JVM target 17")
            val compileResult = runBlocking { host.compiler(source, compilationConfig) }

            compileResult.reports
                .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
                .filter { shouldDisplayWarning(it) }
                .forEach { diagnostic ->
                    val adjustedLine = diagnostic.location?.let { loc ->
                        val srcLine = (loc.start.line - lineOffset).coerceAtLeast(1)
                        " (line $srcLine, col ${loc.start.col})"
                    } ?: ""
                    val src = sourceName?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                    System.err.println("[ScriptingHost][${diagnostic.severity}]$src$adjustedLine ${diagnostic.message}")
                }

            val cs = compileResult.valueOrThrow()
            compiledScriptCache[cacheKey] = cs
            saveToDisk(cacheKey, cs)  // persist for next restart (graceful no-op if not serializable)
            cs
        }

        val evalResult = runBlocking { host.evaluator(compiled, evalConfig) }

        evalResult.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            .filter { shouldDisplayWarning(it) }
            .forEach { diagnostic ->
                System.err.println("[ScriptingHost][${diagnostic.severity}] ${diagnostic.message}")
            }

        val evalRes = evalResult.valueOrThrow()

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

    val hasEvaluated: Boolean
        get() = lastInstance != null

    val lastScriptClassName: String?
        get() = lastScriptClass?.name

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
