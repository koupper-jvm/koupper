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
}
