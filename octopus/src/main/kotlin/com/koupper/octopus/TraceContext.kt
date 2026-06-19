package com.koupper.octopus

import java.util.UUID

object TraceContext {
    private val traceId = ThreadLocal<String>()

    fun get(): String = traceId.get() ?: "unknown"

    fun set(id: String) { traceId.set(id) }

    fun generate(): String {
        val id = UUID.randomUUID().toString().take(8)
        traceId.set(id)
        return id
    }

    fun clear() { traceId.remove() }
}
