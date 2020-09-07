package com.koupper.providers.db.connections

import io.vertx.core.json.JsonObject
import io.zeko.db.sql.connections.DBConn
import io.zeko.db.sql.connections.DBPool
import org.sqlite.SQLiteDataSource

class SQLiteDBPool : DBPool {
    private lateinit var ds: SQLiteDataSource

    constructor(json: JsonObject) {
        init(json)
    }

    private fun init(config: JsonObject) {
        val sqLiteDataSource =  SQLiteDataSource()
        sqLiteDataSource.url = config.getString("url")

        this.ds = sqLiteDataSource
    }

    override suspend fun createConnection(): DBConn {
        return SQLiteDBConn(ds.connection)
    }

    override fun getInsertStatementMode(): Int {
        TODO("Not yet implemented")
    }

    override fun setInsertStatementMode(mode: Int) {
        TODO("Not yet implemented")
    }

}