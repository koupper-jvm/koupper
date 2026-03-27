package com.koupper.providers.logger

import com.koupper.os.env
import com.koupper.providers.Setup
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBPSQLConnector
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.StringBuilder

class PSQLDBLogger : Logger, Setup() {
    private var connector: DBConnector = DBPSQLConnector()
    private var tableToLog: String = env("LOGGER_TABLE_NAME")
    private var fields: MutableList<String> = mutableListOf()
    private var values: MutableList<Any> = mutableListOf()

    override fun log(params: Map<String, Any>) {
        params.forEach { (key, value) ->
            fields.add(key)

            values.add(value)
        }

        val query = this.buildQuery()

        runBlocking {
            launch {
                (connector as DBPSQLConnector).session().once { connection ->
                    connection.insert(query, values)
                }
            }
        }
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
