package com.koupper.providers.db

import io.zeko.db.sql.connections.DBSession

interface DBConnector {
    suspend fun session(): DBSession

    fun configFromPath(configPath: String): DBConnector

    fun configFromUrl(configPath: String): DBConnector

    fun configFromResource(configPath: String): DBConnector
}