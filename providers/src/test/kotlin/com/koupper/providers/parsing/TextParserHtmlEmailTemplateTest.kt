package com.koupper.providers.parsing

import io.kotest.core.spec.style.AnnotationSpec
import java.lang.StringBuilder
import kotlin.test.assertTrue

class TextParserHtmlEmailTemplateTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should bind data in html template using a map`() {
        val htmlEmailTemplate = TextReader()

        val finalBinding = htmlEmailTemplate.bind(
                mapOf(
                        "logo" to "/images/logo.png",
                        "name" to "Jacob",
                        "email" to "jacob.gacosta@gmail.com"
                ),
                StringBuilder(htmlEmailTemplate.readFromPath("yourPath"))
        )

        assertTrue {
            "Jacob" in finalBinding
            "jacob.gacosta@gmail.com" in finalBinding
            "/images/logo.png" in finalBinding
        }
    }
}