package com.koupper.logging

import com.koupper.shared.runtime.ScriptBackend
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

inline fun <reified F> evalExport(engine: ScriptBackend, sentenceName: String): F {

    @Suppress("UNCHECKED_CAST")
    return engine.eval(sentenceName) as F
}
