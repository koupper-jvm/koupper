package com.koupper.orchestrator.paginator

import com.koupper.os.env
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.DBSession
import io.zeko.db.sql.connections.HikariDBPool
import io.zeko.db.sql.connections.HikariDBSession
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import java.net.URI
import java.sql.Statement
import java.util.*
import kotlin.math.max
import kotlin.reflect.full.memberProperties

interface Pager {
    fun totalOfItems(): Int
    fun totalOfPages(): Int
    fun setCurrentPage(pageNumber: Int)
    fun currentPage(): Int
    fun currentItem(): Any?
    fun moveTo(token: String? = null): List<Any?>
    fun nextToken(): String?
    fun previousToken(): String?
    fun hasNext(): Boolean = nextToken() != null
    fun hasPrevious(): Boolean = previousToken() != null
}

data class CollectionPager(
    private var limit: Int = 0,
    private var orderBy: String = "id",
    private var direction: String = "ASC"
) : Pager {
    private var items: Iterable<*> = emptyList<Any>()
    private var currentPage: Int = 0

    fun <T> data(source: () -> T) {
        val result = source()
        items = when (result) {
            is Map<*, *> -> result.entries
            is Iterable<*> -> sortIfPossible(result)
            else -> throw IllegalArgumentException(
                "Expected Iterable or Map, got ${result!!::class.simpleName}"
            )
        }
        currentPage = 0
    }

    private fun sortIfPossible(result: Iterable<*>): Iterable<*> {
        val list = result.toList()
        if (list.isEmpty()) return result

        return try {
            when {
                list.first() is Map<*, *> -> {
                    val maps = list as List<Map<String, Any?>>
                    val ordered = maps.sortedWith { a, b ->
                        val v1 = a[orderBy]
                        val v2 = b[orderBy]
                        compareValues(v1 as? Comparable<Any>, v2 as? Comparable<Any>)
                    }
                    if (direction.equals("DESC", true)) ordered.reversed() else ordered
                }

                list.first() != null && list.first()!!::class.memberProperties.any { it.name == orderBy } -> {
                    val prop = list.first()!!::class.memberProperties
                        .first { it.name == orderBy } as kotlin.reflect.KProperty1<Any, *>

                    val ordered = list.sortedWith { a, b ->
                        val v1 = prop.get(a as Any)
                        val v2 = prop.get(b as Any)
                        compareValues(v1 as? Comparable<Any>, v2 as? Comparable<Any>)
                    }

                    if (direction.equals("DESC", true)) ordered.reversed() else ordered
                }

                else -> list
            }
        } catch (_: Exception) {
            result
        }
    }

    override fun totalOfItems(): Int = items.count()

    override fun totalOfPages(): Int {
        if (limit <= 0) return 0
        val total = totalOfItems()
        return (total + limit - 1) / limit
    }

    override fun setCurrentPage(pageNumber: Int) {
        if (pageNumber < 0 || pageNumber >= totalOfPages()) {
            throw IndexOutOfBoundsException("Page index $pageNumber out of range")
        }
        currentPage = pageNumber
    }

    override fun currentPage(): Int = currentPage

    override fun currentItem(): List<Any?> {
        val start = currentPage * limit
        return items.drop(start).take(limit).toList()
    }

    override fun moveTo(token: String?): List<Any?> {
        val pageIndex = token?.let { decodeToken(it) } ?: 0
        setCurrentPage(pageIndex)
        return currentItem()
    }

    override fun nextToken(): String? =
        if (currentPage + 1 < totalOfPages()) encodeToken(currentPage + 1) else null

    override fun previousToken(): String? =
        if (currentPage > 0) encodeToken(currentPage - 1) else null

    private fun encodeToken(page: Int): String =
        Base64.getUrlEncoder().encodeToString("page_$page".toByteArray())

    private fun decodeToken(token: String): Int? = try {
        String(Base64.getUrlDecoder().decode(token))
            .removePrefix("page_")
            .toIntOrNull()
    } catch (_: Exception) {
        null
    }
}

data class DatabasePager(
    private var limit: Int = 10,
    private var table: String,
    private var orderBy: String = "id",
    private var direction: String = "ASC",
    private var selectedFields: List<String>? = null
) : Pager {
    companion object {
        private val pool: HikariDBPool by lazy {
            val config = Json.obj(
                "url" to "jdbc:postgresql://${env("DB_HOST")}:${env("DB_PORT")}/${env("DB_DATABASE")}?user=${env("DB_USERNAME")}&password=${
                    env(
                        "DB_PASSWORD"
                    )
                }",
                "max_pool_size" to 30
            )
            HikariDBPool(config).apply {
                setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)
                println("✅ HikariDBPool initialized (singleton in paginator module)")
            }
        }
    }

    private suspend fun session(): DBSession {
        return HikariDBSession(pool, pool.createConnection())
    }

    private var totalItems: Int = 0
    private var currentPage: Int = 0

    override fun totalOfItems(): Int = totalItems

    override fun totalOfPages(): Int =
        if (limit > 0) (totalItems + limit - 1) / limit else 0

    override fun setCurrentPage(pageNumber: Int) {
        currentPage = if (pageNumber < 0) 0 else pageNumber
    }

    override fun currentPage(): Int = currentPage

    override fun currentItem(): List<Map<String, Any?>> = runBlocking {
        val offset = currentPage * limit
        val columns = if (!selectedFields.isNullOrEmpty()) selectedFields!!.joinToString(", ") else "*"
        val sql = "SELECT $columns FROM $table ORDER BY $orderBy $direction LIMIT $limit OFFSET $offset"

        var rows: List<Map<String, Any?>> = emptyList()

        session().once { conn ->
            @Suppress("UNCHECKED_CAST")
            rows = conn.query(sql, { it.toMap() }, false, false) as List<Map<String, Any?>>
        }

        totalItems = countItems()
        rows
    }

    private suspend fun countItems(): Int {
        var count = 0
        val sql = "SELECT COUNT(*) AS total FROM $table"

        session().once { conn ->
            @Suppress("UNCHECKED_CAST")
            val result = conn.query(sql, { it.toMap() }, false, false) as List<Map<String, Any?>>
            val first = result.firstOrNull()
            count = (first?.get("total") as? Number)?.toInt() ?: 0
        }
        return count
    }

    override fun nextToken(): String? {
        val nextPage = currentPage + 1
        return if (nextPage < totalOfPages()) encodeToken(nextPage) else null
    }

    override fun previousToken(): String? {
        val prevPage = currentPage - 1
        return if (prevPage >= 0) encodeToken(prevPage) else null
    }

    override fun moveTo(token: String?): List<Any?> {
        currentPage = decodeToken(token) ?: 0
        return currentItem()
    }

    private fun encodeToken(page: Int): String =
        Base64.getUrlEncoder().encodeToString("page_$page".toByteArray())

    private fun decodeToken(token: String?): Int? = try {
        String(Base64.getUrlDecoder().decode(token ?: ""))
            .removePrefix("page_")
            .toIntOrNull()
    } catch (_: Exception) {
        null
    }

    fun select(vararg fields: String): DatabasePager = apply {
        this.selectedFields = fields.toList()
    }
}

data class DynamoPager(
    private var limit: Int = 10,
    private var table: String,
    private var indexName: String? = null,
    private var keyCondition: String? = null,
    private var expressionValues: Map<String, AttributeValue> = emptyMap(),
    private var projectionFields: List<String>? = null,
    private var filterExpression: String? = null,
    private var expressionNames: Map<String, String> = emptyMap(),
    private var scanIndexForward: Boolean = true,
    private var consistentRead: Boolean = false,
    private var fillLimitAfterFilter: Boolean = false
) : Pager {

    private data class CursorPayload(
        val page: Int = 0,
        val startKey: Map<String, String>? = null,
        val history: List<Map<String, String>?> = emptyList()
    )

    private data class PageResult(
        val items: List<Map<String, Any?>>,
        val startKeyUsed: Map<String, AttributeValue>?,
        val lastEvaluatedKey: Map<String, AttributeValue>?,
        val page: Int,
        val history: List<Map<String, AttributeValue>?>
    )

    private fun envCredsProvider(): AwsCredentialsProvider {
        val ak = env("AWS_ACCESS_KEY_ID")
        val sk = env("AWS_SECRET_ACCESS_KEY")
        val st = env("AWS_SESSION_TOKEN", required = false, allowEmpty = true, default = "")
        return when {
            ak.isNotBlank() && sk.isNotBlank() && st.isNotBlank() ->
                StaticCredentialsProvider.create(AwsSessionCredentials.create(ak, sk, st))

            ak.isNotBlank() && sk.isNotBlank() ->
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))

            else ->
                EnvironmentVariableCredentialsProvider.create()
        }
    }

    companion object {
        val dynamoDbClient: DynamoDbClient by lazy {
            DynamoDbClient.builder()
                .region(Region.of(env("DYNAMO_REGION")))
                .apply {
                    val dynamoUrl = env("DYNAMO_URL", required = false, allowEmpty = true, default = "")
                    if (dynamoUrl.isNotEmpty()) {
                        endpointOverride(URI(dynamoUrl))
                        credentialsProvider {
                            AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")
                        }
                    } else {
                        credentialsProvider(DefaultCredentialsProvider.create())
                    }
                }
                .build()
        }
    }

    private var currentItems: List<Map<String, Any?>> = emptyList()
    private var currentPageNumber: Int = 0
    private var currentStartKey: Map<String, AttributeValue>? = null
    private var currentHistory: List<Map<String, AttributeValue>?> = emptyList()
    private var lastEvaluatedKey: Map<String, AttributeValue>? = null

    override fun totalOfItems(): Int = currentItems.size
    override fun totalOfPages(): Int = 0
    override fun setCurrentPage(pageNumber: Int) {}
    override fun currentPage(): Int = currentPageNumber
    override fun currentItem(): List<Any?> = currentItems

    fun itemsInCurrentPage(): Int = currentItems.size

    fun index(name: String): DynamoPager = apply { this.indexName = name }
    fun whereKey(condition: String, values: Map<String, AttributeValue>): DynamoPager = apply {
        this.keyCondition = condition
        this.expressionValues = this.expressionValues + values
    }

    fun whereFilter(expression: String): DynamoPager = apply {
        this.filterExpression = expression
    }

    fun names(values: Map<String, String>): DynamoPager = apply {
        this.expressionNames = this.expressionNames + values
    }

    fun descending(): DynamoPager = apply {
        this.scanIndexForward = false
    }

    fun ascending(): DynamoPager = apply {
        this.scanIndexForward = true
    }

    fun consistent(): DynamoPager = apply {
        this.consistentRead = true
    }

    fun eventuallyConsistent(): DynamoPager = apply {
        this.consistentRead = false
    }

    fun pageSize(size: Int): DynamoPager = apply {
        this.limit = max(1, size)
    }

    fun values(values: Map<String, AttributeValue>): DynamoPager = apply {
        this.expressionValues = this.expressionValues + values
    }

    fun fillAfterFilter(): DynamoPager = apply {
        this.fillLimitAfterFilter = true
    }

    override fun moveTo(token: String?): List<Any?> {
        val cursor = decodeCursor(token) ?: CursorPayload()
        val result = queryPage(cursor)
        currentItems = result.items
        currentStartKey = result.startKeyUsed
        lastEvaluatedKey = result.lastEvaluatedKey
        currentPageNumber = result.page
        currentHistory = result.history
        return currentItems
    }

    override fun nextToken(): String? {
        val nextStart = lastEvaluatedKey?.takeIf { it.isNotEmpty() } ?: return null

        val nextHistory = currentHistory + listOf(currentStartKey)
        return encodeCursor(
            CursorPayload(
                page = currentPageNumber + 1,
                startKey = serializeKey(nextStart),
                history = nextHistory.map { serializeKey(it) }
            )
        )
    }

    override fun previousToken(): String? {
        if (currentPageNumber <= 0) return null

        val previousStart = currentHistory.lastOrNull()
        val previousHistory = if (currentHistory.isNotEmpty()) currentHistory.dropLast(1) else emptyList()

        return encodeCursor(
            CursorPayload(
                page = max(0, currentPageNumber - 1),
                startKey = serializeKey(previousStart),
                history = previousHistory.map { serializeKey(it) }
            )
        )
    }

    private fun queryPage(cursor: CursorPayload): PageResult {
        val initialStartKey = deserializeKey(cursor.startKey)
        val history = cursor.history.map { deserializeKey(it) }

        if (!fillLimitAfterFilter || filterExpression.isNullOrBlank()) {
            val single = singleQueryOrScan(initialStartKey, limit)
            return PageResult(
                items = single.items,
                startKeyUsed = initialStartKey,
                lastEvaluatedKey = single.lastEvaluatedKey,
                page = cursor.page,
                history = history
            )
        }

        val collected = mutableListOf<Map<String, Any?>>()
        var nextStartKey = initialStartKey
        var lastKey: Map<String, AttributeValue>? = null
        var firstIteration = true

        do {
            val remaining = limit - collected.size
            val batchSize = max(remaining, 1)

            val result = singleQueryOrScan(nextStartKey, batchSize)

            if (result.items.isNotEmpty()) {
                collected.addAll(result.items.take(remaining))
            }

            lastKey = result.lastEvaluatedKey
            nextStartKey = lastKey

            if (collected.size >= limit) {
                break
            }

            if (lastKey == null || lastKey.isEmpty()) {
                break
            }

            firstIteration = false
        } while (true)

        return PageResult(
            items = collected,
            startKeyUsed = initialStartKey,
            lastEvaluatedKey = lastKey,
            page = cursor.page,
            history = history
        )
    }

    private data class SinglePageResult(
        val items: List<Map<String, Any?>>,
        val lastEvaluatedKey: Map<String, AttributeValue>?
    )

    private fun singleQueryOrScan(
        startKey: Map<String, AttributeValue>?,
        requestLimit: Int
    ): SinglePageResult {
        val items = mutableListOf<Map<String, Any?>>()

        try {
            if (!keyCondition.isNullOrBlank()) {
                val queryRequest = QueryRequest.builder()
                    .tableName(table)
                    .limit(requestLimit)
                    .scanIndexForward(scanIndexForward)
                    .consistentRead(if (indexName == null) consistentRead else false)
                    .apply {
                        indexName?.let { indexName(it) }
                        keyConditionExpression(keyCondition)

                        if (expressionValues.isNotEmpty()) {
                            expressionAttributeValues(expressionValues)
                        }

                        val names = buildExpressionNames()
                        if (names.isNotEmpty()) {
                            expressionAttributeNames(names)
                        }

                        if (!filterExpression.isNullOrBlank()) {
                            filterExpression(filterExpression)
                        }

                        buildProjectionExpression()?.let { projectionExpression(it) }
                        startKey?.takeIf { it.isNotEmpty() }?.let { exclusiveStartKey(it) }
                    }
                    .build()

                val response = dynamoDbClient.query(queryRequest)
                response.items().forEach { item ->
                    items.add(item.mapValues { convertAttributeValue(it.value) })
                }

                return SinglePageResult(
                    items = items,
                    lastEvaluatedKey = response.lastEvaluatedKey()
                )
            } else {
                val scanRequest = ScanRequest.builder()
                    .tableName(table)
                    .limit(requestLimit)
                    .consistentRead(consistentRead)
                    .apply {
                        if (expressionValues.isNotEmpty()) {
                            expressionAttributeValues(expressionValues)
                        }

                        val names = buildExpressionNames()
                        if (names.isNotEmpty()) {
                            expressionAttributeNames(names)
                        }

                        if (!filterExpression.isNullOrBlank()) {
                            filterExpression(filterExpression)
                        }

                        buildProjectionExpression()?.let { projectionExpression(it) }
                        startKey?.takeIf { it.isNotEmpty() }?.let { exclusiveStartKey(it) }
                    }
                    .build()

                val response = dynamoDbClient.scan(scanRequest)
                response.items().forEach { item ->
                    items.add(item.mapValues { convertAttributeValue(it.value) })
                }

                return SinglePageResult(
                    items = items,
                    lastEvaluatedKey = response.lastEvaluatedKey()
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("DynamoPager query/scan error: ${e.message}", e)
        }
    }

    private fun buildExpressionNames(): Map<String, String> {
        val names = mutableMapOf<String, String>()
        names.putAll(expressionNames)

        if (!projectionFields.isNullOrEmpty()) {
            projectionFields!!.forEach { field ->
                val alias = "#p_${field.replace("[^A-Za-z0-9_]".toRegex(), "_")}"
                names[alias] = field
            }
        }

        return names
    }

    private fun buildProjectionExpression(): String? {
        if (projectionFields.isNullOrEmpty()) return null
        return projectionFields!!.joinToString(", ") { field ->
            "#p_${field.replace("[^A-Za-z0-9_]".toRegex(), "_")}"
        }
    }

    private fun convertAttributeValue(v: AttributeValue?): Any? = when {
        v == null -> null
        v.s() != null -> v.s()
        v.n() != null -> v.n()
        v.bool() != null -> v.bool()
        v.hasM() -> v.m().mapValues { convertAttributeValue(it.value) }
        v.hasL() -> v.l().map { convertAttributeValue(it) }
        v.hasSs() -> v.ss()
        v.hasNs() -> v.ns()
        v.nul() == true -> null
        else -> null
    }

    private fun serializeKey(key: Map<String, AttributeValue>?): Map<String, String>? {
        if (key == null) return null
        return key.mapValues { (_, v) ->
            when {
                v.s() != null -> "S:${v.s()}"
                v.n() != null -> "N:${v.n()}"
                v.bool() != null -> "BOOL:${v.bool()}"
                else -> "S:${v.toString()}"
            }
        }
    }

    private fun deserializeKey(key: Map<String, String>?): Map<String, AttributeValue>? {
        if (key == null) return null
        return key.mapValues { (_, value) ->
            when {
                value.startsWith("S:") -> AttributeValue.builder().s(value.removePrefix("S:")).build()
                value.startsWith("N:") -> AttributeValue.builder().n(value.removePrefix("N:")).build()
                value.startsWith("BOOL:") -> AttributeValue.builder().bool(value.removePrefix("BOOL:").toBoolean())
                    .build()

                else -> AttributeValue.builder().s(value).build()
            }
        }
    }

    private fun encodeCursor(payload: CursorPayload): String {
        val json = JsonObject.mapFrom(
            mapOf(
                "page" to payload.page,
                "startKey" to payload.startKey,
                "history" to payload.history
            )
        ).encode()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun decodeCursor(token: String?): CursorPayload? {
        return try {
            if (token.isNullOrBlank()) return null
            val json = String(Base64.getUrlDecoder().decode(token))
            val obj = JsonObject(json)

            val page = obj.getInteger("page", 0)

            val startKeyObj = obj.getJsonObject("startKey")
            val startKey = startKeyObj?.map?.mapValues { it.value?.toString() ?: "" }

            val historyArray = obj.getJsonArray("history")
            val history = historyArray?.mapNotNull { entry ->
                when (entry) {
                    null -> null
                    is JsonObject -> entry.map.mapValues { it.value?.toString() ?: "" }
                    is Map<*, *> -> entry.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
                    else -> null
                }
            } ?: emptyList()

            CursorPayload(
                page = page,
                startKey = startKey,
                history = history
            )
        } catch (_: Exception) {
            null
        }
    }

    fun select(vararg fields: String): DynamoPager = apply {
        this.projectionFields = fields.toList()
    }
}
