package io.kup.providers.db

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

    @Test
    fun `should generate the sql sentence`() = runBlocking {
        launch(Dispatchers.Main) {  // Will be launched in the mainThreadSurrogate dispatcher
            val sql = Query().fields("*").from("users").where("email" eq "jacob.gacosta@gmail.com").toSql()

            val connector = DBPSQLConnector("jdbc:postgresql://localhost:5432/zigocapital?user=jacobacosta&password=mimamamemima", 30)

            lateinit var rows: List<User>

            connector.session().once { conn ->
                rows = conn.queryPrepared(sql, listOf("jacob.gacosta@gmail.com"), { User(it) }) as List<User>
            }

            assertEquals(1, rows.size)
            rows.forEach {
                assertTrue {
                    it.email.equals("jacob.gacosta@gmail.com")
                    it.firstName.equals("Jacob")
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