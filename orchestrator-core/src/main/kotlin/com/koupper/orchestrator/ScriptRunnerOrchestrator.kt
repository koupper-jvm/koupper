package com.koupper.orchestrator

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.koupper.shared.runtime.ScriptBackend
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.runtime.ScriptingHostBackend

import kotlin.reflect.jvm.isAccessible

data class ScriptCall(
    val functionName: String,
    val code: String,
    val argTypes: List<String>? = null,
    val paramsJson: Map<String, String> = emptyMap(),
    val symbol: Any? = null // 👈 Nuevo
)

fun serializeArgs(vararg args: Any?, mapper: com.fasterxml.jackson.databind.ObjectMapper): Map<String, String> =
    args.mapIndexed { i, v -> "arg$i" to mapper.writeValueAsString(v) }.toMap()

fun ScriptCall(task: KouTask): ScriptCall = ScriptCall(
    code        = task.sourceSnapshot
        ?: error("sourceSnapshot nulo para script"),
    functionName = task.functionName,
    paramsJson   = task.params,
    argTypes   = task.signature?.first
)

fun buildParamsJson(
    types: List<String>,
    positionals: List<String>,
    kv: Map<String, String>
): Map<String, String> {
    val out = LinkedHashMap<String, String>(types.size)
    var pos = 0
    types.indices.forEach { i ->
        val key = "arg$i"
        val v = kv[key] ?: positionals.getOrNull(pos)?.also { pos++ }
        ?: return@forEach // si falta y es nullable lo resolverá el runner, si no, fallará ahí
        out[key] = v
    }
    return out
}

object ScriptRunner {
    fun runScript(
        call: ScriptCall,
        backend: ScriptBackend,
        injector: (String) -> Any? = { null }
    ): Any? {
        val anyRef = call.symbol
            ?: error("Símbolo no encontrado: ${call.functionName}")

        val target: Any = when (anyRef) {
            is kotlin.reflect.KProperty0<*> -> {
                anyRef.getter.isAccessible = true
                anyRef.isAccessible = true
                anyRef.get() ?: error("La propiedad '${call.functionName}' es nula")
            }
            else -> anyRef
        }

        val targetCL = target.javaClass.classLoader

        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .registerKotlinModule()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .also { it.setTypeFactory(it.typeFactory.withClassLoader(targetCL)) }

        val functionArgs: List<String> = call.argTypes
            ?: (extractExportFunctionSignature(call.code)?.first
                ?: error("No se pudo extraer firma de la función"))

        fun argIndex(k: String) = k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE

        fun resolveParamClass(pt: String): Class<*> {
            val full = pt.trim().removeSuffix("?")
            return when (pt.normalizeType()) {
                "String"  -> String::class.java
                "Int"     -> Int::class.java
                "Long"    -> java.lang.Long::class.java
                "Double"  -> java.lang.Double::class.java
                "Boolean" -> java.lang.Boolean::class.java
                "Float"   -> java.lang.Float::class.java
                "Short"   -> java.lang.Short::class.java
                "Byte"    -> java.lang.Byte::class.java
                "Char"    -> Char::class.java
                else      -> Class.forName(full, true, targetCL)
            }
        }

        val orderedParams = call.paramsJson.entries.sortedBy { argIndex(it.key) }
        val callArgs = ArrayList<Any?>(functionArgs.size)
        var userIdx = 0

        loop@ for (pt in functionArgs) {
            val inj = injector(pt)
            if (inj != null) {
                callArgs += inj
                continue@loop
            }

            val key = "arg$userIdx"
            val raw = orderedParams.firstOrNull { it.key == key }?.value
            val isNullable = pt.trim().endsWith("?")

            if (raw == null) {
                if (isNullable) {
                    callArgs += null
                    userIdx += 1
                    continue
                } else error("Falta '$key' en params para tipo '$pt'")
            }

            val token = raw.trim()

            val unwrapped = if (token.length >= 2 && token[0] == '"' &&
                (token.getOrNull(1) == '{' || token.getOrNull(1) == '[')
            ) {
                mapper.readValue(token, String::class.java)
            } else token

            val value: Any? =
                if (pt.isSimpleType()) {
                    when (pt.normalizeType()) {
                        "String" -> {
                            val s = unwrapped
                            val isJsonString = s.length >= 2 && s.first() == '"' && s.last() == '"'
                            if (isJsonString) mapper.readValue(s, String::class.java) else s
                        }
                        "Int"     -> mapper.readValue(unwrapped, Int::class.java)
                        "Long"    -> mapper.readValue(unwrapped, java.lang.Long::class.java)
                        "Double"  -> mapper.readValue(unwrapped, java.lang.Double::class.java)
                        "Boolean" -> mapper.readValue(unwrapped, java.lang.Boolean::class.java)
                        "Float"   -> mapper.readValue(unwrapped, java.lang.Float::class.java)
                        "Short"   -> mapper.readValue(unwrapped, java.lang.Short::class.java)
                        "Byte"    -> mapper.readValue(unwrapped, java.lang.Byte::class.java)
                        "Char"    -> mapper.readValue(unwrapped, String::class.java).single()
                        else      -> mapper.readValue(unwrapped, Any::class.java)
                    }
                } else {
                    val cls = resolveParamClass(pt)
                    if (cls == Char::class.java)
                        mapper.readValue(unwrapped, String::class.java).single()
                    else
                        mapper.readValue(unwrapped, cls)
                }

            callArgs += value
            userIdx += 1
        }

        val lambdaClass = target::class.java

        val invoke = (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == "invoke" && it.parameterCount == callArgs.size }
            ?: (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
                .firstOrNull {
                    it.name == "invoke" &&
                            it.parameterCount == callArgs.size + 1 &&
                            it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
                }?.also { error("La función '${call.functionName}' es suspend (Continuation no soportado).") }
            ?: error("No hay 'invoke' con aridad ${callArgs.size} en ${lambdaClass.name}")

        try { invoke.isAccessible = true } catch (_: Exception) {
            try {
                val ao = Class.forName("java.lang.reflect.AccessibleObject")
                val m  = ao.getMethod("trySetAccessible")
                m.invoke(invoke)
            } catch (_: Throwable) {}
        }

        val oldCl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = lambdaClass.classLoader
        return try {
            invoke.invoke(target, *callArgs.toTypedArray())
        } finally {
            Thread.currentThread().contextClassLoader = oldCl
        }
    }

    fun runScript(
        task: KouTask,
        backend: ScriptBackend,
        injector: (String) -> Any? = { null }
    ): Any? = runScript(ScriptCall(task), backend, injector)
}


