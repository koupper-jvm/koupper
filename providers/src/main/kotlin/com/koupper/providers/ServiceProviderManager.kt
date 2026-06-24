package com.koupper.providers

import kotlin.reflect.KClass

val launchProcess: (() -> Unit) -> Thread = { callback ->
    val thread = Thread {
        callback()
    }

    thread.start()

    waitFor(thread).join()

    thread
}

val waitFor: (Thread) -> Thread = { thread ->
    val loading = Thread {
        val a = arrayOf("\u2058", "\u2059", "\u205A", "\u205B", "\u205C")

        while (thread.isAlive) {
            print("building ${a.random()}")
            Thread.sleep(200L)
            print("\r")
        }
    }

    loading.start()

    loading
}

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        val discovered = ServiceProvider.discoverProviderClasses()

        if (discovered.isEmpty()) {
            throw IllegalStateException(
                "No ServiceProviders discovered via SPI. " +
                "Ensure META-INF/services/com.koupper.providers.ServiceProvider exists " +
                "and the Gradle task 'generateServiceProviderSpi' has run. " +
                "If running tests from IDE, execute './gradlew :providers:processResources' first."
            )
        }

        return discovered
    }

    /**
     * Returns providers filtered by tier.
     * Useful for CI gates: e.g., run full suite only for CORE providers.
     */
    fun listProvidersByTier(tier: ProviderTier): List<KClass<*>> {
        return listProviders().filter { providerClass ->
            val instance = providerClass.constructors.firstOrNull()?.call() as? ServiceProvider
            instance?.tier() == tier
        }
    }
}
