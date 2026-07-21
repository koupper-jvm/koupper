package com.koupper.octopus.annotations

import java.util.concurrent.ConcurrentHashMap

/** Isolated registry for reactive triggers — no java.nio.file dependency, safe to import from scripts. */
object TriggerRegistry {
    val entries = ConcurrentHashMap<String, Map<String, Any?>>()
}
