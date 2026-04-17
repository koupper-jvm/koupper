package com.koupper.providers.aws.dynamo

import kotlin.test.Test
import kotlin.test.assertTrue
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoClientImplAttributeConversionTest {

    @Test
    fun `convertAttributeValue handles empty map and list`() {
        val client = DynamoClientImpl()
        val method = client.javaClass.getDeclaredMethod("convertAttributeValue", AttributeValue::class.java)
        method.isAccessible = true

        val emptyMapAttr = AttributeValue.builder().m(emptyMap()).build()
        val emptyListAttr = AttributeValue.builder().l(emptyList()).build()

        val mapResult = method.invoke(client, emptyMapAttr)
        val listResult = method.invoke(client, emptyListAttr)

        assertTrue(mapResult is Map<*, *>)
        assertTrue((mapResult as Map<*, *>).isEmpty())
        assertTrue(listResult is List<*>)
        assertTrue((listResult as List<*>).isEmpty())
    }
}
