package com.koupper.providers.parsing

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class TextParserHtmlEmailTemplateTest : AnnotationSpec() {
    @Test
    fun `should bind a html using a map`() {
        val htmlEmailTemplate = TextParserHtmlEmailTemplate()

        val finalBinding = htmlEmailTemplate.bind(
                mapOf(
                        "logo" to "/images/logo.png",
                        "name" to "Jacob",
                        "email" to "jacob.gacosta@gmail.com"
                ),
                htmlEmailTemplate.readFromPath("/Users/jacobacosta/Code/kup-framework/octopus/src/main/resources/notifications/template.html")
        )

        assertTrue {
            "Jacob" in finalBinding
            "jacob.gacosta@gmail.com" in finalBinding
            "/images/logo.png" in finalBinding
        }
    }
}