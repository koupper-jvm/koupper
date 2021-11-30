package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.crypto.AESGCM128
import com.koupper.providers.crypto.Crypt0
import com.koupper.providers.crypto.CryptoServiceProvider
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class CryptoServiceProviderTest : AnnotationSpec() {
    private var envs: Map<String, String> = mapOf(
        "SHARED_SECRET" to "sharedsecret",
    )

    @Test
    fun `should bind the crypt0 implementation`() {
        withEnvironment(envs) {
            CryptoServiceProvider().up()

            assertTrue {
                app.createInstanceOf(Crypt0::class) is AESGCM128
            }
        }
    }
}