package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals

class ConfigTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should add script for execution`() {
        val config = Config()

        config.runScript("init.kts", mapOf(
                "user_id" to 1234
        ))

        assertEquals(1234, config.listScripts()["init.kts"]?.get("user_id"))
    }
}