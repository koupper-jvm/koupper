package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.parsing.*
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class TextJsonParserServiceProviderTest : AnnotationSpec() {
    init {
        TextJsonParserServiceProvider().up()
    }

    @Test
    fun `should bind the text json parser`() {
        val instance = app.createInstanceOf(TextJsonParser::class)

        assertTrue {
            instance is JsonToObject<*>
        }
    }
}