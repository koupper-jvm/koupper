package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.jwt.JWT
import com.koupper.providers.jwt.JWTAgent
import com.koupper.providers.jwt.JWTServiceProvider
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class JWTServiceProviderTest : AnnotationSpec() {
    private var envs: Map<String, String> = mapOf(
        "JWT_SECRET" to "jwysecret",
    )

    @Test
    fun `should bind the crypt0 implementation`() {
        withEnvironment(envs) {
            JWTServiceProvider().up()

            assertTrue {
                app.getInstance(JWT::class) is JWTAgent
            }
        }
    }
}