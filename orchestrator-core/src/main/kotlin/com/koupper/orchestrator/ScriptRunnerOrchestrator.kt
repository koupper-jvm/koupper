package com.koupper.orchestrator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import kotlin.reflect.jvm.isAccessible

data class ScriptCall(
    val functionName: String,
    val code: String,
    val argTypes: List<String>? = null,
    val paramsJson: Map<String, String> = emptyMap(),
    val symbol: Any? = null,
    val annotationParams: Map<String, Any?>
)

fun serializeArgs(vararg args: Any?, mapper: com.fasterxml.jackson.databind.ObjectMapper): Map<String, String> =
    args.mapIndexed { i, v -> "arg$i" to mapper.writeValueAsString(v) }.toMap()

fun buildScriptCall(task: KouTask, symbol: Any? = null): ScriptCall = ScriptCall(
    code        = task.sourceSnapshot
        ?: error("sourceSnapshot nulo para script"),
    functionName = task.functionName,
    paramsJson   = task.params,
    argTypes   = task.signature.first,
    symbol   = symbol,
    annotationParams = emptyMap()
)

fun buildParamsJson(
    types: List<String>,
    positionals: List<String>,
    params: Map<String, String>,
    flags: Set<String>
): Map<String, String> {
    val out = LinkedHashMap<String, String>(types.size)
    var pos = 0

    types.forEachIndexed { i, _ ->
        val key = "arg$i"

        val v = params[key]
            ?: positionals.getOrNull(pos)?.also { pos++ }
            ?: flags.firstOrNull { it.equals(key, ignoreCase = true) }
            ?: return@forEachIndexed

        out[key] = v
    }

    return out
}

object ScriptRunner {
    fun runScript(
        call: ScriptCall,
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

        val mapper = jacksonObjectMapper()
            .registerKotlinModule()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .also { it.setTypeFactory(it.typeFactory.withClassLoader(targetCL)) }

        val functionArgs: List<String> = call.argTypes
            ?: (extractExportFunctionSignature(call.code)?.first
                ?: error("No se pudo extraer firma de la función"))

        fun argIndex(k: String) = k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE

        val orderedParams = call.paramsJson.entries.sortedBy { argIndex(it.key) }
        val callArgs = ArrayList<Any?>(functionArgs.size)
        var userIdx = 0

        loop@ for (argName in functionArgs) {
            val inj = injector(argName)

            if (inj != null) {
                callArgs += inj
                continue@loop
            }

            val key = "arg$userIdx"
            val raw = orderedParams.firstOrNull { it.key == key }?.value
            val isNullable = argName.trim().endsWith("?")

            if (raw == null) {
                if (isNullable) {
                    callArgs += null
                    userIdx += 1
                    continue
                } else error("Falta '$key' en params para tipo '$argName'")
            }

            val token = raw.trim()

            val unwrapped = if (token.length >= 2 && token[0] == '"' &&
                (token.getOrNull(1) == '{' || token.getOrNull(1) == '[')
            ) {
                mapper.readValue(token, String::class.java)
            } else token

            val value: Any? =
                if (argName.isSimpleType()) {
                    when (argName.normalizeType()) {
                        "String" -> {
                            val isJsonString =
                                unwrapped.length >= 2 && unwrapped.first() == '"' && unwrapped.last() == '"'
                            if (isJsonString) mapper.readValue(unwrapped, String::class.java) else unwrapped
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
                } else if (looksLikeObjectLiteral(unwrapped!!)) {
                    normalizeObjectLiteralToJson(unwrapped)
                } else null

            if (value != null) {
                callArgs += value
            }

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

    private fun looksLikeObjectLiteral(s: String): Boolean = s.trim().let { it.startsWith("{") && it.endsWith("}") }

    private fun normalizeObjectLiteralToJson(src: String): String {
        val s = src.trim()
        require(s.startsWith("{") && s.endsWith("}")) { "Composed input debe verse como {k=v,...}" }
        val body = s.substring(1, s.length - 1).trim()
        if (body.isBlank()) return "{}"
        return buildString {
            append('{')
            val parts = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            parts.forEachIndexed { i, kv ->
                val (k, v) = kv.split('=', limit = 2).map { it.trim() }
                if (i > 0) append(',')
                append('"').append(k.replace("\"", "\\\"")).append('"').append(':')
                val isJsonish = v.startsWith("{") || v.startsWith("[") || v.startsWith("\"")
                if (isJsonish) append(v) else append('"').append(v.replace("\"", "\\\"")).append('"')
            }
            append('}')
        }
    }

    fun runScript(
        task: KouTask,
        symbol: Any? = null,
        injector: (String) -> Any? = { null }
    ): Any? = runScript(buildScriptCall(task, symbol), injector)
}
