package com.koupper.providers.db

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

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