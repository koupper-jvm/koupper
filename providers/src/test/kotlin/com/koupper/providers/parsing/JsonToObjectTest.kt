package com.koupper.providers.parsing

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals

class JsonToObjectTest : AnnotationSpec() {
    data class Example(val property: String)

    @Test
    fun `should map a json object to kotlin object`() {
        val jsonToObject = JsonToObject<Example>()
        jsonToObject.load("""{"property": "value"}""")

        val example = jsonToObject.toType<Example>()

        assertEquals("value", example.property)
    }
}