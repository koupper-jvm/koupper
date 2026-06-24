package com.koupper.providers.db

import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class DBServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.CORE

    override fun up() {
        this.registerPostgres()

        this.registerSQLite()

        this.registerMysql()
    }

    private fun registerPostgres() {
        app.bind(DBConnector::class, { DBPSQLConnector() }, "DBPSQLConnector")
    }

    private fun registerSQLite() {
        app.bind(DBConnector::class, { DBSQLiteConnector() }, "DBSQLiteConnector")
    }

    private fun registerMysql() {
        app.bind(DBConnector::class, { DBMySQLConnector() }, "DBMySQLConnector")
    }

    override fun externalDependencies() = listOf(
        "org.postgresql:postgresql:42.7.2",
        "org.xerial:sqlite-jdbc:3.45.1.0",
        "com.mysql:mysql-connector-j:9.1.0"
    )
}
