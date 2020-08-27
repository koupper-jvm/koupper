package com.koupper.providers.db

import com.koupper.container.app
import com.koupper.container.extensions.instanceOf
import com.koupper.providers.ServiceProvider
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.extensions.splitKeyValue

class DBServiceProvider : ServiceProvider() {
    private lateinit var currentDirectory: String
    private lateinit var parser: TextParser

    override fun up() {
        this.currentDirectory = System.getProperty("user.dir")

        this.parser = app.create("TextParserEnvPropertiesTemplate").instanceOf()

        this.registerPostgres()

        this.registerSQLite()
    }

    private fun registerPostgres() {
        this.parser.readFromPath("$currentDirectory/.env")

        val properties: Map<String?, String?> = this.parser.splitKeyValue("=".toRegex())

        val connection = properties["DB_CONNECTION"]

        if (!connection.equals("pgsql")) {
            return
        }

        val host = properties["DB_HOST"]
        val port = properties["DB_PORT"]
        val database = properties["DB_DATABASE"]
        val userName = properties["DB_USERNAME"]
        val password = properties["DB_PASSWORD"]

        app.bind(DBConnector::class, {
            DBPSQLConnector("jdbc:postgresql://$host:$port/$database?user=$userName&password=$password", 30)
        }, "DBPSQLConnector")
    }

    private fun registerSQLite() {
        this.parser.readFromPath("$currentDirectory/.env")

        val properties: Map<String?, String?> = this.parser.splitKeyValue("=".toRegex())

        val connection = properties["DB_CONNECTION"]

        if (!connection.equals("sqlite")) {
            return
        }

        val databaseName = properties["DB_DATABASE"]

        app.bind(DBConnector::class, {
            DBSQLiteConnector("jdbc:sqlite:$databaseName")
        }, "DBSQLiteConnector")
    }
}