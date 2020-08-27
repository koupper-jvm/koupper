package com.koupper.providers.db

import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*
import java.sql.Statement

class DBPSQLConnector(urlConnection: String, maxPoolSize: Int) : DBConnector {
    private var pool: HikariDBPool

    init {
        val config = Json.obj(
                "url" to urlConnection,
                "max_pool_size" to maxPoolSize
        )

        pool = HikariDBPool(config)
        pool.setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)
    }

    override suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())
}