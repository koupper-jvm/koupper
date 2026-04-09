import com.koupper.container.app
import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import com.koupper.providers.aws.deploy.AwsApiSmokeEndpoint
import com.koupper.providers.aws.deploy.AwsApiSmokeTestRequest
import com.koupper.providers.aws.deploy.AwsDeployProvider
import com.koupper.providers.aws.deploy.AwsLambdaDeployRequest
import com.koupper.providers.aws.deploy.AwsPreflightRequest
import com.koupper.providers.aws.deploy.AwsStaticSiteDeployRequest
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.YmlFileHandler
import java.io.File
import java.util.concurrent.TimeUnit

data class Input(
    val env: String = "dev",
    val dryRun: Boolean = false,
    val only: String = "all",
    val skipSmoke: Boolean = false,
    val strictPreflight: Boolean = true,
    val configFile: String = "deploy.environments.yaml",
    val commandTimeoutSeconds: Long = 1800
)

data class LocalCommandResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val dryRun: Boolean
)

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

private fun asMap(value: Any?, label: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?> ?: error("$label is not a map")
}

private fun asList(value: Any?, label: String): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? List<Any?> ?: error("$label is not a list")
}

private fun resolvePath(base: File, path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(base, path).absoluteFile
}

private fun findConfig(cwd: File, fileName: String): File {
    val direct = resolvePath(cwd, fileName)
    if (direct.exists()) return direct

    var cursor: File? = cwd
    repeat(5) {
        cursor = cursor?.parentFile
        val candidate = cursor?.let { File(it, fileName) }
        if (candidate != null && candidate.exists()) return candidate
    }
    return direct
}

private fun moduleSelection(envNode: Map<String, Any?>, only: String): List<String> {
    val moduleOrder = envNode["moduleOrder"]
    val ordered = if (moduleOrder != null) {
        asList(moduleOrder, "moduleOrder").map { it?.toString().orEmpty() }.filter { it.isNotBlank() }
    } else {
        asMap(envNode["modules"], "modules").keys.toList()
    }

    if (only.trim().lowercase() == "all") return ordered

    val requested = only.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    val unknown = requested.filter { !ordered.contains(it) }
    if (unknown.isNotEmpty()) error("unknown module(s): ${unknown.joinToString(", ")}")

    return ordered.filter { requested.contains(it) }
}

private fun runLocalCommand(
    command: String,
    cwd: File,
    timeoutSeconds: Long,
    dryRun: Boolean
): LocalCommandResult {
    if (dryRun) {
        println("[dry-run] ${cwd.absolutePath}> $command")
        return LocalCommandResult(command, 0, "dry-run", dryRun = true)
    }

    val args = if (isWindows()) listOf("pwsh", "-NoProfile", "-Command", command) else listOf("bash", "-lc", command)
    val process = ProcessBuilder(args).directory(cwd).redirectErrorStream(true).start()

    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) {
        process.destroyForcibly()
        return LocalCommandResult(command, 124, "command timed out after ${timeoutSeconds}s", dryRun = false)
    }

    return LocalCommandResult(
        command = command,
        exitCode = process.exitValue(),
        output = process.inputStream.bufferedReader().readText().trim(),
        dryRun = false
    )
}

private fun ensureLocalOk(result: LocalCommandResult, step: String) {
    if (result.exitCode != 0) {
        error("$step failed (${result.command}): ${result.output}")
    }
}

private fun preflightResources(envNode: Map<String, Any?>, modules: List<String>): AwsPreflightRequest {
    val moduleMap = asMap(envNode["modules"], "modules")
    val lambdas = mutableSetOf<String>()
    val buckets = mutableSetOf<String>()
    val distributions = mutableSetOf<String>()
    val apis = mutableSetOf<String>()

    modules.forEach { moduleName ->
        val module = asMap(moduleMap[moduleName], "module '$moduleName'")
        if ((module["enabled"] as? Boolean) == false) return@forEach

        when (module["type"]?.toString()) {
            "backend_lambda" -> {
                asList(module["lambdas"], "lambdas").forEach { lambdas += it?.toString().orEmpty() }
            }
            "frontend_static" -> {
                module["bucket"]?.toString()?.takeIf { it.isNotBlank() }?.let { buckets += it }
                module["cloudfrontDistributionId"]?.toString()?.takeIf { it.isNotBlank() }?.let { distributions += it }
            }
        }
    }

    val smoke = envNode["smoke"] as? Map<*, *>
    val apisRaw = smoke?.get("apis") as? List<*>
    apisRaw?.forEach { apiItem ->
        val api = apiItem as? Map<*, *> ?: return@forEach
        val apiId = api["apiGatewayId"]?.toString().orEmpty()
        if (apiId.isNotBlank()) apis += apiId
    }

    return AwsPreflightRequest(
        region = envNode["region"]?.toString(),
        lambdas = lambdas.filter { it.isNotBlank() },
        buckets = buckets.filter { it.isNotBlank() },
        cloudFrontDistributions = distributions.filter { it.isNotBlank() },
        apiGatewayRestApis = apis.filter { it.isNotBlank() }
    )
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val configFile = findConfig(cwd, input.configFile)
    if (!configFile.exists()) error("config file not found: ${configFile.absolutePath}")

    val deploy = app.getInstance(AwsDeployProvider::class)
    val yml = app.getInstance(YmlFileHandler::class)
    val json: JSONFileHandler<*> = app.getInstance(JSONFileHandler::class)

    val root = asMap(yml.readFrom(configFile.absolutePath), "root config")
    val environments = asMap(root["environments"], "environments")
    val envNode = asMap(environments[input.env], "environment '${input.env}'")
    val modulesMap = asMap(envNode["modules"], "modules")
    val selectedModules = moduleSelection(envNode, input.only)
    val region = envNode["region"]?.toString() ?: "us-east-1"

    val preflightInput = preflightResources(envNode, selectedModules).copy(
        dryRun = input.dryRun,
        strict = input.strictPreflight
    )
    val preflight = deploy.preflight(preflightInput)

    val buildResults = mutableListOf<Map<String, Any?>>()
    val deployResults = mutableListOf<Map<String, Any?>>()

    selectedModules.forEach { moduleName ->
        val module = asMap(modulesMap[moduleName], "module '$moduleName'")
        if ((module["enabled"] as? Boolean) == false) {
            deployResults += mapOf("module" to moduleName, "skipped" to "disabled")
            return@forEach
        }

        val projectPath = resolvePath(configFile.parentFile, module["projectPath"]?.toString().orEmpty())
        if (!projectPath.exists()) error("project path not found for $moduleName: ${projectPath.absolutePath}")

        val buildCommand = asMap(module["buildCommand"], "buildCommand").let {
            if (isWindows()) it["windows"]?.toString() ?: "" else it["unix"]?.toString() ?: ""
        }
        if (buildCommand.isNotBlank()) {
            val build = runLocalCommand(buildCommand, projectPath, input.commandTimeoutSeconds, input.dryRun)
            ensureLocalOk(build, "$moduleName build")
            buildResults += mapOf(
                "module" to moduleName,
                "command" to build.command,
                "exitCode" to build.exitCode,
                "output" to build.output,
                "dryRun" to build.dryRun
            )
        }

        when (module["type"]?.toString()) {
            "backend_lambda" -> {
                val artifactPath = resolvePath(projectPath, module["artifactPath"]?.toString().orEmpty())
                val alias = module["alias"]?.toString()
                val lambdaResults = mutableListOf<Map<String, Any?>>()

                asList(module["lambdas"], "lambdas").forEach lambdas@{ lambdaItem ->
                    val lambda = lambdaItem?.toString().orEmpty()
                    if (lambda.isBlank()) return@lambdas

                    val deployed = deploy.deployLambda(
                        AwsLambdaDeployRequest(
                            functionName = lambda,
                            artifactPath = artifactPath.absolutePath,
                            region = region,
                            alias = alias,
                            dryRun = input.dryRun,
                            workingDirectory = cwd.absolutePath
                        )
                    )

                    lambdaResults += mapOf(
                        "functionName" to deployed.functionName,
                        "deployedVersion" to deployed.deployedVersion,
                        "alias" to deployed.alias,
                        "previousAliasVersion" to deployed.previousAliasVersion
                    )
                }

                deployResults += mapOf(
                    "module" to moduleName,
                    "type" to "backend_lambda",
                    "artifact" to artifactPath.absolutePath,
                    "lambdas" to lambdaResults
                )
            }

            "frontend_static" -> {
                val staticResult = deploy.deployStaticSite(
                    AwsStaticSiteDeployRequest(
                        bucket = module["bucket"]?.toString().orEmpty(),
                        distPath = resolvePath(projectPath, module["distPath"]?.toString().orEmpty()).absolutePath,
                        cloudFrontDistributionId = module["cloudfrontDistributionId"]?.toString().orEmpty(),
                        region = region,
                        releaseKeyPrefix = "releases/${System.currentTimeMillis()}/$moduleName",
                        dryRun = input.dryRun,
                        workingDirectory = cwd.absolutePath
                    )
                )

                deployResults += mapOf(
                    "module" to moduleName,
                    "type" to "frontend_static",
                    "bucket" to staticResult.bucket,
                    "backupPrefix" to staticResult.backupPrefix
                )
            }

            else -> error("unsupported module type for $moduleName: ${module["type"]}")
        }
    }

    val smokeNode = envNode["smoke"] as? Map<*, *>
    val smokeEnabled = (smokeNode?.get("enabled") as? Boolean) ?: true
    val smokeResult = if (input.skipSmoke || !smokeEnabled || smokeNode == null) {
        mapOf("ok" to true, "skipped" to "smoke disabled or skipped")
    } else {
        val endpoints = (smokeNode["apis"] as? List<*>)
            ?.mapNotNull { apiItem ->
                val api = apiItem as? Map<*, *> ?: return@mapNotNull null
                val method = api["method"]?.toString() ?: "GET"
                val expected = (api["expectedStatus"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() }
                    ?.toSet()
                    ?: setOf(200)

                val bodyRaw = api["body"]
                val body = when (bodyRaw) {
                    null -> null
                    is String -> bodyRaw
                    else -> json.mapToJsonString(bodyRaw)
                }

                AwsApiSmokeEndpoint(
                    name = api["name"]?.toString() ?: "unnamed",
                    method = method,
                    apiGatewayId = api["apiGatewayId"]?.toString(),
                    stage = api["stage"]?.toString(),
                    path = api["path"]?.toString() ?: "/",
                    baseUrl = api["baseUrl"]?.toString(),
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body,
                    expectedStatusCodes = expected
                )
            }
            ?: emptyList()

        val smoke = deploy.smokeTestApis(
            AwsApiSmokeTestRequest(
                endpoints = endpoints,
                region = region,
                timeoutSeconds = 60,
                dryRun = input.dryRun
            )
        )

        mapOf(
            "ok" to smoke.ok,
            "count" to smoke.results.size,
            "results" to smoke.results.map {
                mapOf(
                    "name" to it.name,
                    "statusCode" to it.statusCode,
                    "ok" to it.ok,
                    "url" to it.url,
                    "dryRun" to it.dryRun
                )
            }
        )
    }

    mapOf(
        "ok" to (preflight.ok && (smokeResult["ok"] as? Boolean ?: true)),
        "env" to input.env,
        "dryRun" to input.dryRun,
        "preflight" to mapOf(
            "ok" to preflight.ok,
            "checks" to preflight.checks.size,
            "warnings" to preflight.warnings
        ),
        "build" to buildResults,
        "deploy" to deployResults,
        "smoke" to smokeResult
    )
}
