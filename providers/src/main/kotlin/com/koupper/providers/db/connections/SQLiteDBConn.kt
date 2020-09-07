package com.koupper.providers.db.connections

import io.zeko.db.sql.connections.DBConn
import java.sql.Connection

class SQLiteDBConn(val conn: Connection) : DBConn {
    override suspend fun beginTx() {
        conn.autoCommit = false
    }

    override suspend fun endTx() {
        conn.autoCommit = true
    }

    override suspend fun commit() {
        conn.commit()
    }

    override suspend fun close() {
        conn.close()
    }

    override suspend fun rollback() {
        conn.rollback()
    }

    override fun raw(): Connection {
        return conn
    }
}