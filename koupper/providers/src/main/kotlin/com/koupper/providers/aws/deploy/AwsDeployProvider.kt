package com.koupper.providers.aws.deploy

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AwsCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val dryRun: Boolean = false
)

data class AwsCheckResult(
    val resourceType: String,
    val resourceId: String,
    val ok: Boolean,
    val message: String,
    val command: AwsCommandResult
)

data class AwsPreflightRequest(
    val region: String? = null,
    val lambdas: List<String> = emptyList(),
    val buckets: List<String> = emptyList(),
    val cloudFrontDistributions: List<String> = emptyList(),
    val apiGatewayRestApis: List<String> = emptyList(),
    val dryRun: Boolean = false,
    val strict: Boolean = true
)

data class AwsPreflightReport(
    val ok: Boolean,
    val checks: List<AwsCheckResult>,
    val warnings: List<String>
)

data class AwsLambdaDeployRequest(
    val functionName: String,
    val artifactPath: String,
    val region: String? = null,
    val alias: String? = null,
    val publish: Boolean = true,
    val targetVersionWhenNoPublish: String? = null,
    val dryRun: Boolean = false,
    val rollbackAliasOnFailure: Boolean = true,
    val workingDirectory: String = "."
)

data class AwsLambdaDeployResult(
    val functionName: String,
    val region: String,
    val alias: String?,
    val previousAliasVersion: String?,
    val deployedVersion: String,
    val updateFunctionCode: AwsCommandResult,
    val publishVersion: AwsCommandResult?,
    val updateAlias: AwsCommandResult?
)

data class AwsStaticSiteDeployRequest(
    val bucket: String,
    val distPath: String,
    val cloudFrontDistributionId: String,
    val region: String? = null,
    val releaseKeyPrefix: String? = null,
    val invalidatePaths: List<String> = listOf("/*"),
    val dryRun: Boolean = false,
    val rollbackOnFailure: Boolean = true,
    val workingDirectory: String = "."
)

data class AwsStaticSiteDeployResult(
    val bucket: String,
    val region: String,
    val backupPrefix: String,
    val backupCommand: AwsCommandResult,
    val uploadCommand: AwsCommandResult,
    val invalidateCommand: AwsCommandResult
)

data class AwsApiSmokeEndpoint(
    val name: String,
    val method: String = "GET",
    val apiGatewayId: String? = null,
    val stage: String? = null,
    val path: String = "/",
    val baseUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val expectedStatusCodes: Set<Int> = setOf(200)
)

data class AwsApiSmokeTestRequest(
    val endpoints: List<AwsApiSmokeEndpoint>,
    val region: String? = null,
    val timeoutSeconds: Long = 60,
    val dryRun: Boolean = false
)

data class AwsApiSmokeEndpointResult(
    val name: String,
    val method: String,
    val url: String,
    val expectedStatusCodes: Set<Int>,
    val statusCode: Int?,
    val ok: Boolean,
    val responseSnippet: String,
    val dryRun: Boolean = false
)

data class AwsApiSmokeTestResult(
    val ok: Boolean,
    val region: String,
    val results: List<AwsApiSmokeEndpointResult>
)

interface AwsDeployProvider {
    fun cliVersion(dryRun: Boolean = false): AwsCommandResult
    fun callerIdentity(region: String? = null, dryRun: Boolean = false): AwsCommandResult
    fun preflight(request: AwsPreflightRequest): AwsPreflightReport
    fun deployLambda(request: AwsLambdaDeployRequest): AwsLambdaDeployResult
    fun deployStaticSite(request: AwsStaticSiteDeployRequest): AwsStaticSiteDeployResult
    fun smokeTestApis(request: AwsApiSmokeTestRequest): AwsApiSmokeTestResult
}

class AwsCliDeployProvider(
    private val awsCommand: String = "aws",
    private val defaultRegion: String = "us-east-1",
    private val timeoutSeconds: Long = 300
) : AwsDeployProvider {

    override fun cliVersion(dryRun: Boolean): AwsCommandResult {
        return runAws(listOf("--version"), region = null, dryRun = dryRun)
    }

    override fun callerIdentity(region: String?, dryRun: Boolean): AwsCommandResult {
        return runAws(listOf("sts", "get-caller-identity"), region = resolveRegion(region), dryRun = dryRun)
    }

    override fun preflight(request: AwsPreflightRequest): AwsPreflightReport {
        val checks = mutableListOf<AwsCheckResult>()
        val warnings = mutableListOf<String>()
        val region = resolveRegion(request.region)

        checks += checkResource(
            resourceType = "aws-cli",
            resourceId = awsCommand,
            command = cliVersion(dryRun = request.dryRun),
            strict = request.strict,
            warnings = warnings
        )

        checks += checkResource(
            resourceType = "identity",
            resourceId = "caller",
            command = callerIdentity(region = region, dryRun = request.dryRun),
            strict = request.strict,
            warnings = warnings
        )

        request.lambdas.forEach { fn ->
            checks += checkResource(
                resourceType = "lambda",
                resourceId = fn,
                command = runAws(
                    listOf("lambda", "get-function", "--function-name", fn),
                    region = region,
                    dryRun = request.dryRun
                ),
                strict = request.strict,
                warnings = warnings
            )
        }

        request.buckets.forEach { bucket ->
            checks += checkResource(
                resourceType = "s3-bucket",
                resourceId = bucket,
                command = runAws(
                    listOf("s3api", "head-bucket", "--bucket", bucket),
                    region = region,
                    dryRun = request.dryRun
                ),
                strict = request.strict,
                warnings = warnings
            )
        }

        request.cloudFrontDistributions.forEach { distId ->
            checks += checkResource(
                resourceType = "cloudfront-distribution",
                resourceId = distId,
                command = runAws(
                    listOf("cloudfront", "get-distribution", "--id", distId),
                    region = null,
                    dryRun = request.dryRun
                ),
                strict = request.strict,
                warnings = warnings
            )
        }

        request.apiGatewayRestApis.forEach { apiId ->
            checks += checkResource(
                resourceType = "api-gateway-rest-api",
                resourceId = apiId,
                command = runAws(
                    listOf("apigateway", "get-rest-api", "--rest-api-id", apiId),
                    region = region,
                    dryRun = request.dryRun
                ),
                strict = request.strict,
                warnings = warnings
            )
        }

        return AwsPreflightReport(
            ok = checks.all { it.ok },
            checks = checks,
            warnings = warnings
        )
    }

    override fun deployLambda(request: AwsLambdaDeployRequest): AwsLambdaDeployResult {
        val region = resolveRegion(request.region)
        val alias = request.alias?.trim().orEmpty().ifBlank { null }
        val cwd = File(request.workingDirectory)
        val artifact = File(request.artifactPath)

        if (!request.dryRun && !artifact.exists()) {
            error("artifact not found: ${artifact.absolutePath}")
        }

        if (!request.publish && alias != null && request.targetVersionWhenNoPublish.isNullOrBlank()) {
            error("targetVersionWhenNoPublish is required when publish=false and alias is set")
        }

        var previousAliasVersion: String? = null
        if (alias != null) {
            val aliasRead = runAws(
                listOf(
                    "lambda", "get-alias",
                    "--function-name", request.functionName,
                    "--name", alias,
                    "--query", "FunctionVersion",
                    "--output", "text"
                ),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd
            )

            if (aliasRead.exitCode == 0) {
                previousAliasVersion = aliasRead.stdout.lines().lastOrNull()?.trim().orEmpty().ifBlank { null }
            }
        }

        return try {
            val updateFunctionCode = runAws(
                listOf(
                    "lambda", "update-function-code",
                    "--function-name", request.functionName,
                    "--zip-file", "fileb://${artifact.absolutePath}",
                    "--publish"
                ),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd
            )
            ensureOk(updateFunctionCode, "lambda update-function-code")

            val publishVersion = if (request.publish) {
                val publish = runAws(
                    listOf(
                        "lambda", "publish-version",
                        "--function-name", request.functionName,
                        "--query", "Version",
                        "--output", "text"
                    ),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd
                )
                ensureOk(publish, "lambda publish-version")
                publish
            } else {
                null
            }

            val deployedVersion = when {
                request.dryRun -> "dry-run"
                request.publish -> publishVersion?.stdout?.lines()?.lastOrNull()?.trim().orEmpty().ifBlank { "latest" }
                else -> request.targetVersionWhenNoPublish!!
            }

            val updateAlias = if (alias != null) {
                val aliasResult = runAws(
                    listOf(
                        "lambda", "update-alias",
                        "--function-name", request.functionName,
                        "--name", alias,
                        "--function-version", deployedVersion
                    ),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd
                )
                ensureOk(aliasResult, "lambda update-alias")
                aliasResult
            } else {
                null
            }

            AwsLambdaDeployResult(
                functionName = request.functionName,
                region = region,
                alias = alias,
                previousAliasVersion = previousAliasVersion,
                deployedVersion = deployedVersion,
                updateFunctionCode = updateFunctionCode,
                publishVersion = publishVersion,
                updateAlias = updateAlias
            )
        } catch (error: Throwable) {
            if (!request.dryRun && request.rollbackAliasOnFailure && alias != null && !previousAliasVersion.isNullOrBlank()) {
                runAws(
                    listOf(
                        "lambda", "update-alias",
                        "--function-name", request.functionName,
                        "--name", alias,
                        "--function-version", previousAliasVersion
                    ),
                    region = region,
                    dryRun = false,
                    workingDirectory = cwd
                )
            }
            throw error
        }
    }

    override fun deployStaticSite(request: AwsStaticSiteDeployRequest): AwsStaticSiteDeployResult {
        val region = resolveRegion(request.region)
        val cwd = File(request.workingDirectory)
        val dist = File(request.distPath)
        if (!request.dryRun && !dist.exists()) {
            error("dist path not found: ${dist.absolutePath}")
        }

        val backupPrefix = request.releaseKeyPrefix ?: "releases/${Instant.now().toEpochMilli()}/site"
        val backupSource = "s3://${request.bucket}"
        val backupTarget = "s3://${request.bucket}/${backupPrefix.trimStart('/')}"

        val backupCommand = runAws(
            listOf("s3", "sync", backupSource, backupTarget, "--delete"),
            region = region,
            dryRun = request.dryRun,
            workingDirectory = cwd
        )
        ensureOk(backupCommand, "s3 backup")

        return try {
            val uploadCommand = runAws(
                listOf("s3", "sync", dist.absolutePath, backupSource, "--delete"),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd
            )
            ensureOk(uploadCommand, "s3 upload")

            val invalidateCommand = runAws(
                listOf(
                    "cloudfront", "create-invalidation",
                    "--distribution-id", request.cloudFrontDistributionId,
                    "--paths"
                ) + request.invalidatePaths,
                region = null,
                dryRun = request.dryRun,
                workingDirectory = cwd
            )
            ensureOk(invalidateCommand, "cloudfront invalidation")

            AwsStaticSiteDeployResult(
                bucket = request.bucket,
                region = region,
                backupPrefix = backupPrefix,
                backupCommand = backupCommand,
                uploadCommand = uploadCommand,
                invalidateCommand = invalidateCommand
            )
        } catch (error: Throwable) {
            if (!request.dryRun && request.rollbackOnFailure) {
                runAws(
                    listOf("s3", "sync", backupTarget, backupSource, "--delete"),
                    region = region,
                    dryRun = false,
                    workingDirectory = cwd
                )
                runAws(
                    listOf(
                        "cloudfront", "create-invalidation",
                        "--distribution-id", request.cloudFrontDistributionId,
                        "--paths"
                    ) + request.invalidatePaths,
                    region = null,
                    dryRun = false,
                    workingDirectory = cwd
                )
            }
            throw error
        }
    }

    override fun smokeTestApis(request: AwsApiSmokeTestRequest): AwsApiSmokeTestResult {
        val region = resolveRegion(request.region)
        val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds))
            .build()

        val results = request.endpoints.map { endpoint ->
            val method = endpoint.method.uppercase()
            val url = resolveEndpointUrl(endpoint, region)
            val expected = if (endpoint.expectedStatusCodes.isEmpty()) setOf(200) else endpoint.expectedStatusCodes

            if (request.dryRun) {
                AwsApiSmokeEndpointResult(
                    name = endpoint.name,
                    method = method,
                    url = url,
                    expectedStatusCodes = expected,
                    statusCode = null,
                    ok = true,
                    responseSnippet = "dry-run",
                    dryRun = true
                )
            } else {
                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(request.timeoutSeconds))

                endpoint.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

                val httpRequest = when (method) {
                    "POST", "PUT", "PATCH" -> requestBuilder.method(
                        method,
                        HttpRequest.BodyPublishers.ofString(endpoint.body ?: "")
                    ).build()
                    "DELETE" -> requestBuilder.DELETE().build()
                    else -> requestBuilder.GET().build()
                }

                val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                val snippet = response.body().take(500)
                AwsApiSmokeEndpointResult(
                    name = endpoint.name,
                    method = method,
                    url = url,
                    expectedStatusCodes = expected,
                    statusCode = response.statusCode(),
                    ok = expected.contains(response.statusCode()),
                    responseSnippet = snippet
                )
            }
        }

        return AwsApiSmokeTestResult(
            ok = results.all { it.ok },
            region = region,
            results = results
        )
    }

    private fun resolveEndpointUrl(endpoint: AwsApiSmokeEndpoint, region: String): String {
        val path = if (endpoint.path.startsWith('/')) endpoint.path else "/${endpoint.path}"

        endpoint.baseUrl?.takeIf { it.isNotBlank() }?.let { base ->
            return if (base.endsWith('/')) base.dropLast(1) + path else base + path
        }

        val apiId = endpoint.apiGatewayId ?: error("apiGatewayId or baseUrl is required for endpoint '${endpoint.name}'")
        val stage = endpoint.stage?.ifBlank { "prod" } ?: "prod"
        return "https://$apiId.execute-api.$region.amazonaws.com/$stage$path"
    }

    private fun resolveRegion(region: String?): String {
        return region?.ifBlank { null } ?: defaultRegion
    }

    private fun checkResource(
        resourceType: String,
        resourceId: String,
        command: AwsCommandResult,
        strict: Boolean,
        warnings: MutableList<String>
    ): AwsCheckResult {
        val ok = command.exitCode == 0
        val message = if (ok) {
            command.stdout.ifBlank { "ok" }
        } else {
            command.stderr.ifBlank { command.stdout }.ifBlank { "failed" }
        }

        if (!ok && !strict) {
            warnings += "[$resourceType:$resourceId] $message"
        }

        if (!ok && strict) {
            error("preflight check failed for $resourceType:$resourceId -> $message")
        }

        return AwsCheckResult(
            resourceType = resourceType,
            resourceId = resourceId,
            ok = ok,
            message = message,
            command = command
        )
    }

    private fun ensureOk(result: AwsCommandResult, step: String) {
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }
            error("$step failed: $detail")
        }
    }

    private fun runAws(
        args: List<String>,
        region: String?,
        dryRun: Boolean,
        workingDirectory: File = File("."),
        timeout: Long = timeoutSeconds
    ): AwsCommandResult {
        val allArgs = mutableListOf(awsCommand)
        allArgs += args
        if (!region.isNullOrBlank()) {
            allArgs += listOf("--region", region)
        }

        val command = allArgs.joinToString(" ")
        if (dryRun) {
            return AwsCommandResult(
                command = command,
                exitCode = 0,
                stdout = "dry-run",
                stderr = "",
                durationMs = 0,
                dryRun = true
            )
        }

        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder(allArgs)
            .directory(workingDirectory)
            .start()

        val completed = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw IllegalStateException("aws command timed out after ${timeout}s: $command")
        }

        return AwsCommandResult(
            command = command,
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            durationMs = System.currentTimeMillis() - startedAt,
            dryRun = false
        )
    }
}
