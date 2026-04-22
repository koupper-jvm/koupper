package com.koupper.logging

import com.koupper.shared.runtime.ScriptBackend
import javax.script.ScriptEngine

inline fun <T> withScriptLogger(
    scriptLogger: KLogger,
    mdc: Map<String, String> = emptyMap(),
    streamRouting: StreamRoutingConfig? = null,
    block: () -> T
): T {
    val prev = GlobalLogger.log
    val prevRouting = StreamRoutingContext.get()
    return try {
        GlobalLogger.setLogger(scriptLogger)
        if (streamRouting != null) {
            StreamRoutingContext.set(streamRouting)
        }
        ScopedMDC(mdc).use { block() }
    } finally {
        StreamRoutingContext.set(prevRouting)
        GlobalLogger.setLogger(prev)
    }
}

inline fun <reified F> evalExport(engine: ScriptBackend, sentenceName: String): F {

    @Suppress("UNCHECKED_CAST")
    return engine.eval(sentenceName) as F
}
