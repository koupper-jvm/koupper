package com.koupper.providers.db

import com.koupper.os.env
import com.koupper.providers.Setup
import io.vertx.kotlin.core.json.Json
import io.vertx.kotlin.core.json.obj
import io.zeko.db.sql.connections.*

class DBSQLiteConnector : DBConnector, Setup() {

    private val databaseName: String get() = env("DB_DATABASE")

    // Pool created once on first session() call — at that point scriptContext is already set
    private val pool: HikariDBPool by lazy {
        Class.forName("org.sqlite.JDBC")
        HikariDBPool(Json.obj("url" to "jdbc:sqlite:$databaseName"))
    }

    override suspend fun session(): DBSession = HikariDBSession(pool, pool.createConnection())
}
