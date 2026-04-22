package com.koupper.octopus.modules.http

class RouterDsl {
    private var basePath: String = ""
    private val routes = mutableListOf<RegisteredRoute>()

    fun path(block: () -> String) {
        basePath = block()
    }

    fun <I : Any, O : Any> post(block: PostDsl<I, O>.() -> Unit) {
        routes += PostDsl<I, O>(basePath).apply(block).build()
    }

    fun <I : Any, O : Any> put(block: PutDsl<I, O>.() -> Unit) {
        routes += PutDsl<I, O>(basePath).apply(block).build()
    }

    fun <O : Any> get(block: GetDsl<O>.() -> Unit) {
        routes += GetDsl<O>(basePath).apply(block).build()
    }

    fun <O : Any> delete(block: DeleteDsl<O>.() -> Unit) {
        routes += DeleteDsl<O>(basePath).apply(block).build()
    }

    fun build(): List<RegisteredRoute> = routes
}
