package io.kup.providers

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.app
import io.kup.container.extensions.instanceOf
import io.kup.providers.db.DBConnector
import io.kup.providers.db.DBServiceProvider
import io.kup.providers.db.DBPSQLConnector
import kotlin.test.assertTrue

class DBServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind psql connector`() {
        DBServiceProvider().up()

        assertTrue {
            app.create().instanceOf<DBConnector>() is DBPSQLConnector
        }
    }
}