package com.koupper.providers.logger

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class PSQLDBLoggerTest : AnnotationSpec() {
    @Test
    fun `should bind db logger`() {
        val envs = mapOf(
            "DB_HOST" to "somehost",
            "DB_PORT" to "someport",
            "DB_DATABASE" to "somedbname",
            "DB_USERNAME" to "someusername",
            "DB_PASSWORD" to "somepassword",
            "LOGGER_TABLE_NAME" to "talename"
        )

        withEnvironment(envs) {
            LoggerServiceProvider().up()

            assertTrue {
                app.getInstance(Logger::class) is PSQLDBLogger
            }
        }
    }
}