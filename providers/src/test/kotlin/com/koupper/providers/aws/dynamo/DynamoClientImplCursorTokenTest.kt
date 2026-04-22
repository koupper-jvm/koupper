package com.koupper.providers.aws.dynamo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoClientImplCursorTokenTest {

    @Test
    fun `cursor token roundtrip preserves key attributes`() {
        val client = DynamoClientImpl()
        val cursor = mapOf(
            "pk" to AttributeValue.builder().s("USER#123").build(),
            "sk" to AttributeValue.builder().n("42").build()
        )

        val token = client.encodeCursorToBase64(cursor)
        val decoded = client.decodeCursorFromBase64(token)

        assertNotNull(token)
        assertNotNull(decoded)
        assertEquals("USER#123", decoded["pk"]?.s())
        assertEquals("42", decoded["sk"]?.n())
    }

    @Test
    fun `decode returns null for empty token`() {
        val client = DynamoClientImpl()
        assertEquals(null, client.decodeCursorFromBase64(null))
        assertEquals(null, client.decodeCursorFromBase64(""))
    }

    @Test
    fun `encoded cursor is url safe`() {
        val client = DynamoClientImpl()
        val cursor = mapOf("pk" to AttributeValue.builder().s("abc+/=").build())

        val token = client.encodeCursorToBase64(cursor)

        assertNotNull(token)
        assertTrue(token.none { it == '+' || it == '/' || it == '=' })
    }
}
