package com.koupper.providers.db

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class DBServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerPostgres()

        this.registerSQLite()
    }

    private fun registerPostgres() {
        app.bind(DBConnector::class, { DBPSQLConnector() }, "DBPSQLConnector")
    }

    private fun registerSQLite() {
        app.bind(DBConnector::class, { DBSQLiteConnector() }, "DBSQLiteConnector")
    }

    override fun externalDependencies() = listOf(
        "org.postgresql:postgresql:42.7.2",
        "org.xerial:sqlite-jdbc:3.45.1.0"
    )
}
