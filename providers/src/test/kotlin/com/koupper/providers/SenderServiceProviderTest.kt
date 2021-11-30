package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.mailing.Sender
import com.koupper.providers.mailing.SenderHtmlEmail
import com.koupper.providers.mailing.SenderServiceProvider
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class SenderServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the html email sender`() {
        val envs = mapOf(
            "MAIL_HOST" to "somehost",
            "MAIL_PORT" to "someport",
            "MAIL_USERNAME" to "somedbname",
            "PASSWORD" to "someusername",
        )

        withEnvironment(envs) {
            SenderServiceProvider().up()

            assertTrue {
                app.createInstanceOf(Sender::class) is SenderHtmlEmail
            }
        }
    }
}