package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.parsing.TextParserHtmlEmailTemplate
import com.koupper.providers.parsing.TextParserServiceProvider
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.extensions.splitKeyValue
import kotlin.test.assertTrue

class TextParserServiceProviderTest : AnnotationSpec() {

    init {
        TextParserServiceProvider().up()
    }

    @Test
    fun `should bind the html email template parser`() {
        assertTrue {
            app.createInstanceOf(TextParser::class, "TextParserHtmlEmailTemplate") is TextParserHtmlEmailTemplate
            app.createInstanceOf(TextParser::class, "TextParserEnvPropertiesTemplate") is TextParserEnvPropertiesTemplate
        }
    }

    @Ignore
    @Test
    fun `should get an env properties from resources`() {
        val parser = app.createInstanceOf(TextParser::class, "TextParserEnvPropertiesTemplate")
        val properties = parser.readFromResource(".env")

        assertTrue {
            parser.splitKeyValue("=".toRegex())["MODEL_PROJECT_URL"].equals("localhost:8080")
        }
    }
}