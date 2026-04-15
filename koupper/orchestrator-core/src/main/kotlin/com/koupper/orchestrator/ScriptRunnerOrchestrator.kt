package com.koupper.orchestrator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.koupper.shared.isSimpleType
import com.koupper.shared.monitoring.ExecutionMeta
import com.koupper.shared.monitoring.ExecutionMonitor
import com.koupper.shared.monitoring.NoopExecutionMonitor
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.looksLikeObjectLiteral
import com.koupper.shared.octopus.normalizeObjectLiteralToJson
import com.koupper.shared.octopus.resolveClassFromArgName
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

data class ScriptCall(
    val functionName: String,
    val code: String,
    val argTypes: List<String>? = null,
    val paramsJson: Map<String, String> = emptyMap(),
    val symbol: Any? = null,
    val annotationParams: Map<String, Any?>,
    val context: String? = null,
    val scriptPath: String? = null,
    val kind: String = "KTS",
    val className: String? = null
)

private data class PendingJson(val json: String)

fun serializeArgs(vararg args: Any?, mapper: com.fasterxml.jackson.databind.ObjectMapper): Map<String, String> =
    args.mapIndexed { i, v -> "arg$i" to mapper.writeValueAsString(v) }.toMap()

fun buildScriptCall(task: KouTask, symbol: Any? = null): ScriptCall = ScriptCall(
    code = task.sourceSnapshot
        ?: error("sourceSnapshot nulo para script"),
    functionName = task.functionName,
    paramsJson = task.params,
    argTypes = task.signature.first,
    symbol = symbol,
    annotationParams = emptyMap(),
    context = task.context,
    scriptPath = task.scriptPath
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
            ?: return@forEachIndexed

        out[key] = v
    }

    return out
}

object ScriptRunner {
    var monitor: ExecutionMonitor = NoopExecutionMonitor

    private fun buildExportId(kind: String, name: String, scriptPath: String?): String {
        return when {
            kind == "KTS" && !scriptPath.isNullOrBlank() -> "${scriptPath.trim()}::$name"
            kind == "KTS" -> "kts::$name"
            else -> "kt::$name"
        }
    }

    private fun unescapeIfEscapedJson(s: String, mapper: com.fasterxml.jackson.databind.ObjectMapper): String {
        val t = s.trim()

        if (t.startsWith("{") || t.startsWith("[")) {
            if (t.contains("\\\"") || t.contains("\\\\")) {
                return t
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim()
            }
            return t
        }

        if (t.startsWith("\\{") || t.startsWith("\\[")) {
            val wrapped = buildString {
                append('"')
                for (ch in t) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        else -> append(ch)
                    }
                }
                append('"')
            }
            return mapper.readValue(wrapped, String::class.java)
        }

        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return mapper.readValue(t, String::class.java)
        }

        return t
    }

    private fun decodeCliEscapedString(token: String, mapper: com.fasterxml.jackson.databind.ObjectMapper): String {
        val t = token.trim()

        if (runCatching { mapper.readTree(t); true }.getOrDefault(false)) return t

        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            val inner = runCatching { mapper.readValue(t, String::class.java).trim() }.getOrNull()
            if (inner != null && runCatching { mapper.readTree(inner); true }.getOrDefault(false)) {
                return inner
            }
        }

        val unescaped = t
            .removePrefix("\\\"").removeSuffix("\\\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()

        if (runCatching { mapper.readTree(unescaped); true }.getOrDefault(false)) return unescaped

        if (unescaped.length >= 2 && unescaped.first() == '"' && unescaped.last() == '"') {
            val inner = runCatching { mapper.readValue(unescaped, String::class.java).trim() }.getOrNull()
            if (inner != null && runCatching { mapper.readTree(inner); true }.getOrDefault(false)) {
                return inner
            }
        }

        var current = t
        repeat(5) {
            val next = current
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
                .let {
                    if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.drop(1).dropLast(1) else it
                }
            if (runCatching { mapper.readTree(next); true }.getOrDefault(false)) return next
            current = next
        }

        return t
    }

    private fun stripGenericsAndNullability(typeName: String): String {
        val t = typeName.trim().removeSuffix("?")
        val i = t.indexOf('<')
        return if (i >= 0) t.substring(0, i).trim() else t
    }

    fun runScript(
        call: ScriptCall,
        injector: (String) -> Any? = { null }
    ): Any? {
        val anyRef = call.symbol ?: error("Símbolo no encontrado: ${call.functionName}")

        val target: Any = when (anyRef) {
            is KProperty0<*> -> {
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
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_YAML_COMMENTS, true)
            .also { it.setTypeFactory(it.typeFactory.withClassLoader(targetCL)) }

        val functionArgs: List<String> = call.argTypes
            ?: (extractExportFunctionSignature(call.code)?.parameterTypes
                ?: error("No se pudo extraer firma de la función"))

        val functionSignature = extractExportFunctionSignature(call.code)
        val inferredClassName = call.className
            ?: target::class.java.enclosingClass?.name
            ?: target::class.java.name.substringBefore("$", missingDelimiterValue = target::class.java.name)

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
                    continue@loop
                } else {
                    error("Falta '$key' en params para tipo '$argName'")
                }
            }

            val token = raw.trim()

            val unwrapped = if (token.length >= 2 && token[0] == '"' &&
                (token.getOrNull(1) == '{' || token.getOrNull(1) == '[')
            ) {
                mapper.readValue(token, String::class.java)
            } else token

            val value: Any? =
                if (argName.isSimpleType()) {
                    val u = unwrapped.trim()
                    when (argName.normalizeType()) {
                        "String" -> {
                            when {
                                u.contains("\\\"") || u.contains("\\\\") -> decodeCliEscapedString(u, mapper)
                                u.length >= 2 && u.first() == '"' && u.last() == '"' -> mapper.readValue(u, String::class.java)
                                else -> u
                            }
                        }
                        "Int" -> mapper.readValue(u, Int::class.java)
                        "Long" -> mapper.readValue(u, java.lang.Long::class.java)
                        "Double" -> mapper.readValue(u, java.lang.Double::class.java)
                        "Boolean" -> mapper.readValue(u, java.lang.Boolean::class.java)
                        "Float" -> mapper.readValue(u, java.lang.Float::class.java)
                        "Short" -> mapper.readValue(u, java.lang.Short::class.java)
                        "Byte" -> mapper.readValue(u, java.lang.Byte::class.java)
                        "Char" -> mapper.readValue(u, String::class.java).single()
                        else -> mapper.readValue(u, Any::class.java)
                    }
                } else {
                    val preDecoded = if (unwrapped.contains("\\\"") || unwrapped.contains("\\\\")) {
                        decodeCliEscapedString(unwrapped.trim(), mapper)
                    } else {
                        unwrapped.trim()
                    }

                    val t0 = unescapeIfEscapedJson(preDecoded, mapper).trim()
                    val t = if (t0.equals("EMPTY_PARAMS", ignoreCase = true)) "" else t0

                    val tClean = if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                        t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    } else t

                    val pending = try {
                        if (tClean.isBlank()) {
                            PendingJson("{}")
                        } else {
                            // Sin Regex pendejos, confiamos en el mapper permisivo
                            mapper.readTree(tClean)
                            PendingJson(tClean)
                        }
                    } catch (e: Exception) {
                        error("Input inválido para '$key'. Jackson falló: ${e.message}")
                    }

                    callArgs += pending
                    userIdx += 1
                    continue@loop
                }

            callArgs += value
            userIdx += 1
        }

        val lambdaClass = target::class.java

        val allInvokes = (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
            .filter { it.name == "invoke" }
            .toList()

        val invoke = allInvokes.asSequence()
            .filter { it.parameterCount == callArgs.size }
            .sortedWith(
                compareBy<java.lang.reflect.Method>(
                    { m -> m.parameterTypes.count { it == Object::class.java || it == Any::class.java } },
                    { m -> if (m.isBridge || m.isSynthetic) 1 else 0 }
                )
            )
            .firstOrNull()
            ?: allInvokes.asSequence()
                .firstOrNull {
                    it.parameterCount == callArgs.size + 1 &&
                            it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
                }?.also { error("La función '${call.functionName}' es suspend (Continuation no soportado).") }
            ?: error("No hay 'invoke' con aridad ${callArgs.size} en ${lambdaClass.name}")

        for (i in callArgs.indices) {
            val v = callArgs[i]
            if (v is PendingJson) {
                val json = v.json.trim()
                val expectedArgName = functionArgs.getOrNull(i) ?: ""
                val signatureArgName = functionSignature?.parameterTypes?.getOrNull(i)
                val paramTypeReflection = invoke.parameterTypes.getOrNull(i)
                val expectedClass = if (paramTypeReflection != null && paramTypeReflection != Object::class.java && paramTypeReflection != Any::class.java) {
                    paramTypeReflection
                } else {
                    functionSignature?.let {
                        resolveClassFromArgName(signatureArgName ?: expectedArgName, it, targetCL, inferredClassName)
                    }
                }

                if (expectedClass != null) {
                    callArgs[i] = mapper.readValue(json, expectedClass)
                } else {
                    callArgs[i] = json
                }
            }
        }

        try {
            invoke.isAccessible = true
        } catch (_: Exception) {
            try {
                val ao = Class.forName("java.lang.reflect.AccessibleObject")
                val m = ao.getMethod("trySetAccessible")
                m.invoke(invoke)
            } catch (_: Throwable) { }
        }

        val oldCl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = lambdaClass.classLoader

        val meta = ExecutionMeta(
            exportId = buildExportId(call.kind, call.functionName, call.scriptPath),
            kind = call.kind,
            context = call.context,
            scriptPath = call.scriptPath
        )

        return monitor.track(meta) {
            try {
                invoke.invoke(target, *callArgs.toTypedArray())
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
            }
        }
    }

    fun runScript(
        task: KouTask,
        symbol: Any? = null,
        injector: (String) -> Any? = { null }
    ): Any? = runScript(buildScriptCall(task, symbol), injector)

    private fun exportIdFromSymbol(symbol: Any): String {
        return when (symbol) {
            is KProperty0<*> -> "kt::${symbol.name}"
            else -> "kt::${symbol::class.qualifiedName ?: symbol.toString()}"
        }
    }

    fun executeFunction(
        symbol: Any,
        paramsValues: List<Any?> = emptyList(),
        injector: (String) -> Any? = { null }
    ): Any? {
        val target: Any = when (symbol) {
            is KProperty0<*> -> {
                symbol.isAccessible = true
                symbol.get() ?: error("Property reference is null")
            }
            else -> symbol
        }

        val lambdaClass = target::class.java
        val invoke = (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == "invoke" && it.parameterCount >= paramsValues.size }
            ?: error("No suitable 'invoke' found in ${lambdaClass.name}")

        val finalParams = mutableListOf<Any?>()
        val reflectedParams = invoke.parameters

        for ((index, param) in reflectedParams.withIndex()) {
            val injected = injector(param.name ?: "")
            finalParams += when {
                injected != null -> injected
                index < paramsValues.size -> paramsValues[index]
                else -> null
            }
        }

        try {
            invoke.isAccessible = true
        } catch (_: Exception) {
            try {
                val ao = Class.forName("java.lang.reflect.AccessibleObject")
                val m = ao.getMethod("trySetAccessible")
                m.invoke(invoke)
            } catch (_: Throwable) { }
        }

        val meta = ExecutionMeta(
            exportId = exportIdFromSymbol(symbol),
            kind = "KT",
            context = null,
            scriptPath = null
        )

        return monitor.track(meta) {
            invoke.invoke(target, *finalParams.toTypedArray())
        }
    }
}
