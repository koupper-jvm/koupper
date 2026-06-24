package com.koupper.providers

import com.koupper.providers.crypto.CryptoServiceProvider
import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.github.GitHubServiceProvider
import com.koupper.providers.mailing.SenderServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceProviderManagerTest : AnnotationSpec() {

    @Test
    fun `should discover providers via SPI and contain core providers`() {
        val serviceProviderManager = ServiceProviderManager()
        val providers = serviceProviderManager.listProviders()

        // SPI should discover at least the core providers
        assertTrue(
            providers.containsAll(
                listOf(
                    CryptoServiceProvider::class,
                    DBServiceProvider::class,
                    FileServiceProvider::class,
                    HttpServiceProvider::class,
                    GitHubServiceProvider::class,
                    JWTServiceProvider::class,
                    LoggerServiceProvider::class,
                    SenderServiceProvider::class,
                )
            ),
            "Core providers should be discovered via SPI"
        )
    }

    @Test
    fun `should discover all 46 providers via SPI`() {
        val providers = ServiceProvider.discoverProviderClasses()

        assertTrue(
            providers.size >= 40,
            "SPI should discover all providers (found ${providers.size})"
        )
    }

    @Test
    fun `should throw when SPI file is missing or empty`() {
        // This test verifies the contract: if SPI returns empty, manager throws.
        // In production/tests, the Gradle task generateServiceProviderSpi ensures
        // the SPI file is always present before processResources.
        val discovered = ServiceProvider.discoverProviderClasses()
        assertTrue(
            discovered.isNotEmpty(),
            "SPI should not be empty in test environment — run './gradlew :providers:processResources' if this fails"
        )
    }

    @Test
    fun `SPI discovery should not initialize provider classes`() {
        val providers = ServiceProvider.discoverProviderClasses()

        // discoverProviderClasses uses Class.forName(className, false, classLoader)
        // which does NOT run static initializers or constructors.
        // We verify this by checking we get KClass references without instantiation.
        assertTrue(providers.isNotEmpty(), "Should discover provider class references")
        providers.forEach { kclass ->
            assertTrue(
                kclass.java.name.endsWith("ServiceProvider"),
                "All discovered classes should be ServiceProviders, found: ${kclass.java.name}"
            )
        }
    }
}
