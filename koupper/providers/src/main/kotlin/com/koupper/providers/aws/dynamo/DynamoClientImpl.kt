package com.koupper.providers.aws.dynamo

import com.koupper.os.env
import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.providers.files.toType
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.net.URI
import java.util.Base64

class DynamoClientImpl private constructor(
    private val explicitCredsProvider: AwsCredentialsProvider?
) : DynamoClient {

    constructor() : this(null)

    fun withCredentials(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = null
    ): DynamoClientImpl {
        val provider =
            if (!sessionToken.isNullOrBlank())
                StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
                )
            else
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                )

        return DynamoClientImpl(provider)
    }

    private fun envCredsProvider(): AwsCredentialsProvider {
        val ak = env("AWS_ACCESS_KEY_ID", required = false, allowEmpty = true, default = "")
        val sk = env("AWS_SECRET_ACCESS_KEY", required = false, allowEmpty = true, default = "")
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

    private val dynamoDbClient: DynamoDbClient by lazy {
        val dynamoUrl = env("DYNAMO_URL", required = false, allowEmpty = true, default = "")
        val region = env("DYNAMO_REGION")
        val credsForAws = explicitCredsProvider ?: envCredsProvider()

        DynamoDbClient.builder()
            .region(Region.of(region))
            .apply {
                if (dynamoUrl.isNotEmpty()) {
                    endpointOverride(URI(dynamoUrl))
                    credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")
                        )
                    )
                } else {
                    credentialsProvider(credsForAws)
                }
            }
            .build()
    }

    override fun createTable(
        tableName: String,
        keySchema: List<Pair<String, String>>,
        attributeDefinitions: List<Pair<String, String>>,
        globalSecondaryIndexes: List<Map<String, Any>>?
    ) {
        val keySchemaElements = keySchema.map {
            KeySchemaElement.builder()
                .attributeName(it.first)
                .keyType(KeyType.fromValue(it.second))
                .build()
        }

        val attributeDefinitionElements = attributeDefinitions.map {
            AttributeDefinition.builder()
                .attributeName(it.first)
                .attributeType(ScalarAttributeType.fromValue(it.second))
                .build()
        }

        val createTableRequestBuilder = CreateTableRequest.builder()
            .tableName(tableName)
            .keySchema(keySchemaElements)
            .attributeDefinitions(attributeDefinitionElements)
            .provisionedThroughput(
                ProvisionedThroughput.builder()
                    .readCapacityUnits(5)
                    .writeCapacityUnits(5)
                    .build()
            )

        globalSecondaryIndexes?.let { gsis ->
            val gsiElements = gsis.map { gsi ->
                val ks = (gsi["KeySchema"] as List<Map<String, String>>).map { key ->
                    KeySchemaElement.builder()
                        .attributeName(key["AttributeName"]!!)
                        .keyType(KeyType.fromValue(key["KeyType"]!!))
                        .build()
                }

                GlobalSecondaryIndex.builder()
                    .indexName(gsi["IndexName"] as String)
                    .keySchema(ks)
                    .projection(
                        Projection.builder()
                            .projectionType(ProjectionType.ALL)
                            .build()
                    )
                    .provisionedThroughput(
                        ProvisionedThroughput.builder()
                            .readCapacityUnits(5)
                            .writeCapacityUnits(5)
                            .build()
                    )
                    .build()
            }
            createTableRequestBuilder.globalSecondaryIndexes(gsiElements)
        }

        val createTableRequest = createTableRequestBuilder.build()

        try {
            dynamoDbClient.createTable(createTableRequest)
            dynamoDbClient.waiter().waitUntilTableExists(
                DescribeTableRequest.builder().tableName(tableName).build()
            )
        } catch (e: Exception) {
            println("Error creating table: ${e.message}")
        }
    }

    override fun insertItem(tableName: String, jsonItem: String) {
        val mapper = JSONFileHandlerImpl<Map<String, Any?>>()
        val item: Map<String, Any?> = mapper.read(jsonItem).toType()
        insertItem(tableName, item)
    }

    override fun insertItem(tableName: String, item: Map<String, Any?>) {
        val attributeValues = toAttrMap(item)

        val putItemRequest = PutItemRequest.builder()
            .tableName(tableName)
            .item(attributeValues)
            .build()

        dynamoDbClient.putItem(putItemRequest)
    }

    private fun processValue(value: Any): AttributeValue =
        when (value) {
            is String -> AttributeValue.builder().s(value).build()
            is Number -> AttributeValue.builder().n(value.toString()).build()
            is Boolean -> AttributeValue.builder().bool(value).build()
            is List<*> -> AttributeValue.builder()
                .l(value.mapNotNull { it?.let(::processValue) })
                .build()
            is Map<*, *> -> AttributeValue.builder()
                .m(processMap(value as Map<String, Any?>))
                .build()
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.java.name}")
        }

    private fun processMap(value: Map<String, Any?>): Map<String, AttributeValue> =
        value.mapNotNull { (k, v) -> v?.let { k to processValue(it) } }.toMap()

    override fun getItem(
        tableName: String,
        partitionKeyName: String,
        partitionKeyValue: String,
        sortKeyName: String?,
        sortKeyValue: String?
    ): Map<String, Any>? {
        require(partitionKeyName.isNotBlank() && partitionKeyValue.isNotBlank()) {
            "Partition key and its value are required for this query."
        }

        val key = mutableMapOf(
            partitionKeyName to AttributeValue.builder().s(partitionKeyValue).build()
        )

        if (!sortKeyName.isNullOrBlank() && !sortKeyValue.isNullOrBlank()) {
            key[sortKeyName] = AttributeValue.builder().s(sortKeyValue).build()
        }

        val getItemRequest = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        val getItemResult = dynamoDbClient.getItem(getItemRequest)

        return if (getItemResult.hasItem()) {
            getItemResult.item().mapValues { convertAttributeValue(it.value) }
        } else null
    }

    override fun getItems(
        tableName: String?,
        partitionKeyName: String?,
        partitionKeyValue: String?,
        gsiName: String?,
        filterExpression: String?,
        operator: String?,
        comparisonValue: String?
    ): List<Map<String, Any>>? {
        require(!tableName.isNullOrBlank()) { "tableName is required." }
        require(!partitionKeyName.isNullOrBlank()) { "partitionKeyName is required." }
        require(!partitionKeyValue.isNullOrBlank()) { "partitionKeyValue is required." }

        val keyConditionExpression = "$partitionKeyName = :pk"
        val expressionAttributeValues = mutableMapOf(":pk" to processValue(partitionKeyValue))

        val queryRequestBuilder = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression(keyConditionExpression)
            .expressionAttributeValues(expressionAttributeValues)

        if (!gsiName.isNullOrEmpty()) queryRequestBuilder.indexName(gsiName)
        if (!filterExpression.isNullOrEmpty()) queryRequestBuilder.filterExpression(filterExpression)

        val result = dynamoDbClient.query(queryRequestBuilder.build())

        return if (result.items().isNotEmpty()) {
            result.items().map { it.mapValues { v -> convertAttributeValue(v.value) } }
        } else null
    }

    private fun convertAttributeValue(attributeValue: AttributeValue): Any =
        when {
            attributeValue.s() != null -> attributeValue.s()
            attributeValue.n() != null -> attributeValue.n().toBigDecimal()
            attributeValue.bool() != null -> attributeValue.bool()
            attributeValue.hasM() ->
                attributeValue.m().mapValues { convertAttributeValue(it.value) }
            attributeValue.hasL() ->
                attributeValue.l().map { convertAttributeValue(it) }
            attributeValue.nul() == true -> ""
            else -> throw IllegalArgumentException("Unsupported AttributeValue: $attributeValue")
        }

    override fun updateItem(
        tableName: String,
        key: Map<String, Any>,
        updateExpression: String,
        expressionAttributeValues: Map<String, Any>
    ) {
        val keyAttributes = key.mapValues { (_, value) -> processValue(value) }
        val attributeValues = expressionAttributeValues.mapValues { (_, value) -> processValue(value) }

        val updateRequest = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(keyAttributes)
            .updateExpression(updateExpression)
            .expressionAttributeValues(attributeValues)
            .build()

        try {
            dynamoDbClient.updateItem(updateRequest)
        } catch (e: Exception) {
            println("Error updating item: ${e.message}")
        }
    }

    override fun deleteItem(tableName: String, key: Map<String, Any>) {
        val keyAttributes = key.mapValues { (_, value) -> processValue(value) }

        val deleteRequest = DeleteItemRequest.builder()
            .tableName(tableName)
            .key(keyAttributes)
            .build()

        try {
            dynamoDbClient.deleteItem(deleteRequest)
        } catch (e: Exception) {
            println("Error deleting item: ${e.message}")
        }
    }

    override fun doesTableExist(tableName: String): Boolean =
        try {
            dynamoDbClient.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()
            )
            true
        } catch (_: DynamoDbException) {
            false
        }

    override fun listTables(): List<String> {
        val tableNames = mutableListOf<String>()
        var lastEvaluatedTableName: String? = null

        do {
            val requestBuilder = ListTablesRequest.builder()
            lastEvaluatedTableName?.let { requestBuilder.exclusiveStartTableName(it) }

            val result = dynamoDbClient.listTables(requestBuilder.build())
            tableNames.addAll(result.tableNames())
            lastEvaluatedTableName = result.lastEvaluatedTableName()
        } while (lastEvaluatedTableName != null)

        return tableNames
    }

    override fun queryItems(
        tableName: String,
        keyConditionExpression: String,
        expressionAttributeValues: Map<String, Any>,
        indexName: String?,
        filterExpression: String?
    ): List<Map<String, Any>> {
        val attrValues = expressionAttributeValues.mapValues { (_, v) -> processValue(v) }

        val requestBuilder = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression(keyConditionExpression)
            .expressionAttributeValues(attrValues)

        if (!indexName.isNullOrBlank()) requestBuilder.indexName(indexName)
        if (!filterExpression.isNullOrBlank()) requestBuilder.filterExpression(filterExpression)

        val result = dynamoDbClient.query(requestBuilder.build())

        return result.items().map { item ->
            item.mapValues { convertAttributeValue(it.value) }
        }
    }

    override fun queryItemsPaginatedChunk(
        tableName: String,
        keyConditionExpression: String,
        expressionAttributeValues: Map<String, Any>,
        indexName: String?,
        filterExpression: String?,
        limit: Int,
        cursorToken: String?
    ): Pair<List<Map<String, Any>>, String?> {
        val attrValues = expressionAttributeValues.mapValues { (_, v) -> processValue(v) }

        val requestBuilder = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression(keyConditionExpression)
            .expressionAttributeValues(attrValues)
            .limit(limit)

        if (!indexName.isNullOrBlank()) requestBuilder.indexName(indexName)
        if (!filterExpression.isNullOrBlank()) requestBuilder.filterExpression(filterExpression)

        decodeCursorFromBase64(cursorToken)?.let { requestBuilder.exclusiveStartKey(it) }

        val result = dynamoDbClient.query(requestBuilder.build())
        val parsedItems = result.items().map { item ->
            item.mapValues { convertAttributeValue(it.value) }
        }

        return parsedItems to encodeCursorToBase64(result.lastEvaluatedKey())
    }

    override fun scanItems(tableName: String): List<Map<String, Any>> {
        val items = mutableListOf<Map<String, Any>>()
        var lastEvaluatedKey: Map<String, AttributeValue>? = null

        do {
            val requestBuilder = ScanRequest.builder().tableName(tableName)
            lastEvaluatedKey?.let { requestBuilder.exclusiveStartKey(it) }

            val result = dynamoDbClient.scan(requestBuilder.build())
            if (result.hasItems()) {
                items.addAll(result.items().map { it.mapValues { v -> convertAttributeValue(v.value) } })
            }

            lastEvaluatedKey = result.lastEvaluatedKey()
        } while (lastEvaluatedKey != null && lastEvaluatedKey.isNotEmpty())

        return items
    }

    override fun scanItemsPaginatedChunk(
        tableName: String,
        limit: Int,
        cursorToken: String?
    ): Pair<List<Map<String, Any>>, String?> {
        val requestBuilder = ScanRequest.builder()
            .tableName(tableName)
            .limit(limit)

        decodeCursorFromBase64(cursorToken)?.let { requestBuilder.exclusiveStartKey(it) }

        val result = dynamoDbClient.scan(requestBuilder.build())
        val parsedItems = result.items().map { item ->
            item.mapValues { convertAttributeValue(it.value) }
        }

        return parsedItems to encodeCursorToBase64(result.lastEvaluatedKey())
    }

    fun encodeCursorToBase64(cursor: Map<String, AttributeValue>?): String? {
        if (cursor.isNullOrEmpty()) return null

        val payload = cursor.mapValues { convertAttributeValue(it.value) }
        val json = JSONFileHandlerImpl<Map<String, Any?>>().toJsonString(payload)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun decodeCursorFromBase64(base64: String?): Map<String, AttributeValue>? {
        if (base64.isNullOrBlank()) return null

        val decoded = String(Base64.getUrlDecoder().decode(base64), Charsets.UTF_8)
        val map = JSONFileHandlerImpl<Map<String, Any?>>().read(decoded).toType<Map<String, Any?>>()
        return map.mapValues { (_, value) -> value?.let(::processValue) ?: AttributeValue.builder().nul(true).build() }
    }

    override fun getAllItemsPaginated(tableName: String): List<Map<String, Any>> {
        val items = mutableListOf<Map<String, Any>>()
        var lastEvaluatedKey: Map<String, AttributeValue>? = null

        do {
            val scanRequestBuilder = ScanRequest.builder().tableName(tableName)
            lastEvaluatedKey?.let { scanRequestBuilder.exclusiveStartKey(it) }

            val result = dynamoDbClient.scan(scanRequestBuilder.build())
            items.addAll(result.items().map { it.mapValues { v -> convertAttributeValue(v.value) } })

            lastEvaluatedKey = result.lastEvaluatedKey()
        } while (lastEvaluatedKey != null && lastEvaluatedKey.isNotEmpty())

        return items
    }

    override fun getItemCount(
        tableName: String?,
        gsiName: String?,
        filterExpression: String?,
        expressionValues: Map<String, AttributeValue>?
    ): Int {
        val scanRequestBuilder = ScanRequest.builder()
            .tableName(tableName)
            .select(Select.COUNT)

        if (!gsiName.isNullOrEmpty()) scanRequestBuilder.indexName(gsiName)
        if (!filterExpression.isNullOrEmpty()) scanRequestBuilder.filterExpression(filterExpression)
        if (!expressionValues.isNullOrEmpty()) scanRequestBuilder.expressionAttributeValues(expressionValues)

        val result = dynamoDbClient.scan(scanRequestBuilder.build())
        return result.count()
    }

    override fun transactWrite(items: List<TransactWriteItem>) {
        dynamoDbClient.transactWriteItems(
            TransactWriteItemsRequest.builder()
                .transactItems(items)
                .build()
        )
    }

    override fun toAttrMap(item: Map<String, Any?>): Map<String, AttributeValue> {
        val out = mutableMapOf<String, AttributeValue>()
        item.forEach { (k, v) ->
            if (v != null) out[k] = processValue(v)
        }
        return out
    }

    override fun transactPut(puts: List<TxPut>) {
        val txItems = puts.map { p ->
            TransactWriteItem.builder()
                .put(
                    Put.builder()
                        .tableName(p.tableName)
                        .item(toAttrMap(p.item))
                        .build()
                )
                .build()
        }

        dynamoDbClient.transactWriteItems(
            TransactWriteItemsRequest.builder()
                .transactItems(txItems)
                .build()
        )
    }
}
