package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.container.extensions.instanceOf
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.db.DBPSQLConnector
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