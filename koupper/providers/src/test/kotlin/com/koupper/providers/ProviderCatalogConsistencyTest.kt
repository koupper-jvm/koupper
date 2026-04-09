package com.koupper.providers

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProvidersCatalog(val providers: List<ProviderCatalogEntry> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProviderCatalogEntry(val serviceProvider: String)

class ProviderCatalogConsistencyTest : AnnotationSpec() {
    @Test
    fun `catalog should include all registered service providers`() {
        val managerProviders = ServiceProviderManager()
            .listProviders()
            .mapNotNull { it.simpleName }
            .toSet()

        val catalogStream = javaClass.classLoader.getResourceAsStream("providers-catalog.json")
        requireNotNull(catalogStream) { "providers-catalog.json not found in test classpath" }

        val catalogProviders = catalogStream.use {
            jacksonObjectMapper().readValue<ProvidersCatalog>(it).providers.map { entry -> entry.serviceProvider }.toSet()
        }

        val allowedCatalogOnlyEntries = setOf("TerminalRuntime")
        val missingInCatalog = managerProviders - catalogProviders
        val unexpectedCatalogEntries = (catalogProviders - managerProviders) - allowedCatalogOnlyEntries

        assertTrue(missingInCatalog.isEmpty(), "Missing in providers-catalog.json: $missingInCatalog")
        assertTrue(unexpectedCatalogEntries.isEmpty(), "Unexpected entries in providers-catalog.json: $unexpectedCatalogEntries")
    }
}
