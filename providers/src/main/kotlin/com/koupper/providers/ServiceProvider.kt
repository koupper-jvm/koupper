package com.koupper.providers

import kotlin.reflect.KClass

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

    /**
     * Returns the KClass references of other ServiceProviders that this
     * provider depends on and must be initialized before it.
     */
    open fun dependencies(): List<KClass<out ServiceProvider>> = emptyList()

    companion object {
        private const val SPI_PATH = "META-INF/services/com.koupper.providers.ServiceProvider"

        /**
         * Discovers all ServiceProvider KClass references via the SPI services file
         * without initializing the classes. Reads META-INF/services/ directly.
         */
        fun discoverProviderClasses(): List<KClass<out ServiceProvider>> {
            val classLoader = ServiceProvider::class.java.classLoader
            val resources = classLoader.getResources(SPI_PATH)
            val classNames = mutableSetOf<String>()

            while (resources.hasMoreElements()) {
                resources.nextElement().openStream().bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            classNames.add(trimmed)
                        }
                    }
                }
            }

            return classNames.mapNotNull { className ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    Class.forName(className, false, classLoader).kotlin as KClass<out ServiceProvider>
                } catch (e: ClassNotFoundException) {
                    null
                }
            }
        }
    }
}
