package io.kup.providers.logger

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.app
import io.kup.container.extensions.instanceOf
import kotlin.test.assertTrue

class LoggerServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind db logger`() {
        LoggerServiceProvider().up()

        assertTrue {
            app.create().instanceOf<Logger>() is DBLogger
        }
    }
}