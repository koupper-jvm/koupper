package com.koupper.providers.helloworld

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class HelloWorldProviderTest {
    @Test
    fun `should ping successfully`() {
        val provider = HelloWorldImpl()
        val response = provider.ping()
        
        assertTrue(response.ok)
        assertEquals("pong from HelloWorld", response.message)
    }
}