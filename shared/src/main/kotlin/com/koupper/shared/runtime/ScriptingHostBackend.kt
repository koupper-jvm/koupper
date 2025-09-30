package com.koupper.shared.runtime

import java.io.File
import java.net.URLClassLoader
import javax.script.ScriptEngineManager

class ScriptingHostBackend : ScriptBackend {

    private val engine = run {
        // 🚑 Forzar scripting a usar el classpath y no module-path
        System.setProperty("kotlin.scripting.use.jvm.modules", "false")
        System.setProperty("kotlin.scripting.jvm.module.path", "disabled")
        System.setProperty("idea.use.native.fs.for.win", "false")

        // 🚀 Construir classloader con el classpath real
        val cp = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it).toURI().toURL() }
            .toTypedArray()

        val loader = URLClassLoader(cp, Thread.currentThread().contextClassLoader)

        ScriptEngineManager(loader).getEngineByExtension("kts")
            ?: error("❌ No se encontró motor para .kts")
    }

    override fun eval(code: String): Any? {
        return try {
            engine.eval(code)
        } catch (e: Exception) {
            throw RuntimeException("Script error: ${e.message}", e)
        }
    }

    override val classLoader: ClassLoader
        get() = this::class.java.classLoader

    fun get(symbol: String): Any? = engine.get(symbol)
}
