package com.koupper.providers

abstract class ServiceProvider {
    abstract fun up()

    /**
     * Returns pairs of (functionName, sourceCode) for top-level functions
     * that will be automatically injected into all Koupper scripts.
     */
    open fun topLevelFunctions(): Map<String, String> = emptyMap()
}
