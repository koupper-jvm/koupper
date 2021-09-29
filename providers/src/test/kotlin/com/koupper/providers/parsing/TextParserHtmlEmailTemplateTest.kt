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
                "%user%" to "@user",
                "%password%" to "secret"
            ),
            StringBuilder(htmlEmailTemplate.readFromURL("https://iglyemailtemplates.s3.amazonaws.com/welcome-igly.html"))
        )

        assertTrue {
            "Jacob" in finalBinding
            "jacob.gacosta@gmail.com" in finalBinding
            "/images/logo.png" in finalBinding
        }
    }
}