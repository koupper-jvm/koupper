package com.koupper.providers.db

import io.kotest.core.spec.style.AnnotationSpec
import io.zeko.db.sql.Query
import io.zeko.db.sql.dsl.eq
import io.zeko.model.Entity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DBPSQLConnectorTest : AnnotationSpec() {
    private val mainThreadSurrogate = newSingleThreadContext("UI Thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
    }

    @Ignore
    @Test
    fun `should establish a connection to PSQL database`() = runBlocking {
        launch(Dispatchers.Main) {  // Will be launched in the mainThreadSurrogate dispatcher
            val sql = Query().fields("*").from("someTable").where("field" eq "value").toSql()

            val connector = DBPSQLConnector("jdbc:postgresql://localhost:5432/yourdatabase?user=youruser&password=yourpassword", 30)

            lateinit var rows: List<User>

            connector.session().once { conn ->
                rows = conn.queryPrepared(sql, listOf("email@domain.com"), { User(it) }) as List<User>
            }

            assertEquals(1, rows.size)
            rows.forEach {
                assertTrue {
                    it.email.equals("email@domain.com")
                    it.firstName.equals("YourName")
                }
            }
        }
    }

}

class User : Entity {
    constructor(map: Map<String, Any?>) : super(map)

    constructor(vararg props: Pair<String, Any?>) : super(*props)

    var firstName: String? by map
    var email: String? by map
}