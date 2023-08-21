package com.koupper.providers

import com.koupper.providers.crypto.CryptoServiceProvider
import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.mailing.SenderServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import kotlin.test.assertTrue

class ServiceProviderManagerTest : AnnotationSpec() {
    @Test
    fun `should get a list of service providers`() {
        val serviceProviderManager = ServiceProviderManager()

        assertTrue {
            serviceProviderManager.listProviders().containsAll(
                listOf(
                    CryptoServiceProvider::class,
                    DBServiceProvider::class,
                    FileServiceProvider::class,
                    HttpServiceProvider::class,
                    JWTServiceProvider::class,
                    LoggerServiceProvider::class,
                    SenderServiceProvider::class,
                )
            )
        }
    }
}