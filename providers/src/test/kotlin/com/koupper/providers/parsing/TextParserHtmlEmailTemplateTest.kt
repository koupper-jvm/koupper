package com.koupper.providers.parsing

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class TextParserHtmlEmailTemplateTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should bind data in html template using a map`() {
        val htmlEmailTemplate = TextParserHtmlEmailTemplate()

        val finalBinding = htmlEmailTemplate.bind(
                mapOf(
                        "logo" to "/images/logo.png",
                        "name" to "Jacob",
                        "email" to "jacob.gacosta@gmail.com"
                ),
                htmlEmailTemplate.readFromPath("yuor-template.html")
        )

        assertTrue {
            "Jacob" in finalBinding
            "jacob.gacosta@gmail.com" in finalBinding
            "/images/logo.png" in finalBinding
        }
    }
}