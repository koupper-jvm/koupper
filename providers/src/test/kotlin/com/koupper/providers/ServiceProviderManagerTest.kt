package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.despatch.SenderServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.parsing.TextParserServiceProvider

class ServiceProviderManagerTest : AnnotationSpec() {
    @Test
    fun `should get a list of service providers`() {
        val serviceProviderManager = ServiceProviderManager()

        serviceProviderManager.listProviders().containsAll(
                listOf(
                        DBServiceProvider::class,
                        SenderServiceProvider::class,
                        TextParserServiceProvider::class,
                        LoggerServiceProvider::class
                )
        )
    }
}