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

    @Test
    fun `should execute the sql`() = runBlocking {
        launch(Dispatchers.Main) {
            val connector = DBSQLiteConnector("jdbc:sqlite:koupper-scripts-log.db")

            // Because the HikariDBSession has not a `create table` method or a raw query execution way
            connector.session().once { conn ->
                conn.update("create table person (id integer, name string)", emptyList())
            }
        }
    }
}