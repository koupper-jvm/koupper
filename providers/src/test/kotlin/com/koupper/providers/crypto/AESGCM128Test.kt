package com.koupper.providers.crypto

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class AESGCM128Test : AnnotationSpec() {
    private lateinit var container: Container

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)

        this.container = app

        CryptoServiceProvider().up()
    }

    @Test
    fun `should encrypt an decrypt text`() {
        withEnvironment("SHARED_SECRET", "A?D(G+KbPeShVmYq") {
            val aes = AESGCM128(this.container)

            val encryptedRawText = aes.encrypt("raw text".toByteArray(), byteArrayOf())

            val decryptedRawText = aes.decrypt(encryptedRawText, aes.IV, aes.authData)

            assertEquals("raw text", String(decryptedRawText, StandardCharsets.UTF_8))
        }
    }
}