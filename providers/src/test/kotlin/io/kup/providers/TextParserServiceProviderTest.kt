package io.kup.providers

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.app
import io.kup.container.extensions.instanceOf
import io.kup.providers.parsing.TextParserHtmlEmailTemplate
import io.kup.providers.parsing.TextParserServiceProvider
import io.kup.providers.parsing.TextParser
import io.kup.providers.parsing.TextParserEnvPropertiesTemplate
import kotlin.test.assertTrue

class TextParserServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the html email template parser`() {
        TextParserServiceProvider().up()

        assertTrue {
            app.create("TextParserHtmlEmailTemplate").instanceOf<TextParser>() is TextParserHtmlEmailTemplate
            app.create("TextParserEnvPropertiesTemplate").instanceOf<TextParser>() is TextParserEnvPropertiesTemplate
        }
    }
}