package com.koupper.providers.db

import com.koupper.os.env
import com.koupper.providers.Setup
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*
import java.sql.Statement

class DBMySQLConnector : DBConnector, Setup() {
    private lateinit var pool: HikariDBPool
    private val host: String get() = env("DB_HOST")
    private val port: String get() = env("DB_PORT")
    private val database: String get() = env("DB_DATABASE")
    private val userName: String get() = env("DB_USERNAME")
    private val password: String get() = env("DB_PASSWORD")

    override suspend fun session(): DBSession {
        val config = Json.obj(
            "url" to "jdbc:mysql://$host:$port/$database?user=$userName&password=$password&useSSL=false&allowPublicKeyRetrieval=true",
            "max_pool_size" to 30
        )

        pool = HikariDBPool(config)
        pool.setInsertStatementMode(Statement.RETURN_GENERATED_KEYS)

        return HikariDBSession(pool, pool.createConnection())
    }
}
