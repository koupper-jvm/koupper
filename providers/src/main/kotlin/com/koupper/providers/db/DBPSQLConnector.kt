package com.koupper.providers.db

import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*
import java.sql.Statement

class DBPSQLConnector(urlConnection: String, maxPoolSize: Int) : DBConnector {
    private var pool: HikariDBPool
    private var pool2: JasyncDBPool

    init {
        val config = Json.obj(
                "url" to urlConnection,
                "max_pool_size" to maxPoolSize
        )

        pool = HikariDBPool(config)
        pool.setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)
        pool2 = JasyncDBPool(config)
    }

    suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())

    suspend fun session2(): DBSession = JasyncDBSession(pool2, pool2.createConnection())
}