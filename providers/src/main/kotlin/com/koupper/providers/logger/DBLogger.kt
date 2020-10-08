package com.koupper.providers.logger

import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBPSQLConnector
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.StringBuilder

class DBLogger : Logger {
    private lateinit var connector: DBConnector
    private lateinit var query: String
    private lateinit var tableToLog: String
    private var fields: MutableList<String> = mutableListOf()
    private var values: MutableList<Any> = mutableListOf()

    override fun log() {
        runBlocking {
            launch {
                (connector as DBPSQLConnector).session().once { connection ->
                    connection.insert(query, values)
                }
            }
        }
    }

    override fun configUsing(configuration: LoggerConfiguration): Logger {
        configuration.setup().forEach { (key, value) ->
            if (key == "url") {
                this.connector = DBPSQLConnector()
                this.connector.configFromUrl(value as String)
            }

            if (key == "tableName") {
                this.tableToLog = value as String
            }

            if (key == "params") {
                (value as Map<String, Any>).forEach { (key, value) ->
                    fields.add(key)

                    values.add(value)
                }
            }
        }

        this.query = this.buildQuery()

        return this
    }

    private fun buildQuery(): String {
        val stringFields = StringBuilder()

        val missingFields = StringBuilder()

        for ((index, value) in this.fields.iterator().withIndex()) {
            if (index == this.fields.size - 1) {
                missingFields.append("?")
                stringFields.append("$value")
            } else {
                missingFields.append("?, ")
                stringFields.append("$value, ")
            }
        }

        return "INSERT INTO $tableToLog ($stringFields) VALUES ($missingFields)"
    }
}
