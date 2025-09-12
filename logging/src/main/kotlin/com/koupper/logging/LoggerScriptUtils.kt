package com.koupper.logging

import com.koupper.shared.octopus.extractExportFunctionName
import javax.script.ScriptEngine

inline fun <T> withScriptLogger(
    scriptLogger: KLogger,
    mdc: Map<String, String> = emptyMap(),
    block: () -> T
): T {
    val prev = GlobalLogger.log
    return try {
        GlobalLogger.setLogger(scriptLogger)
        ScopedMDC(mdc).use { block() }
    } finally {
        GlobalLogger.setLogger(prev)
    }
}

fun exportName(sentence: String): String =
    extractExportFunctionName(sentence) ?: error("No @Export function found")

inline fun <reified F> evalExport(engine: ScriptEngine, sentence: String): F {
    val name = exportName(sentence)
    @Suppress("UNCHECKED_CAST")
    return engine.eval(name) as F
}
