package io.kup.providers

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.app
import io.kup.container.extensions.instanceOf
import io.kup.providers.despatch.Sender
import io.kup.providers.despatch.SenderHtmlEmail
import io.kup.providers.despatch.SenderServiceProvider
import kotlin.test.assertTrue

class SenderServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the html email sender`() {
        SenderServiceProvider().up()

        assertTrue {
            app.create().instanceOf<Sender>() is SenderHtmlEmail
        }
    }
}