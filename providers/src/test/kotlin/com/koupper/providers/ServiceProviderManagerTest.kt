package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.mailing.SenderServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.parsing.TextJsonParserServiceProvider
import com.koupper.providers.parsing.TextParserServiceProvider
import kotlin.test.assertTrue

class ServiceProviderManagerTest : AnnotationSpec() {
    @Test
    fun `should get a list of service providers`() {
        val serviceProviderManager = ServiceProviderManager()

        assertTrue {
            serviceProviderManager.listProviders().containsAll(
                    listOf(
                            TextParserServiceProvider::class,
                            DBServiceProvider::class,
                            SenderServiceProvider::class,
                            LoggerServiceProvider::class,
                            HttpServiceProvider::class,
                            TextJsonParserServiceProvider::class,
                            FileServiceProvider::class
                    )
            )
        }
    }
}