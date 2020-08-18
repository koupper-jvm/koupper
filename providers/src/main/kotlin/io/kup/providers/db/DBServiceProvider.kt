package io.kup.providers.db

import io.kup.container.app
import io.kup.providers.ServiceProvider

class DBServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerPostgres();
    }

    private fun registerPostgres() {
        app.bind(DBConnector::class, {
            DBPSQLConnector("jdbc:postgresql://localhost:5432/zigocapital?user=jacobacosta&password=mimamamemima", 30)
        })
    }
}