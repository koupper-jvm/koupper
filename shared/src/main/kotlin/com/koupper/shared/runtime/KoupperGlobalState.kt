package com.koupper.shared.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * KoupperGlobalState is a process-level anchor.
 * 
 * Since this lives in the 'shared' module (Parent ClassLoader), 
 * it is visible to both the Octopus Daemon and all isolated scripts.
 * 
 * It provides a safe way to share infrastructure state like web routes,
 * database pools, or agent queues across ClassLoader boundaries.
 */
object KoupperGlobalState {
    private val state = ConcurrentHashMap<String, Any>()

    fun set(key: String, value: Any) {
        state[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(key: String, defaultValue: () -> T): T {
        return state.getOrPut(key) { defaultValue() as Any } as T
    }

    fun has(key: String): Boolean = state.containsKey(key)

    fun get(key: String): Any? = state[key]
}
