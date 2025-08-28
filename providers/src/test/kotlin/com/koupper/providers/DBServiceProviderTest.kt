package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.db.DBPSQLConnector
import com.koupper.providers.db.DBSQLiteConnector
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class DBServiceProviderTest : AnnotationSpec() {
    private var envs: Map<String, String> = mapOf(
        "DB_HOST" to "somehost",
        "DB_PORT" to "someport",
        "DB_DATABASE" to "somedbname",
        "DB_USERNAME" to "someusername",
        "DB_PASSWORD" to "somepassword",
    )

    init {
        DBServiceProvider().up()
    }

    @Test
    fun `should bind psql connector`() {
        withEnvironment(envs) {
            assertTrue {
                app.getInstance(DBConnector::class, "DBPSQLConnector") is DBPSQLConnector
            }
        }
    }

    @Test
    fun `should bind sqlite connector`() {
        withEnvironment(envs) {
            assertTrue {
                app.getInstance(DBConnector::class, "DBSQLiteConnector") is DBSQLiteConnector
            }
        }
    }
}