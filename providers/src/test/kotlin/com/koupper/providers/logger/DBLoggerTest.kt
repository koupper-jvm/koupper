package com.koupper.providers.logger

import io.kotest.core.spec.style.AnnotationSpec

class DBLoggerTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should build a functional db logger`() {
        val dbLogger = DBLogger()

        class TestDBLoggerConfig() : LoggerConfiguration {
            override fun setup(): Map<String, Any> {
                return mapOf(
                        "url" to "jdbc:postgresql://localhost:5432/yourdatabase?user=youruser&password=yourpassword",
                        "tableName" to "user_notifications",
                        "params" to mapOf(
                                "user_id" to 31,
                                "type" to "APPLICATION_FUNDED",
                                "profile_type" to "BORROWER",
                                "CONTENT" to "<bold>hello</bold>"
                        )
                )
            }
        }

        dbLogger.configUsing(TestDBLoggerConfig())
        dbLogger.log()

        /*assertDatabaseHas("user_notifications", mapOf(
                "user_id" to "12345"
        ))*/
    }

    @Test
    fun `should throw exception if the db logger configurations are wrong`() {

    }
}

