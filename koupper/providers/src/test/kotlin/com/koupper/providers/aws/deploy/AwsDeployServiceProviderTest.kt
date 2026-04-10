package com.koupper.providers.aws.deploy

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    fun `should retry lambda publish on transient conflict and then update alias`() {
        val publishAttempts = AtomicInteger(0)
        val provider = AwsCliDeployProvider(
            commandRunner = { args, _, _ ->
                when {
                    args.contains("get-alias") -> AwsCommandProcessResult(0, "41", "", 5)
                    args.contains("update-function-code") -> AwsCommandProcessResult(0, "ok", "", 10)
                    args.contains("function-updated") -> AwsCommandProcessResult(0, "waited", "", 5)
                    args.contains("publish-version") -> {
                        val current = publishAttempts.incrementAndGet()
                        if (current == 1) {
                            AwsCommandProcessResult(255, "", "ResourceConflictException", 5)
                        } else {
                            AwsCommandProcessResult(0, "42", "", 5)
                        }
                    }

                    args.contains("update-alias") -> AwsCommandProcessResult(0, "ok", "", 5)
                    else -> AwsCommandProcessResult(0, "ok", "", 5)
                }
            }
        )

        val artifact = Files.createTempFile("lambda-artifact", ".zip").toFile()
        artifact.writeText("content")

        val result = provider.deployLambda(
            AwsLambdaDeployRequest(
                functionName = "my-handler",
                artifactPath = artifact.absolutePath,
                alias = "live",
                publish = true,
                execution = AwsDeployExecutionOptions(awsRetryCount = 2, awsRetryBackoffMs = 1)
            )
        )

        assertEquals("42", result.deployedVersion)
        assertTrue(result.actions.any { it.action == "lambda-publish-version" && it.attempts == 2 && it.ok })
    }

    @Test
    fun `should propagate operation specific timeout for long s3 and cloudfront calls`() {
        val timeouts = CopyOnWriteArrayList<Pair<String, Long>>()
        val provider = AwsCliDeployProvider(
            timeoutSeconds = 300,
            commandRunner = { args, _, timeout ->
                val op = when {
                    args.contains("cloudfront") -> "cloudfront"
                    args.contains("s3") -> "s3"
                    else -> "other"
                }
                timeouts += op to timeout
                AwsCommandProcessResult(0, "ok", "", 5)
            }
        )

        val distDir = Files.createTempDirectory("site-dist").toFile()
        File(distDir, "index.html").writeText("ok")

        provider.deployStaticSite(
            AwsStaticSiteDeployRequest(
                bucket = "site-bucket",
                distPath = distDir.absolutePath,
                cloudFrontDistributionId = "EDIST123",
                backupMode = "full",
                execution = AwsDeployExecutionOptions(
                    operationTimeoutSeconds = mapOf(
                        "s3-sync" to 1200,
                        "cloudfront-invalidation" to 1500
                    )
                )
            )
        )

        assertTrue(timeouts.any { it.first == "s3" && it.second == 1200L })
        assertTrue(timeouts.any { it.first == "cloudfront" && it.second == 1500L })
    }

    @Test
    fun `should support disabled frontend backup mode without forcing backup sync`() {
        val calls = CopyOnWriteArrayList<String>()
        val provider = AwsCliDeployProvider(
            commandRunner = { args, _, _ ->
                calls += args.joinToString(" ")
                AwsCommandProcessResult(0, "ok", "", 5)
            }
        )

        val distDir = Files.createTempDirectory("site-dist-disabled").toFile()
        File(distDir, "index.html").writeText("ok")

        val result = provider.deployStaticSite(
            AwsStaticSiteDeployRequest(
                bucket = "site-bucket",
                distPath = distDir.absolutePath,
                cloudFrontDistributionId = "EDIST456",
                backupMode = "disabled"
            )
        )

        assertEquals("disabled", result.backupPrefix)
        assertTrue(result.backupCommand.stdout.contains("backup disabled"))
        assertTrue(result.actions.any { it.action == "static-site-backup-disabled" && it.warnings.isNotEmpty() })
        assertTrue(calls.none { it.contains("sync s3://site-bucket s3://site-bucket/releases") })
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
