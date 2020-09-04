package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.despatch.Sender
import com.koupper.providers.despatch.SenderHtmlEmail
import com.koupper.providers.despatch.SenderServiceProvider
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