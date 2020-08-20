package com.koupper.providers.logger

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.container.extensions.instanceOf
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