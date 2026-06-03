package com.koupper.providers

abstract class ServiceProvider {
    abstract fun up()

    /**
     * Returns pairs of (functionName, sourceCode) for top-level functions
     * that will be automatically injected into all Koupper scripts.
     */
    open fun topLevelFunctions(): Map<String, String> = emptyMap()

    /**
     * Returns a list of Maven coordinates (GAV) that this provider
     * needs to operate (e.g., "org.postgresql:postgresql:42.7.2").
     */
    open fun externalDependencies(): List<String> = emptyList()
}
