package com.koupper.providers.db

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.parsing.TextParser

class DBServiceProvider : ServiceProvider() {
    private lateinit var currentDirectory: String
    private lateinit var parser: TextParser

    override fun up() {
        this.currentDirectory = System.getProperty("user.dir")

        this.parser = app.createInstanceOf(TextParser::class, "TextParserEnvPropertiesTemplate")

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
