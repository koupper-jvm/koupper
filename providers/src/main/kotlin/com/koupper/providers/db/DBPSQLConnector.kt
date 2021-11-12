package com.koupper.providers.db

import com.koupper.providers.parsing.TextReader
import com.koupper.providers.parsing.extensions.splitKeyValue
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*
import java.sql.Statement

class DBPSQLConnector : DBConnector {
    private lateinit var pool: HikariDBPool
    private val parser = TextReader()

    override suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())

    override fun configFromPath(configPath: String): DBConnector {
        this.parser.readFromPath(configPath)

        this.setup()

        return this
    }

    override fun configFromUrl(configPath: String): DBConnector {
        this.parser.readFromURL(configPath)

        this.setup()

        return this
    }

    override fun configFromResource(configPath: String): DBConnector {
        this.parser.readFromResource(configPath)

        this.setup()

        return this
    }

    private fun setup() {
        val properties: Map<String?, String?> = parser.splitKeyValue("=")

        val host = properties["DB_HOST"]
        val port = properties["DB_PORT"]
        val database = properties["DB_DATABASE"]
        val userName = properties["DB_USERNAME"]
        val password = properties["DB_PASSWORD"]

        val config = Json.obj(
                "url" to "jdbc:postgresql://$host:$port/$database?user=$userName&password=$password",
                "max_pool_size" to 30
        )

        pool = HikariDBPool(config)
        pool.setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)
    }
}
