package com.koupper.providers.db

import io.kotest.core.spec.style.AnnotationSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain

class DBSQLiteConnectorTest : AnnotationSpec() {
    private val mainThreadSurrogate = newSingleThreadContext("UI Thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
    }

    @Ignore
    @Test
    fun `should establish a connection to SQLite database`() = runBlocking {
        launch(Dispatchers.Main) {
            val connector = DBSQLiteConnector()
            connector.configFrom("yourPathEnv")

            // Because the HikariDBSession has not a `create table` method or a raw query execution way
            connector.session().once { conn ->
                conn.update("create table yourTable(id integer, field string)", emptyList())
            }
        }
    }
}
