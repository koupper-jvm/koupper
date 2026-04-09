package com.koupper.providers.aws.deploy

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsDeployServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind aws deploy provider`() {
        AwsDeployServiceProvider().up()

        assertTrue {
            app.getInstance(AwsDeployProvider::class) is AwsCliDeployProvider
        }
    }

    @Test
    fun `should build smoke endpoint urls in dry-run`() {
        val provider = AwsCliDeployProvider(defaultRegion = "us-east-1")
        val smoke = provider.smokeTestApis(
            AwsApiSmokeTestRequest(
                dryRun = true,
                endpoints = listOf(
                    AwsApiSmokeEndpoint(
                        name = "api-gw",
                        apiGatewayId = "abc123",
                        stage = "dev",
                        path = "/health",
                        expectedStatusCodes = setOf(200)
                    ),
                    AwsApiSmokeEndpoint(
                        name = "base-url",
                        baseUrl = "https://example.com",
                        path = "/status",
                        expectedStatusCodes = setOf(200, 202)
                    )
                )
            )
        )

        assertTrue { smoke.ok }
        assertEquals(2, smoke.results.size)
        assertEquals("https://abc123.execute-api.us-east-1.amazonaws.com/dev/health", smoke.results[0].url)
        assertEquals("https://example.com/status", smoke.results[1].url)
    }
}
