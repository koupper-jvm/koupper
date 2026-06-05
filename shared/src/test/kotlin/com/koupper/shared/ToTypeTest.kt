package com.koupper.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToTypeTest {

    data class Sub(val value: String)
    data class Main(val id: Int, val sub: Sub)

    @Test
    fun `should convert map to nested data class`() {
        val map = mapOf(
            "id" to 1,
            "sub" to mapOf("value" to "hello")
        )
        
        val result = map.toType<Main>()
        
        assertEquals(1, result.id)
        assertEquals("hello", result.sub.value)
    }
}
