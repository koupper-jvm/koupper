package com.koupper.providers.aws.dynamo

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
        partitionKeyName: String? = null,
        partitionKeyValue: String? = null,
        sortKeyName: String? = null,
        sortKeyValue: String? = null,
    ): Map<String, Any>?
    fun updateItem(tableName: String, key: Map<String, Any>, updateExpression: String, expressionAttributeValues: Map<String, Any>)
    fun deleteItem(tableName: String, key: Map<String, Any>)
    fun doesTableExist(tableName: String): Boolean
    fun listTables(): List<String> // Nuevo método para listar tablas
    fun queryItems(tableName: String, keyConditionExpression: String, expressionAttributeValues: Map<String, Any>): List<Map<String, Any>> // Nuevo método para consultas
    fun scanItems(tableName: String): List<Map<String, Any>> // Nuevo método para escaneo
    fun getAllItemsPaginated(tableName: String): List<Map<String, Any>>
}
