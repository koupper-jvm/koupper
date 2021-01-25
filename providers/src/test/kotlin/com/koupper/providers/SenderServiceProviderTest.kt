package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.mailing.Sender
import com.koupper.providers.mailing.SenderHtmlEmail
import com.koupper.providers.mailing.SenderServiceProvider
import kotlin.test.assertTrue

class SenderServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the html email sender`() {
        SenderServiceProvider().up()

        assertTrue {
            app.createInstanceOf(Sender::class) is SenderHtmlEmail
        }
    }
}