package com.koupper.providers.helloworld

import com.koupper.providers.ServiceProvider

/**
 * Contract for the HelloWorld provider.
 */
interface HelloWorldProvider {
    /**
     * Example operation for HelloWorld.
     */
    fun ping(): HelloWorldResponse
}

data class HelloWorldResponse(
    val ok: Boolean,
    val message: String,
    val metadata: Map<String, Any?> = emptyMap()
)