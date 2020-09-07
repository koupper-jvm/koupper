package com.koupper.providers.db

import io.zeko.db.sql.connections.DBSession

interface DBConnector {
    suspend fun session(): DBSession
}