package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.parsing.TextParserHtmlEmailTemplate
import com.koupper.providers.parsing.TextParserServiceProvider
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import kotlin.test.assertTrue

class TextParserServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the html email template parser`() {
        TextParserServiceProvider().up()

        assertTrue {
            app.createInstanceOf(TextParser::class, "TextParserHtmlEmailTemplate") is TextParserHtmlEmailTemplate
            app.createInstanceOf(TextParser::class, "TextParserEnvPropertiesTemplate") is TextParserEnvPropertiesTemplate
        }
    }
}