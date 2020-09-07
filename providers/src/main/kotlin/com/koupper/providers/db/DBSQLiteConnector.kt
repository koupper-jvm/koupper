package com.koupper.providers.db

import com.koupper.providers.db.connections.SQLiteDBPool
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*

class DBSQLiteConnector(urlConnection: String) : DBConnector {
    private var pool: SQLiteDBPool

    init {
        val config = Json.obj(
                "url" to urlConnection
        )

        pool = SQLiteDBPool(config)
    }

    override suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())
}