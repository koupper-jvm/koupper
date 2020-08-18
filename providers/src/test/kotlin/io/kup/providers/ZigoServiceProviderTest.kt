package io.kup.providers

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.app
import io.kup.container.extensions.instanceOf
import zigocapital.db.DBZigoManager
import zigocapital.db.DBZigoManagerImpl
import zigocapital.providers.ZigoServiceProvider
import kotlin.test.assertTrue

class ZigoServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should register db zigo manager`() {
        ZigoServiceProvider().up()

        assertTrue {
            app.create().instanceOf<DBZigoManager>() is DBZigoManagerImpl
        }
    }
}