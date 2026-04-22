package com.koupper.providers.aws.dynamo

import io.kotest.core.spec.style.AnnotationSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamoLocalAdminProviderTest : AnnotationSpec() {
    @Test
    fun `ensure table should create only when missing`() {
        val client = mockk<DynamoClient>(relaxed = true)
        every { client.doesTableExist("users") } returnsMany listOf(false, true)

        val admin = DynamoLocalAdminImpl(client)

        val first = admin.ensureTable(
            DynamoTableSpec(
                tableName = "users",
                keySchema = listOf("id" to "HASH"),
                attributeDefinitions = listOf("id" to "S")
            )
        )
        val second = admin.ensureTable(
            DynamoTableSpec(
                tableName = "users",
                keySchema = listOf("id" to "HASH"),
                attributeDefinitions = listOf("id" to "S")
            )
        )

        assertTrue(first)
        assertFalse(second)
        verify(exactly = 1) {
            client.createTable("users", any(), any(), any())
        }
    }

    @Test
    fun `truncate table should delete scanned keys`() {
        val client = mockk<DynamoClient>(relaxed = true)
        every { client.scanItems("users") } returns listOf(
            mapOf("id" to "1"),
            mapOf("id" to "2"),
            mapOf("other" to "skip")
        )

        val admin = DynamoLocalAdminImpl(client)
        val deleted = admin.truncateTable("users", listOf("id"))

        assertEquals(2, deleted)
        verify(exactly = 2) { client.deleteItem("users", any()) }
    }
}
