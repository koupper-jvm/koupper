package io.kup.providers

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.providers.db.DBServiceProvider
import io.kup.providers.despatch.SenderServiceProvider
import io.kup.providers.parsing.TextParserServiceProvider

class ServiceProviderManagerTest : AnnotationSpec() {
    @Test
    fun `should get a list of service providers`() {
        val serviceProviderManager = ServiceProviderManager()

        serviceProviderManager.listProviders().containsAll(
                listOf(
                        DBServiceProvider::class,
                        SenderServiceProvider::class,
                        TextParserServiceProvider::class
                )
        )
    }
}