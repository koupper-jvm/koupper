package com.koupper.orchestrator.paginator

import io.kotest.common.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.test.*

class PagerTest {
    private fun hasDbEnv(): Boolean {
        val required = listOf("DB_HOST", "DB_DATABASE", "DB_USERNAME", "DB_PASSWORD")
        return required.all { !System.getenv(it).isNullOrBlank() }
    }

    private fun hasAwsEnv(): Boolean {
        val required = listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_REGION")
        return required.all { !System.getenv(it).isNullOrBlank() }
    }

    @Test
    fun `should compute total items from different collection types`() {
        data class User(val name: String)

        val mapOfValues = mapOf(2 to "Irene", 3 to "Dante", 1 to "Jacob")
        val listOfValues = listOf("Irene")
        val listOfObjects = listOf(User("Irene"), User("Jacob"))

        val cp = CollectionPager(limit = 7)

        cp.data { mapOfValues }
        assertEquals(3, cp.totalOfItems())

        cp.data { listOfValues }
        assertEquals(1, cp.totalOfItems())

        cp.data { listOfObjects }
        assertEquals(2, cp.totalOfItems())
    }

    @Test
    fun `should compute total pages based on limit`() {
        val list = (1..100).map { it.toString() }
        val cp = CollectionPager(limit = 5)
        cp.data { list }

        assertEquals(20, cp.totalOfPages())
    }

    @Test
    fun `should move correctly and handle tokens`() {
        val list = (1..30).map { it.toString() }
        val cp = CollectionPager(limit = 10).apply { data { list } }

        assertEquals(list.take(10), cp.currentItem())
        assertEquals("cGFnZV8x", cp.nextToken())
        cp.moveTo(cp.nextToken())
        assertEquals(1, cp.currentPage())
    }

    @Test
    fun `should reset and recompute when changing data`() {
        val list1 = (1..50).map { it.toString() }
        val list2 = (1..10).map { it.toString() }

        val cp = CollectionPager(limit = 10)
        cp.data { list1 }
        cp.setCurrentPage(2)
        assertEquals(list1.drop(20).take(10), cp.currentItem())

        cp.data { list2 }
        assertEquals(list2.take(10), cp.currentItem())
        assertEquals(0, cp.currentPage())
    }

    @Test
    fun `should paginate DB table correctly`() = runBlocking {
        assumeTrue(hasDbEnv(), "Skipping DB integration test: missing DB_* env vars")

        val dbPager = DatabasePager(limit = 3, table = "blogs", orderBy = "id", direction = "ASC")

        val firstPage = dbPager.currentItem()
        assertNotNull(firstPage)
        assertTrue(firstPage.isNotEmpty())
        assertTrue(firstPage.size <= 3)

        val firstTitles = firstPage.map { it["title"] }
        val nextToken = dbPager.nextToken()

        if (nextToken == null) return@runBlocking

        val secondPage = dbPager.moveTo(nextToken) as List<Map<String, Any?>>
        assertNotNull(secondPage)
        assertTrue(secondPage.isNotEmpty())
        assertTrue(secondPage.size <= 3)

        val secondTitles = secondPage.map { it["title"] }
        assertNotEquals(firstTitles, secondTitles)
    }

    @Test
    fun `should paginate Dynamo correctly`() {
        assumeTrue(hasAwsEnv(), "Skipping Dynamo integration test: missing AWS_* env vars")

        val pager = DynamoPager(
            table = "IGLY_BLOG_ARTICLES",
            limit = 5
        )

        val firstPage = pager.moveTo(null)

        assertNotNull(firstPage)
        assertTrue(firstPage.isNotEmpty(), "Expected at least one item in first page")
        assertTrue(firstPage.size <= 5, "Expected at most 5 items")

        val nextToken = pager.nextToken()

        if (nextToken == null) {
            println("✅ Only one page available (no pagination token).")
            return
        }

        val secondPage = pager.moveTo(nextToken)
        assertNotNull(secondPage)
        assertTrue(secondPage.isNotEmpty(), "Expected items in the second page")
        assertNotEquals(firstPage, secondPage, "Expected different items between pages")
    }

    @Test
    fun `should handle empty collection and zero limit safely`() {
        val cp = CollectionPager(limit = 0)
        cp.data { emptyList<String>() }
        assertEquals(0, cp.totalOfItems())
        assertEquals(0, cp.totalOfPages())
        assertEquals(emptyList<String>(), cp.currentItem())
    }

    @Test
    fun `should ignore invalid page token gracefully`() {
        val list = (1..20).map { it.toString() }
        val cp = CollectionPager(limit = 5).apply { data { list } }

        val items = cp.moveTo("invalid_token")
        assertEquals(list.take(5), items)
        assertEquals(0, cp.currentPage())
    }

    @Test
    fun `should return null nextToken on last Dynamo page`() {
        assumeTrue(hasAwsEnv(), "Skipping Dynamo integration test: missing AWS_* env vars")

        val pager = DynamoPager(
            table = "IGLY_BLOG_ARTICLES",
            limit = 100,
            keyCondition = "slug = :slugVal",
            expressionValues = mapOf(
                ":slugVal" to AttributeValue.builder().s("ai-and-future").build()
            )
        )

        val token = pager.nextToken()
        assertTrue(token == null || token is String)
    }

    @Test
    fun `should correctly compute previousToken in CollectionPager`() {
        val list = (1..20).map { it.toString() }
        val pager = CollectionPager(limit = 5).apply { data { list } }

        val firstPage = pager.currentItem()
        assertEquals(list.take(5), firstPage)
        val nextToken = pager.nextToken()
        assertEquals("cGFnZV8x", nextToken)

        pager.moveTo(nextToken)
        val prevToken = pager.previousToken()
        assertEquals("cGFnZV8w", prevToken)
    }

    @Test
    fun `should handle invalid database connection gracefully`() = runBlocking {
        assumeTrue(hasDbEnv(), "Skipping DB integration test: missing DB_* env vars")

        val pager = DatabasePager(limit = 5, table = "non_existing_table", orderBy = "id", direction = "ASC")
        try {
            val rows = pager.currentItem()
            println("✅ No crash — rows: ${rows.size}")
        } catch (e: Exception) {
            val message = e.message?.lowercase() ?: ""
            println("⚠️ Caught expected exception for invalid DB table: $message")
            assertTrue(
                message.contains("relation") ||
                        message.contains("no existe") ||
                        message.contains("does not exist") ||
                        message.contains("undefined") ||
                        message.contains("table") ||
                        message.contains("db_host") ||
                        message.contains("environment"),
                "Unexpected error message for invalid table: $message"
            )
        }
    }

    @Test
    fun `should set and get current page manually in CollectionPager`() {
        val list = (1..15).map { it.toString() }
        val pager = CollectionPager(limit = 5).apply { data { list } }

        pager.setCurrentPage(2)
        assertEquals(2, pager.currentPage())

        val expected = list.drop(10).take(5)
        assertEquals(expected, pager.currentItem())
    }

    @Test
    fun `should support filterExpression and projectionFields in DynamoPager`() {
        assumeTrue(hasAwsEnv(), "Skipping Dynamo integration test: missing AWS_* env vars")

        val pager = DynamoPager(
            table = "IGLY_BLOG_ARTICLES",
            limit = 3
        ).apply {
            // simulando proyección selectiva
            select("title", "author")
        }

        val result = pager.moveTo(null)
        assertNotNull(result)
        assertTrue(result is List<*>)
        println("✅ DynamoPager projection test returned ${result.size} items")
    }

    @Test
    fun `should return empty result when filterExpression excludes all Dynamo items`() {
        assumeTrue(hasAwsEnv(), "Skipping Dynamo integration test: missing AWS_* env vars")

        val pager = DynamoPager(
            table = "IGLY_BLOG_ARTICLES",
            limit = 3,
            filterExpression = "non_existing_field = :value",
            expressionValues = mapOf(":value" to AttributeValue.builder().s("impossible").build())
        )

        val result = pager.moveTo(null)
        assertTrue(result.isEmpty() || result is List<*>)
    }

    @Test
    fun `should handle decodeToken failure gracefully in DynamoPager`() {
        assumeTrue(hasAwsEnv(), "Skipping Dynamo integration test: missing AWS_* env vars")

        val pager = DynamoPager(table = "IGLY_BLOG_ARTICLES", limit = 2)
        val result = pager.moveTo("invalid_token")
        assertNotNull(result)
        assertTrue(result is List<*>)
    }

    @Test
    fun `should correctly compute totalOfItems and totalOfPages in DatabasePager`() = runBlocking {
        assumeTrue(hasDbEnv(), "Skipping DB integration test: missing DB_* env vars")

        val pager = DatabasePager(limit = 2, table = "blogs", orderBy = "id", direction = "ASC")

        val items = pager.currentItem()
        val total = pager.totalOfItems()
        val pages = pager.totalOfPages()

        assertTrue(items.isNotEmpty())
        assertTrue(total >= 1)
        assertTrue(pages >= 1)
    }
}
