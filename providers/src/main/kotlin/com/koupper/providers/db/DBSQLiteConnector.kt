package com.koupper.providers.db

import com.koupper.os.env
import com.koupper.providers.Setup
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*

class DBSQLiteConnector : DBConnector, Setup() {
    private lateinit var pool: HikariDBPool
    private val databaseName = env("DB_DATABASE")

    override suspend fun session(): DBSession {
        val config = Json.obj(
            "url" to "jdbc:sqlite:$databaseName"
        )

        pool = HikariDBPool(config)

        return HikariDBSession(pool, pool.createConnection())
    }
}
