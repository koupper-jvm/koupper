package com.koupper.providers.db

import com.koupper.os.env
import com.koupper.providers.Setup
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*
import java.sql.Statement

class DBPSQLConnector : DBConnector, Setup() {
    private lateinit var pool: HikariDBPool
    private val host = env("DB_HOST")
    private val port = env("DB_PORT")
    private val database = env("DB_DATABASE")
    private val userName = env("DB_USERNAME")
    private val password = env("DB_PASSWORD")

    override suspend fun session(): DBSession {
        val config = Json.obj(
            "url" to "jdbc:postgresql://$host:$port/$database?user=$userName&password=$password",
            "max_pool_size" to 30
        )

        pool = HikariDBPool(config)
        pool.setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)

        return HikariDBSession(pool, pool.createConnection())
    }
}

