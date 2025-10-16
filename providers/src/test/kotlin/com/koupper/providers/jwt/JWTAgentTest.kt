package com.koupper.providers.jwt

import com.auth0.jwt.exceptions.JWTDecodeException
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import io.kotest.core.annotation.Ignored
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Ignored
class JWTAgentTest : AnnotationSpec() {
    @Test
    fun `should encode and decode a token`() {
        withEnvironment("JWT_SECRET", "7Xu2AiaG-iGX7FHii9aEy@MXuQkVEFdp") {
            val jwtAgent = JWTAgent()

            val token = jwtAgent.encode(
                "{\"credentials\":\"876FSDFh7324\", \"expiresAt\":1636599153, \"iv\": \"876FSDFh7233s\"}",
                JWTAgentEnum.HMAC256
            )

            val decodeToken = jwtAgent.decode(token, JWTAgentEnum.HMAC256)

            assertEquals(decodeToken.claims["credentials"]!!.asString(), "876FSDFh7324")
            assertEquals(decodeToken.claims["expiresAt"]!!.asInt(), 1636599153)
            assertEquals(decodeToken.claims["iv"]!!.asString(), "876FSDFh7233s")
        }
    }

    @Test
    fun `should check a valid token`() {
        withEnvironment("JWT_SECRET", "7Xu2AiaG-iGX7FHii9aEy@MXuQkVEFdp") {
            val jwtAgent = JWTAgent()

            assertDoesNotThrow {
                jwtAgent.decode("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJjcmVkZW50aWFscyI6eyJjaXBoZXJ0ZXh0Ijp7InR5cGUiOiJCdWZmZXIiLCJkYXRhIjpbMjE4LDE2NCw2NiwwLDEzMCwyMiwxMTQsMTQ2LDIwMCwyMDgsMjM4LDExMiwyMDUsNTQsMTUwLDUyLDI1NCwyMjMsMjA3LDE2NiwxNTYsMTIwLDExNiwyNDgsMjA5LDE5MSwxMzksODAsNDAsNTYsMjI0LDIwMywyNTUsMTY1LDEyMCwxODQsMTY5LDQ0LDg1LDIwMiw1NywyMTIsNjksMTI3LDE2OCwyNDMsMzIsMTIxLDEyLDIyNiw5NiwyMDgsMjAwLDIyMCwxNTEsMjQ4LDEwMSwxNywxNjEsMjIsMjAyLDE4OSwxNjksODksMTAsMTYwLDIxMywyMCwxMTIsMjE5LDI1MiwyMTcsMTA0LDI0NSwxMTAsMTA4LDEzOSwxNTEsOTUsNSwyNTUsMTAyLDI1MiwxNjVdfSwiYXV0aF90YWciOnsidHlwZSI6IkJ1ZmZlciIsImRhdGEiOls0LDIyMywyMDgsMjAxLDE1MSwxMzgsMjQwLDE5NSwxMzUsNDIsODIsMjcsMjM0LDIyMCw1NSwxMDZdfX0sImV4cGlyZXNBdCI6MTYzNjQxOTQ4OSwiaXYiOnsidHlwZSI6IkJ1ZmZlciIsImRhdGEiOlsxMDMsMjEzLDMyLDQ4LDIyMiwxOTIsMjE5LDEwOCw1MiwxMjAsMzcsMzIsODUsOTYsMjYsMTIyXX19.jr0ZKVpMhVtbPSsTzsUL5nsh7BLW5CXxltEgZKkJiPg")
            }
        }
    }

    @Test
    fun `should check an invalid token`() {
        withEnvironment("JWT_SECRET", "7Xu2AiaG-iGX7FHii9aEy@MXuQkVEFdp") {
            val exception = assertFailsWith<JWTDecodeException> {
                val jwtAgent = JWTAgent()

                // the first letter 'e' previously,  was changes by E
                jwtAgent.decode("EyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJjcmVkZW50aWFscyI6eyJjaXBoZXJ0ZXh0Ijp7InR5cGUiOiJCdWZmZXIiLCJkYXRhIjpbMjE4LDE2NCw2NiwwLDEzMCwyMiwxMTQsMTQ2LDIwMCwyMDgsMjM4LDExMiwyMDUsNTQsMTUwLDUyLDI1NCwyMjMsMjA3LDE2NiwxNTYsMTIwLDExNiwyNDgsMjA5LDE5MSwxMzksODAsNDAsNTYsMjI0LDIwMywyNTUsMTY1LDEyMCwxODQsMTY5LDQ0LDg1LDIwMiw1NywyMTIsNjksMTI3LDE2OCwyNDMsMzIsMTIxLDEyLDIyNiw5NiwyMDgsMjAwLDIyMCwxNTEsMjQ4LDEwMSwxNywxNjEsMjIsMjAyLDE4OSwxNjksODksMTAsMTYwLDIxMywyMCwxMTIsMjE5LDI1MiwyMTcsMTA0LDI0NSwxMTAsMTA4LDEzOSwxNTEsOTUsNSwyNTUsMTAyLDI1MiwxNjVdfSwiYXV0aF90YWciOnsidHlwZSI6IkJ1ZmZlciIsImRhdGEiOls0LDIyMywyMDgsMjAxLDE1MSwxMzgsMjQwLDE5NSwxMzUsNDIsODIsMjcsMjM0LDIyMCw1NSwxMDZdfX0sImV4cGlyZXNBdCI6MTYzNjQxOTQ4OSwiaXYiOnsidHlwZSI6IkJ1ZmZlciIsImRhdGEiOlsxMDMsMjEzLDMyLDQ4LDIyMiwxOTIsMjE5LDEwOCw1MiwxMjAsMzcsMzIsODUsOTYsMjYsMTIyXX19.jr0ZKVpMhVtbPSsTzsUL5nsh7BLW5CXxltEgZKkJiPg")
            }

            assertTrue {
                exception is JWTDecodeException
            }
        }
    }
}