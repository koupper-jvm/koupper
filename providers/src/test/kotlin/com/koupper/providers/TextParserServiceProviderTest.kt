package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.parsing.*
import com.koupper.providers.parsing.extensions.splitKeyValue
import kotlin.test.assertTrue

class TextParserServiceProviderTest : AnnotationSpec() {

    init {
        TextParserServiceProvider().up()
    }

    @Test
    fun `should bind the text parser`() {
        assertTrue {
            app.createInstanceOf(TextParser::class) is TextReader
        }
    }

    @Ignore
    @Test
    fun `should get an env properties from resources`() {
        val parser = app.createInstanceOf(TextParser::class)

        val properties = parser.readFromResource(".env")

        assertTrue {
            parser.splitKeyValue("=".toRegex())["MODEL_PROJECT_URL"].equals("localhost:8080")
        }
    }
}