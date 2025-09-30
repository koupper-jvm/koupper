package com.koupper.shared.runtime

import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class ScriptingHostBackend : ScriptBackend {

    private val host = BasicJvmScriptingHost()
    private var lastInstance: Any? = null  // guarda la última instancia del script

    override fun eval(code: String): Any? {
        val compilationConfig = ScriptCompilationConfiguration {
            jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        }

        val evalConfig = ScriptEvaluationConfiguration {
            jvm { baseClassLoader(Thread.currentThread().contextClassLoader) }
        }

        val result = host.eval(code.toScriptSource(), compilationConfig, evalConfig)
        val evalRes = result.valueOrThrow()

        // 🔹 guardar la instancia del script evaluado
        lastInstance = evalRes.returnValue.scriptInstance

        return evalRes.returnValue
    }

    override val classLoader: ClassLoader
        get() = Thread.currentThread().contextClassLoader

    fun getSymbol(symbol: String): Any? {
        val instance = lastInstance ?: error("⚠️ No se ha evaluado ningún script todavía")
        val clazz = instance.javaClass

        val field = clazz.declaredFields.firstOrNull { it.name == symbol }
            ?: error("No se encontró campo con nombre $symbol en ${clazz.name}")

        field.isAccessible = true
        return field.get(instance)
    }
}

