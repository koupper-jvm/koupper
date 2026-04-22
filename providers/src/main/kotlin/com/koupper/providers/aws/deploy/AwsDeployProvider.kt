package com.koupper.providers.aws.deploy

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.pow

data class AwsCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val dryRun: Boolean = false
)

data class AwsActionResult(
    val ok: Boolean,
    val action: String,
    val exitCode: Int,
    val durationMs: Long,
    val attempts: Int,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val nextAction: String? = null
)

data class AwsDeployExecutionOptions(
    val awsTimeoutSeconds: Long? = null,
    val awsRetryCount: Int? = null,
    val awsRetryBackoffMs: Long? = null,
    val operationTimeoutSeconds: Map<String, Long> = emptyMap()
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
    val strict: Boolean = true,
    val execution: AwsDeployExecutionOptions = AwsDeployExecutionOptions()
)

data class AwsPreflightReport(
    val ok: Boolean,
    val checks: List<AwsCheckResult>,
    val warnings: List<String>,
    val actions: List<AwsActionResult> = emptyList()
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
    val workingDirectory: String = ".",
    val execution: AwsDeployExecutionOptions = AwsDeployExecutionOptions()
)

data class AwsLambdaDeployResult(
    val functionName: String,
    val region: String,
    val alias: String?,
    val previousAliasVersion: String?,
    val deployedVersion: String,
    val updateFunctionCode: AwsCommandResult,
    val publishVersion: AwsCommandResult?,
    val updateAlias: AwsCommandResult?,
    val actions: List<AwsActionResult> = emptyList()
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
    val backupMode: String = "incremental",
    val workingDirectory: String = ".",
    val execution: AwsDeployExecutionOptions = AwsDeployExecutionOptions()
)

data class AwsStaticSiteDeployResult(
    val bucket: String,
    val region: String,
    val backupPrefix: String,
    val backupCommand: AwsCommandResult,
    val uploadCommand: AwsCommandResult,
    val invalidateCommand: AwsCommandResult,
    val actions: List<AwsActionResult> = emptyList()
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
    val results: List<AwsApiSmokeEndpointResult>,
    val actions: List<AwsActionResult> = emptyList()
)

interface AwsDeployProvider {
    fun cliVersion(dryRun: Boolean = false): AwsCommandResult
    fun callerIdentity(region: String? = null, dryRun: Boolean = false): AwsCommandResult
    fun preflight(request: AwsPreflightRequest): AwsPreflightReport
    fun deployLambda(request: AwsLambdaDeployRequest): AwsLambdaDeployResult
    fun deployStaticSite(request: AwsStaticSiteDeployRequest): AwsStaticSiteDeployResult
    fun smokeTestApis(request: AwsApiSmokeTestRequest): AwsApiSmokeTestResult
}

data class AwsCommandProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false
)

typealias AwsCommandRunner = (args: List<String>, workingDirectory: File, timeoutSeconds: Long) -> AwsCommandProcessResult

private enum class FrontendBackupMode {
    FULL,
    INCREMENTAL,
    DISABLED
}

private data class AwsActionExecution(
    val command: AwsCommandResult,
    val action: AwsActionResult
)

class AwsCliDeployProvider(
    private val awsCommand: String = "aws",
    private val defaultRegion: String = "us-east-1",
    private val timeoutSeconds: Long = 300,
    private val defaultRetryCount: Int = 2,
    private val defaultRetryBackoffMs: Long = 500,
    private val commandRunner: AwsCommandRunner? = null
) : AwsDeployProvider {

    override fun cliVersion(dryRun: Boolean): AwsCommandResult {
        return runAwsAction(
            action = "aws-cli-version",
            args = listOf("--version"),
            region = null,
            dryRun = dryRun,
            execution = AwsDeployExecutionOptions()
        ).command
    }

    override fun callerIdentity(region: String?, dryRun: Boolean): AwsCommandResult {
        return runAwsAction(
            action = "aws-caller-identity",
            args = listOf("sts", "get-caller-identity"),
            region = resolveRegion(region),
            dryRun = dryRun,
            execution = AwsDeployExecutionOptions()
        ).command
    }

    override fun preflight(request: AwsPreflightRequest): AwsPreflightReport {
        val checks = mutableListOf<AwsCheckResult>()
        val warnings = mutableListOf<String>()
        val actions = mutableListOf<AwsActionResult>()
        val region = resolveRegion(request.region)

        val cliVersion = runAwsAction(
            action = "preflight-cli-version",
            args = listOf("--version"),
            region = null,
            dryRun = request.dryRun,
            execution = request.execution
        )
        actions += cliVersion.action
        checks += checkResource("aws-cli", awsCommand, cliVersion.command, request.strict, warnings)

        val identity = runAwsAction(
            action = "preflight-caller-identity",
            args = listOf("sts", "get-caller-identity"),
            region = region,
            dryRun = request.dryRun,
            execution = request.execution
        )
        actions += identity.action
        checks += checkResource("identity", "caller", identity.command, request.strict, warnings)

        request.lambdas.forEach { fn ->
            val action = runAwsAction(
                action = "preflight-lambda-get-function",
                args = listOf("lambda", "get-function", "--function-name", fn),
                region = region,
                dryRun = request.dryRun,
                execution = request.execution
            )
            actions += action.action
            checks += checkResource("lambda", fn, action.command, request.strict, warnings)
        }

        request.buckets.forEach { bucket ->
            val action = runAwsAction(
                action = "preflight-s3-head-bucket",
                args = listOf("s3api", "head-bucket", "--bucket", bucket),
                region = region,
                dryRun = request.dryRun,
                execution = request.execution
            )
            actions += action.action
            checks += checkResource("s3-bucket", bucket, action.command, request.strict, warnings)
        }

        request.cloudFrontDistributions.forEach { distId ->
            val action = runAwsAction(
                action = "preflight-cloudfront-get-distribution",
                args = listOf("cloudfront", "get-distribution", "--id", distId),
                region = null,
                dryRun = request.dryRun,
                execution = request.execution,
                timeoutKey = "cloudfront-get-distribution"
            )
            actions += action.action
            checks += checkResource("cloudfront-distribution", distId, action.command, request.strict, warnings)
        }

        request.apiGatewayRestApis.forEach { apiId ->
            val action = runAwsAction(
                action = "preflight-apigateway-get-rest-api",
                args = listOf("apigateway", "get-rest-api", "--rest-api-id", apiId),
                region = region,
                dryRun = request.dryRun,
                execution = request.execution
            )
            actions += action.action
            checks += checkResource("api-gateway-rest-api", apiId, action.command, request.strict, warnings)
        }

        return AwsPreflightReport(
            ok = checks.all { it.ok },
            checks = checks,
            warnings = warnings,
            actions = actions
        )
    }

    override fun deployLambda(request: AwsLambdaDeployRequest): AwsLambdaDeployResult {
        val region = resolveRegion(request.region)
        val alias = request.alias?.trim().orEmpty().ifBlank { null }
        val cwd = File(request.workingDirectory)
        val artifact = File(request.artifactPath)
        val actions = mutableListOf<AwsActionResult>()

        if (!request.dryRun && !artifact.exists()) {
            error("artifact not found: ${artifact.absolutePath}")
        }

        if (!request.publish && alias != null && request.targetVersionWhenNoPublish.isNullOrBlank()) {
            error("targetVersionWhenNoPublish is required when publish=false and alias is set")
        }

        var previousAliasVersion: String? = null
        if (alias != null) {
            val aliasRead = runAwsAction(
                action = "lambda-get-alias",
                args = listOf(
                    "lambda", "get-alias",
                    "--function-name", request.functionName,
                    "--name", alias,
                    "--query", "FunctionVersion",
                    "--output", "text"
                ),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd,
                execution = request.execution
            )
            actions += aliasRead.action
            if (aliasRead.command.exitCode == 0) {
                previousAliasVersion = aliasRead.command.stdout.lines().lastOrNull()?.trim().orEmpty().ifBlank { null }
            }
        }

        return try {
            val updateFunctionCode = runAwsAction(
                action = "lambda-update-function-code",
                args = listOf(
                    "lambda", "update-function-code",
                    "--function-name", request.functionName,
                    "--zip-file", "fileb://${artifact.absolutePath}"
                ),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd,
                execution = request.execution,
                timeoutKey = "lambda-update-function-code"
            )
            actions += updateFunctionCode.action
            ensureOk(updateFunctionCode, "lambda update-function-code")

            val waitForUpdate = runAwsAction(
                action = "lambda-wait-function-updated",
                args = listOf(
                    "lambda", "wait", "function-updated",
                    "--function-name", request.functionName
                ),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd,
                execution = request.execution,
                timeoutKey = "lambda-wait-function-updated"
            )
            actions += waitForUpdate.action
            ensureOk(waitForUpdate, "lambda wait function-updated")

            val publishVersion = if (request.publish) {
                val publish = runAwsAction(
                    action = "lambda-publish-version",
                    args = listOf(
                        "lambda", "publish-version",
                        "--function-name", request.functionName,
                        "--query", "Version",
                        "--output", "text"
                    ),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "lambda-publish-version"
                )
                actions += publish.action
                ensureOk(publish, "lambda publish-version")
                publish.command
            } else {
                null
            }

            val deployedVersion = when {
                request.dryRun -> "dry-run"
                request.publish -> publishVersion?.stdout?.lines()?.lastOrNull()?.trim().orEmpty().ifBlank { "latest" }
                else -> request.targetVersionWhenNoPublish!!
            }

            val updateAlias = if (alias != null) {
                val aliasResult = runAwsAction(
                    action = "lambda-update-alias",
                    args = listOf(
                        "lambda", "update-alias",
                        "--function-name", request.functionName,
                        "--name", alias,
                        "--function-version", deployedVersion
                    ),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "lambda-update-alias"
                )
                actions += aliasResult.action
                ensureOk(aliasResult, "lambda update-alias")
                aliasResult.command
            } else {
                null
            }

            AwsLambdaDeployResult(
                functionName = request.functionName,
                region = region,
                alias = alias,
                previousAliasVersion = previousAliasVersion,
                deployedVersion = deployedVersion,
                updateFunctionCode = updateFunctionCode.command,
                publishVersion = publishVersion,
                updateAlias = updateAlias,
                actions = actions
            )
        } catch (error: Throwable) {
            if (!request.dryRun && request.rollbackAliasOnFailure && alias != null && !previousAliasVersion.isNullOrBlank()) {
                val rollbackAlias = runAwsAction(
                    action = "lambda-rollback-alias",
                    args = listOf(
                        "lambda", "update-alias",
                        "--function-name", request.functionName,
                        "--name", alias,
                        "--function-version", previousAliasVersion
                    ),
                    region = region,
                    dryRun = false,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "lambda-update-alias"
                )
                actions += rollbackAlias.action
            }
            throw IllegalStateException("lambda deploy failed for ${request.functionName}: ${error.message}", error)
        }
    }

    override fun deployStaticSite(request: AwsStaticSiteDeployRequest): AwsStaticSiteDeployResult {
        val region = resolveRegion(request.region)
        val cwd = File(request.workingDirectory)
        val dist = File(request.distPath)
        val actions = mutableListOf<AwsActionResult>()

        if (!request.dryRun && !dist.exists()) {
            error("dist path not found: ${dist.absolutePath}")
        }

        val backupMode = parseBackupMode(request.backupMode)
        val backupPrefix = request.releaseKeyPrefix ?: "releases/${Instant.now().toEpochMilli()}/site"
        val backupSource = "s3://${request.bucket}"
        val backupTarget = "s3://${request.bucket}/${backupPrefix.trimStart('/')}"

        val backupCommand = when (backupMode) {
            FrontendBackupMode.FULL -> {
                val backup = runAwsAction(
                    action = "static-site-backup-full",
                    args = listOf("s3", "sync", backupSource, backupTarget, "--delete"),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "s3-sync"
                )
                actions += backup.action
                ensureOk(backup, "s3 backup full")
                backup.command
            }

            FrontendBackupMode.INCREMENTAL -> {
                val backup = runAwsAction(
                    action = "static-site-backup-incremental",
                    args = listOf("s3", "sync", backupSource, backupTarget),
                    region = region,
                    dryRun = request.dryRun,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "s3-sync"
                )
                actions += backup.action
                ensureOk(backup, "s3 backup incremental")
                backup.command
            }

            FrontendBackupMode.DISABLED -> {
                val action = AwsActionResult(
                    ok = true,
                    action = "static-site-backup-disabled",
                    exitCode = 0,
                    durationMs = 0,
                    attempts = 1,
                    warnings = listOf("Frontend backup disabled; rollback cannot restore previous site state"),
                    nextAction = "Enable backup_mode=incremental or full for rollback-capable deployments"
                )
                actions += action
                AwsCommandResult(
                    command = "backup disabled",
                    exitCode = 0,
                    stdout = "backup disabled",
                    stderr = "",
                    durationMs = 0,
                    dryRun = request.dryRun
                )
            }
        }

        return try {
            val uploadCommand = runAwsAction(
                action = "static-site-upload",
                args = listOf("s3", "sync", dist.absolutePath, backupSource, "--delete"),
                region = region,
                dryRun = request.dryRun,
                workingDirectory = cwd,
                execution = request.execution,
                timeoutKey = "s3-sync"
            )
            actions += uploadCommand.action
            ensureOk(uploadCommand, "s3 upload")

            val invalidateCommand = runAwsAction(
                action = "static-site-cloudfront-invalidation",
                args = listOf(
                    "cloudfront", "create-invalidation",
                    "--distribution-id", request.cloudFrontDistributionId,
                    "--paths"
                ) + request.invalidatePaths,
                region = null,
                dryRun = request.dryRun,
                workingDirectory = cwd,
                execution = request.execution,
                timeoutKey = "cloudfront-invalidation"
            )
            actions += invalidateCommand.action
            ensureOk(invalidateCommand, "cloudfront invalidation")

            AwsStaticSiteDeployResult(
                bucket = request.bucket,
                region = region,
                backupPrefix = if (backupMode == FrontendBackupMode.DISABLED) "disabled" else backupPrefix,
                backupCommand = backupCommand,
                uploadCommand = uploadCommand.command,
                invalidateCommand = invalidateCommand.command,
                actions = actions
            )
        } catch (error: Throwable) {
            if (!request.dryRun && request.rollbackOnFailure && backupMode != FrontendBackupMode.DISABLED) {
                val rollbackUpload = runAwsAction(
                    action = "static-site-rollback-upload",
                    args = listOf("s3", "sync", backupTarget, backupSource, "--delete"),
                    region = region,
                    dryRun = false,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "s3-sync"
                )
                actions += rollbackUpload.action

                val rollbackInvalidate = runAwsAction(
                    action = "static-site-rollback-invalidation",
                    args = listOf(
                        "cloudfront", "create-invalidation",
                        "--distribution-id", request.cloudFrontDistributionId,
                        "--paths"
                    ) + request.invalidatePaths,
                    region = null,
                    dryRun = false,
                    workingDirectory = cwd,
                    execution = request.execution,
                    timeoutKey = "cloudfront-invalidation"
                )
                actions += rollbackInvalidate.action
            }
            throw IllegalStateException("static site deploy failed for bucket ${request.bucket}: ${error.message}", error)
        }
    }

    override fun smokeTestApis(request: AwsApiSmokeTestRequest): AwsApiSmokeTestResult {
        val region = resolveRegion(request.region)
        val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds))
            .build()

        val actions = mutableListOf<AwsActionResult>()

        val results = request.endpoints.map { endpoint ->
            val startedAt = System.currentTimeMillis()
            val method = endpoint.method.uppercase()
            val url = resolveEndpointUrl(endpoint, region)
            val expected = if (endpoint.expectedStatusCodes.isEmpty()) setOf(200) else endpoint.expectedStatusCodes

            if (request.dryRun) {
                val duration = System.currentTimeMillis() - startedAt
                actions += AwsActionResult(
                    ok = true,
                    action = "api-smoke-${endpoint.name}",
                    exitCode = 0,
                    durationMs = duration,
                    attempts = 1,
                    warnings = emptyList(),
                    errors = emptyList(),
                    nextAction = null
                )
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
                val ok = expected.contains(response.statusCode())
                val duration = System.currentTimeMillis() - startedAt
                actions += AwsActionResult(
                    ok = ok,
                    action = "api-smoke-${endpoint.name}",
                    exitCode = if (ok) 0 else 1,
                    durationMs = duration,
                    attempts = 1,
                    warnings = if (ok) emptyList() else listOf("Unexpected status code for ${endpoint.name}"),
                    errors = if (ok) emptyList() else listOf("Expected ${expected.joinToString(",")}, got ${response.statusCode()}"),
                    nextAction = if (ok) null else "Validate API deployment and endpoint readiness"
                )

                AwsApiSmokeEndpointResult(
                    name = endpoint.name,
                    method = method,
                    url = url,
                    expectedStatusCodes = expected,
                    statusCode = response.statusCode(),
                    ok = ok,
                    responseSnippet = snippet
                )
            }
        }

        return AwsApiSmokeTestResult(
            ok = results.all { it.ok },
            region = region,
            results = results,
            actions = actions
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

    private fun parseBackupMode(rawMode: String): FrontendBackupMode {
        return when (rawMode.trim().lowercase()) {
            "full" -> FrontendBackupMode.FULL
            "incremental", "" -> FrontendBackupMode.INCREMENTAL
            "disabled" -> FrontendBackupMode.DISABLED
            else -> error("Invalid backup_mode '$rawMode'. Allowed values: full | incremental | disabled")
        }
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

    private fun ensureOk(execution: AwsActionExecution, step: String) {
        if (!execution.action.ok) {
            val details = if (execution.action.errors.isEmpty()) {
                execution.command.stderr.ifBlank { execution.command.stdout }.ifBlank { "unknown error" }
            } else {
                execution.action.errors.joinToString(" | ")
            }
            val next = execution.action.nextAction?.let { " Next action: $it" } ?: ""
            error("$step failed: $details.$next")
        }
    }

    private fun runAwsAction(
        action: String,
        args: List<String>,
        region: String?,
        dryRun: Boolean,
        execution: AwsDeployExecutionOptions,
        workingDirectory: File = File("."),
        timeoutKey: String = action
    ): AwsActionExecution {
        val timeout = resolveTimeoutSeconds(execution, timeoutKey)
        val retries = (execution.awsRetryCount ?: defaultRetryCount).coerceAtLeast(0)
        val baseBackoffMs = (execution.awsRetryBackoffMs ?: defaultRetryBackoffMs).coerceAtLeast(0)

        val allArgs = mutableListOf(awsCommand)
        allArgs += args
        if (!region.isNullOrBlank()) {
            allArgs += listOf("--region", region)
        }

        val command = allArgs.joinToString(" ")
        if (dryRun) {
            val commandResult = AwsCommandResult(
                command = command,
                exitCode = 0,
                stdout = "dry-run",
                stderr = "",
                durationMs = 0,
                dryRun = true
            )
            return AwsActionExecution(
                command = commandResult,
                action = AwsActionResult(
                    ok = true,
                    action = action,
                    exitCode = 0,
                    durationMs = 0,
                    attempts = 1
                )
            )
        }

        var attempts = 0
        var totalDuration = 0L
        var latestProcess = AwsCommandProcessResult(
            exitCode = 127,
            stdout = "",
            stderr = "failed to execute aws command",
            durationMs = 0,
            timedOut = false
        )
        val warnings = mutableListOf<String>()

        while (attempts <= retries) {
            attempts += 1
            latestProcess = runAwsRaw(allArgs, workingDirectory, timeout)
            totalDuration += latestProcess.durationMs

            val ok = latestProcess.exitCode == 0 && !latestProcess.timedOut
            if (ok) {
                val commandResult = AwsCommandResult(
                    command = command,
                    exitCode = latestProcess.exitCode,
                    stdout = latestProcess.stdout,
                    stderr = latestProcess.stderr,
                    durationMs = totalDuration,
                    dryRun = false
                )
                return AwsActionExecution(
                    command = commandResult,
                    action = AwsActionResult(
                        ok = true,
                        action = action,
                        exitCode = 0,
                        durationMs = totalDuration,
                        attempts = attempts,
                        warnings = warnings
                    )
                )
            }

            val transient = isTransientFailure(latestProcess)
            val canRetry = transient && attempts <= retries
            if (canRetry) {
                val backoffMs = (baseBackoffMs * 2.0.pow((attempts - 1).toDouble())).toLong().coerceAtMost(30_000L)
                warnings += "Transient AWS failure on '$action' (attempt $attempts/${retries + 1}). Retrying in ${backoffMs}ms"
                Thread.sleep(backoffMs)
            } else {
                break
            }
        }

        val commandResult = AwsCommandResult(
            command = command,
            exitCode = latestProcess.exitCode,
            stdout = latestProcess.stdout,
            stderr = latestProcess.stderr,
            durationMs = totalDuration,
            dryRun = false
        )
        val errors = mutableListOf<String>()
        if (latestProcess.timedOut) {
            errors += "AWS command timed out after ${timeout}s"
        }
        val detail = latestProcess.stderr.ifBlank { latestProcess.stdout }
        if (detail.isNotBlank()) {
            errors += detail
        }

        return AwsActionExecution(
            command = commandResult,
            action = AwsActionResult(
                ok = false,
                action = action,
                exitCode = latestProcess.exitCode,
                durationMs = totalDuration,
                attempts = attempts,
                warnings = warnings,
                errors = errors,
                nextAction = suggestNextAction(action, latestProcess)
            )
        )
    }

    private fun resolveTimeoutSeconds(execution: AwsDeployExecutionOptions, timeoutKey: String): Long {
        val explicit = execution.operationTimeoutSeconds[timeoutKey]
        if (explicit != null && explicit > 0) return explicit

        val providerDefaultByOperation = when {
            timeoutKey.contains("cloudfront", ignoreCase = true) -> 900L
            timeoutKey.contains("s3", ignoreCase = true) -> 1800L
            timeoutKey.contains("lambda-wait", ignoreCase = true) -> 600L
            else -> execution.awsTimeoutSeconds
        }

        val resolved = providerDefaultByOperation ?: timeoutSeconds
        return resolved.coerceAtLeast(1)
    }

    private fun isTransientFailure(process: AwsCommandProcessResult): Boolean {
        if (process.timedOut) return true
        val text = "${process.stderr}\n${process.stdout}".lowercase()
        return listOf(
            "resourceconflictexception",
            "throttlingexception",
            "requesttimeout",
            "timed out",
            "connection reset",
            "connection refused",
            "temporarily unavailable",
            "too many requests"
        ).any { marker -> text.contains(marker) }
    }

    private fun suggestNextAction(action: String, process: AwsCommandProcessResult): String {
        if (process.timedOut) {
            return "Increase aws timeout for '$action' and retry"
        }

        val text = "${process.stderr}\n${process.stdout}".lowercase()
        return when {
            text.contains("resourceconflictexception") -> "Wait for Lambda state to settle and retry publish/alias"
            text.contains("throttlingexception") || text.contains("too many requests") -> "Increase retry/backoff and retry during lower API pressure"
            text.contains("requesttimeout") || text.contains("connection") -> "Check network stability and retry"
            else -> "Inspect AWS CLI stderr and verify permissions/resources"
        }
    }

    private fun runAwsRaw(args: List<String>, workingDirectory: File, timeoutSeconds: Long): AwsCommandProcessResult {
        commandRunner?.let { return it(args, workingDirectory, timeoutSeconds) }

        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder(args)
            .directory(workingDirectory)
            .start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return AwsCommandProcessResult(
                exitCode = 124,
                stdout = "",
                stderr = "timeout",
                durationMs = System.currentTimeMillis() - startedAt,
                timedOut = true
            )
        }

        return AwsCommandProcessResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            durationMs = System.currentTimeMillis() - startedAt,
            timedOut = false
        )
    }
}
