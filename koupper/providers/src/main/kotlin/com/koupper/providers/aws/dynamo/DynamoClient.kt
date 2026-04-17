package com.koupper.providers.aws.dynamo

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem

interface DynamoClient {
    fun createTable(
        tableName: String,
        keySchema: List<Pair<String, String>>,
        attributeDefinitions: List<Pair<String, String>>,
        globalSecondaryIndexes: List<Map<String, Any>>? = null
    )
    fun insertItem(tableName: String, item: Map<String, Any?>)
    fun insertItem(tableName: String, jsonItem: String)
    fun getItems(
        tableName: String?,
        partitionKeyName: String?,
        partitionKeyValue: String?,
        gsiName: String?,
        filterExpression: String? = null,
        operator: String? = null,
        comparisonValue: String? = null
    ): List<Map<String, Any>>?
    fun getItem(
        tableName: String,
        partitionKeyName: String,
        partitionKeyValue: String,
        sortKeyName: String? = null,
        sortKeyValue: String? = null,
    ): Map<String, Any>?
    fun updateItem(tableName: String, key: Map<String, Any>, updateExpression: String, expressionAttributeValues: Map<String, Any>)
    fun deleteItem(tableName: String, key: Map<String, Any>)
    fun doesTableExist(tableName: String): Boolean
    fun listTables(): List<String> // Nuevo método para listar tablas
    fun queryItems(
        tableName: String,
        keyConditionExpression: String,
        expressionAttributeValues: Map<String, Any>,
        indexName: String? = null,
        filterExpression: String? = null
    ): List<Map<String, Any>>
    fun queryItemsPaginatedChunk(
        tableName: String,
        keyConditionExpression: String,
        expressionAttributeValues: Map<String, Any>,
        indexName: String? = null,
        filterExpression: String? = null,
        limit: Int = 20,
        cursorToken: String? = null
    ): Pair<List<Map<String, Any>>, String?>
    fun scanItems(tableName: String): List<Map<String, Any>> // Nuevo método para escaneo
    fun scanItemsPaginatedChunk(
        tableName: String,
        limit: Int = 50,
        cursorToken: String? = null
    ): Pair<List<Map<String, Any>>, String?>
    fun getAllItemsPaginated(tableName: String): List<Map<String, Any>>
    fun getItemCount(
        tableName: String?,
        gsiName: String? = null, // Ahora opcional
        filterExpression: String? = null,
        expressionValues: Map<String, AttributeValue>? = null
    ): Int
    fun transactWrite(items: List<TransactWriteItem>)
    fun toAttrMap(item: Map<String, Any?>): Map<String, AttributeValue>
    fun transactPut(puts: List<TxPut>)
}
