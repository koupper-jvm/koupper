package com.koupper.providers.db

import com.koupper.providers.db.connections.SQLiteDBPool
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.extensions.splitKeyValue
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*

class DBSQLiteConnector : DBConnector {
    private lateinit var pool: SQLiteDBPool
    private var parserHtmlTemplate = TextParserEnvPropertiesTemplate()

    override suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())

    override fun configFromPath(configPath: String): DBConnector {
        this.parserHtmlTemplate.readFromPath(configPath)

        this.setup()

        return this
    }

    override fun configFromUrl(configPath: String): DBConnector {
        this.parserHtmlTemplate.readFromURL(configPath)

        this.setup()

        return this
    }

    override fun configFromResource(configPath: String): DBConnector {
        this.parserHtmlTemplate.readFromResource(configPath)

        this.setup()

        return this
    }

    private fun setup() {
        val properties: Map<String?, String?> = parserHtmlTemplate.splitKeyValue("=".toRegex())

        val databaseName = properties["DB_DATABASE"]

        val config = Json.obj(
                "url" to "jdbc:sqlite:$databaseName"
        )

        pool = SQLiteDBPool(config)
    }
}