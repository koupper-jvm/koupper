package com.koupper.providers.aws.dynamo

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

data class DynamoTableSpec(
    val tableName: String,
    val keySchema: List<Pair<String, String>>,
    val attributeDefinitions: List<Pair<String, String>>,
    val globalSecondaryIndexes: List<Map<String, Any>>? = null
)

interface DynamoLocalAdmin {
    fun ensureTable(spec: DynamoTableSpec): Boolean
    fun tableExists(name: String): Boolean
    fun truncateTable(name: String, keySchema: List<String>): Int
    fun countByEmail(table: String, email: String, field: String = "email"): Int
    fun scanCount(table: String, filterExpression: String? = null, expressionValues: Map<String, AttributeValue>? = null): Int
    fun listTables(): List<String>
}

class DynamoLocalAdminImpl(
    private val dynamo: DynamoClient
) : DynamoLocalAdmin {
    override fun ensureTable(spec: DynamoTableSpec): Boolean {
        if (dynamo.doesTableExist(spec.tableName)) {
            return false
        }

        dynamo.createTable(
            tableName = spec.tableName,
            keySchema = spec.keySchema,
            attributeDefinitions = spec.attributeDefinitions,
            globalSecondaryIndexes = spec.globalSecondaryIndexes
        )
        return true
    }

    override fun tableExists(name: String): Boolean = dynamo.doesTableExist(name)

    override fun truncateTable(name: String, keySchema: List<String>): Int {
        val items = dynamo.scanItems(name)
        if (items.isEmpty()) return 0

        var deleted = 0
        items.forEach { item ->
            val key = keySchema.mapNotNull { ks ->
                val value = item[ks]
                if (value != null) ks to value else null
            }.toMap()

            if (key.isNotEmpty()) {
                dynamo.deleteItem(name, key)
                deleted += 1
            }
        }

        return deleted
    }

    override fun countByEmail(table: String, email: String, field: String): Int {
        val scan = dynamo.scanItems(table)
        return scan.count { row -> row[field]?.toString() == email }
    }

    override fun scanCount(
        table: String,
        filterExpression: String?,
        expressionValues: Map<String, AttributeValue>?
    ): Int {
        return dynamo.getItemCount(
            tableName = table,
            filterExpression = filterExpression,
            expressionValues = expressionValues
        )
    }

    override fun listTables(): List<String> = dynamo.listTables()
}
