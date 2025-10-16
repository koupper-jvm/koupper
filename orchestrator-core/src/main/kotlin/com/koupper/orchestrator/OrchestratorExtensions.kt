package com.koupper.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.logging.LogSpec
import com.koupper.logging.LoggerHolder
import com.koupper.logging.captureLogs
import com.koupper.logging.withScriptLogger
import com.koupper.orchestrator.config.JobConfig
import com.koupper.orchestrator.config.JobConfiguration
import com.koupper.os.env
import com.koupper.os.envOptional
import com.koupper.shared.octopus.readTextOrNull
import com.koupper.shared.octopus.sha256Of
import redis.clients.jedis.Jedis
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.*
import kotlin.reflect.KProperty0

private var enableDebugMode: Boolean = false

fun enableDebugMode() { enableDebugMode = true }

data class KouTask(
    val id: String,
    val fileName: String,
    val functionName: String,
    val params: Map<String, String>,
    val signature: Pair<List<String>, String>,
    val scriptPath: String?,
    val packageName: String?,
    val origin: String = "koupper",
    val context: String = "default",
    val sourceType: String = "compiled",
    val contextVersion: String,
    val sourceSnapshot: String? = null,
    val artifactUri: String? = null,
    val artifactSha256: String? = null
)

private fun sqsCredsProvider(config: JobConfiguration): AwsCredentialsProvider {
    val ak =config.sqsAccessKey
    val sk = config.sqsSecretKey

    return if (!ak.isNullOrBlank() && !sk.isNullOrBlank()) {
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ak, sk)
        )
    } else {
        DefaultCredentialsProvider.create()
    }
}

object JobSerializer {
    val mapper = jacksonObjectMapper()
    fun serialize(task: KouTask): String = mapper.writeValueAsString(task)
    fun deserialize(json: String): KouTask = mapper.readValue(json, KouTask::class.java)
}

object JobDispatcher {
    fun dispatch(task: KouTask, config: JobConfiguration) {
        when (config.driver) {
            "file" -> {
                val rootPath = if (task.context == "default") "" else  task.context + "/"

                val jobsDir = File("${rootPath}jobs/${config.queue}")

                if (!jobsDir.exists()) {
                    val created = jobsDir.mkdirs()
                    if (!created) {
                        throw IOException("Jobs folder can't be created: $jobsDir")
                    }
                }

                val jobFile = File(jobsDir, "${task.id}.json")
                jobFile.writeText(JobSerializer.serialize(task))
            }

            "database" -> {
                // insertar en una tabla jobs con el nombre del queue
            }
            "redis" -> {
                // encolar en lista Redis con nombre `queue`
            }
            "sqs" -> {
                val region   = Region.of(config.sqsRegion)
                val queueUrl = config.sqsQueueUrl

                val sqsClient = SqsClient.builder()
                    .region(region)
                    .credentialsProvider(sqsCredsProvider(config))
                    .build()

                try {
                    val sendRequest = SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(JobSerializer.serialize(task))
                        .build()

                    val result = sqsClient.sendMessage(sendRequest)
                    println("✅ Job sent to SQS [$queueUrl] with message ID: ${result.messageId()}")
                } catch (e: Exception) {
                    error("❌ Failed to dispatch job to SQS: ${e.message}")
                } finally {
                    sqsClient.close()
                }
            }

            else -> {
                error("Unknown driver: ${config.driver}")
            }
        }

        println("✅ Job dispatched with id/alias: [${config.id}]")
    }
}

object JobRunner {
    fun runPendingJobs(
        context: String,
        jobId: String? = null,
        configId: String? = null,
        onResult: (List<Any?>) -> Unit = {}
    ) {
        val configs = JobConfig.loadOrFail(context, configId)
        val allResults = mutableListOf<JobResult>()

        configs.configurations?.forEach { config ->
            if (config.ignoreOnProcessing) {
                return@forEach
            }

            val results = when (val driver = JobDrivers.resolve(config.driver)) {
                is ContextualJobDriver -> driver.forEachPending(context, config, jobId)
                else -> driver.forEachPending(config, jobId)
            }
            allResults += results
        }

        if (allResults.isEmpty()) {
            return onResult(listOf(JobResult.Error("⚠️ No jobs found")))
        }

        val result = allResults.map { res ->
            when (res) {
                is JobResult.Ok -> {
                    val task = res.task
                    val result = runCompiled(context, task)
                    JobInfo(
                        configId = res.configName,
                        id = res.task.id,
                        function = res.task.functionName,
                        params = res.task.params,
                        source = res.task.scriptPath,
                        context = res.task.context,
                        version = res.task.contextVersion,
                        origin = res.task.origin,
                        resultOfExecution = result
                    )
                }
                is JobResult.Error -> res
            }
        }

        onResult(result)
    }

    private fun buildLocalClassLoader(context: String, base: ClassLoader): ClassLoader {
        println("🔍 Buscando build local (classes/jar) en $context")

        val currentContext = File(context)
        val urls = mutableListOf(currentContext.toURI().toURL())

        val mainCodeDir = File("${currentContext.absolutePath}/build/classes/kotlin/main")
        val libFolder = File("${currentContext.absolutePath}/build/libs")

        var foundSomething = false

        if (mainCodeDir.exists() && mainCodeDir.isDirectory) {
            println("📦 Incluyendo código principal en classloader: ${mainCodeDir.absolutePath}")
            urls += mainCodeDir.toURI().toURL()
            foundSomething = true
        } else {
            println("⚠️ No se encontró build/classes/kotlin/main.")
        }

        if (libFolder.exists() && libFolder.isDirectory) {
            println("📦 Incluyendo jar(s) en classloader: ${libFolder.absolutePath}")

            libFolder.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
                urls += jar.toURI().toURL()
                println("   ➕ Agregado: ${jar.name}")
            }

            foundSomething = true
        } else {
            println("⚠️ No se encontró build/libs.")
        }

        fun fallbackLocalLoader(base: ClassLoader): ClassLoader {
            val urls = mutableListOf<java.net.URL>()
            val projectRoot = Paths.get("").toAbsolutePath().toFile()

            val classesDir = File(context, "build/classes/kotlin/main")
            if (classesDir.exists() && classesDir.isDirectory) {
                println("📦 Usando clases locales: ${classesDir.absolutePath}")
                urls += classesDir.toURI().toURL()
            }

            val libsDir = File(projectRoot, "libs")
            if (libsDir.exists() && libsDir.isDirectory) {
                val latestJar = libsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                    ?.maxByOrNull { it.lastModified() }
                if (latestJar != null) {
                    println("📦 Usando jar local: ${latestJar.absolutePath}")
                    urls += latestJar.toURI().toURL()
                }
            }

            val buildLibsDir = File(projectRoot, "build/libs")
            if (buildLibsDir.exists() && buildLibsDir.isDirectory) {
                val latestJar = buildLibsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                    ?.maxByOrNull { it.lastModified() }
                if (latestJar != null) {
                    println("📦 Usando jar local: ${latestJar.absolutePath}")
                    urls += latestJar.toURI().toURL()
                }
            }
            return if (urls.isNotEmpty()) URLClassLoader(urls.toTypedArray(), base) else base
        }

        return if (foundSomething) {
            println("✅ ClassLoader armado con ${urls.size} URLs.")
            URLClassLoader(urls.toTypedArray(), base)
        } else {
            println("⚠️ No se encontró ningún directorio válido, usando fallback.")
            fallbackLocalLoader(base)
        }
    }

    private fun runCompiled(context: String, task: KouTask): Any? {
        try {
            println("\n🔍 Buscando clase para ejecutar: ${task.functionName}")

            val base = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

            val loader: ClassLoader = buildLocalClassLoader(context, base)

            (loader as? URLClassLoader)?.let { cl ->
                val urls = cl.urLs ?: cl.urLs
                println("🧭 Loader URLs:"); urls.forEach { println(" - $it") }
            } ?: println("🧭 Loader: ${loader.javaClass.name}")

            val pkg = task.packageName?.trimEnd('.')
            val basePrefix = if (pkg.isNullOrBlank()) "" else "$pkg."
            val fileBase = task.fileName
                .removeSuffix(".class")
                .removeSuffix(".kt")
                .removeSuffix("Kt")

            val candidates = linkedSetOf(
                "${basePrefix}${fileBase}Kt",
                "${basePrefix}${fileBase}"
            )
            if (task.fileName != fileBase) {
                candidates += "${basePrefix}${task.fileName.removeSuffix(".class")}"
            }

            val clazz = candidates.asSequence()
                .mapNotNull { fqcn -> try { loader.loadClass(fqcn) } catch (_: ClassNotFoundException) { null } }
                .firstOrNull() ?: error("No se encontró clase para ${task.functionName}. Probé: $candidates")

            println("🧪 Campos disponibles en ${clazz.name}:")
            clazz.declaredFields.forEach { println(" - ${it.name}") }

            val field = try {
                clazz.getDeclaredField(task.functionName)
            } catch (_: NoSuchFieldException) {
                error("No existe el field '${task.functionName}' en ${clazz.name}. Campos: ${clazz.declaredFields.joinToString { it.name }}")
            }
            field.isAccessible = true
            val functionRef = field.get(null)

            val value = when (functionRef) {
                is kotlin.reflect.KProperty0<*> -> functionRef.get()
                else -> functionRef
            } ?: error("Referencia nula para '${task.functionName}'")

            val kotlinToJava = mapOf(
                "kotlin.String" to String::class.java,
                "kotlin.Int" to Integer::class.java,
                "kotlin.Long" to java.lang.Long::class.java,
                "kotlin.Boolean" to java.lang.Boolean::class.java,
                "kotlin.Float" to java.lang.Float::class.java,
                "kotlin.Double" to java.lang.Double::class.java,
                "kotlin.collections.List" to List::class.java,
                "kotlin.collections.Map" to Map::class.java
            )

            fun argIndex(k: String) = k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE

            val args: List<Any?> = task.params.entries
                .sortedBy { argIndex(it.key) }
                .mapIndexed { index, entry ->
                    val typeName = task.signature?.first?.get(index)
                        ?: error("Missing type for arg$index")
                    val rawType = typeName.removeSuffix("?")
                    val paramClass = kotlinToJava[rawType] ?: Class.forName(rawType, true, loader)

                    val raw = entry.value
                    val jsonForJackson =
                        if (raw.length >= 2 && raw[0] == '"' && (raw.getOrNull(1) == '{' || raw.getOrNull(1) == '['))
                            JobSerializer.mapper.readValue(raw, String::class.java)
                        else raw

                    JobSerializer.mapper.readValue(jsonForJackson, paramClass)
                }

            val oldCl = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = value::class.java.classLoader
            return try {
                val lambdaClass = value::class.java
                val invoke = (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
                    .firstOrNull { it.name == "invoke" && it.parameterCount == args.size }
                    ?: (lambdaClass.methods.asSequence() + lambdaClass.declaredMethods.asSequence())
                        .firstOrNull {
                            it.name == "invoke" &&
                                    it.parameterCount == args.size + 1 &&
                                    it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
                        }?.also {
                            error("La función '${task.functionName}' es suspend (Continuation no soportado).")
                        }
                    ?: error("❌ No se encontró método 'invoke' con aridad ${args.size} en ${lambdaClass.name}")

                try { invoke.isAccessible = true } catch (_: Exception) {
                    try {
                        val ao = Class.forName("java.lang.reflect.AccessibleObject")
                        val m  = ao.getMethod("trySetAccessible")
                        m.invoke(invoke)
                    } catch (_: Throwable) { /* ignore */ }
                }

                val result = invoke.invoke(value, *args.toTypedArray())
                println("✅ Job result: $result")
                result
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
            }
        } catch (e: Exception) {
            println("❌ Error ejecutando job compilado: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}

sealed class JobResult {
    data class Ok(val configName: String?, val task: KouTask) : JobResult()
    data class Error(val message: String, val exception: Exception? = null) : JobResult()
}

data class JobInfo(
    val configId: String? = "",
    val id: String,
    val function: String,
    val params: Map<String, Any?>,
    val source: String?,
    val context: String?,
    val version: String?,
    val origin: String?,
    val resultOfExecution: Any? = null
)

object JobLister {
    fun list(
        context: String,
        jobId: String? = null,
        configId: String? = null,
        onResult: (List<Any>) -> Unit = {}
    ) {
        val jobConfiguration = JobConfig.loadOrFail(context, configId)
        val results = mutableListOf<JobResult>()

        jobConfiguration.configurations?.forEach { config ->
            val driver = JobDrivers.resolve(config.driver)
            results += driver.listPending(context, config, jobId)
        }

        if (results.isEmpty()) {
            return onResult(listOf(JobResult.Error("⚠️ No jobs found")))
        }

        val infos = results.map {
            when (it) {
                is JobResult.Ok -> JobInfo(
                    configId = it.configName,
                    id = it.task.id,
                    function = it.task.functionName,
                    params = it.task.params,
                    source = it.task.scriptPath,
                    context = it.task.context,
                    version = it.task.contextVersion,
                    origin = it.task.origin
                )
                is JobResult.Error -> it
            }
        }

        onResult(infos)
    }
}

object JobBuilder {
    fun build(queue: String, driver: String) {
        println("🔨 Building worker for queue: $queue with driver: $driver")

        val result = Runtime.getRuntime().exec("gradle shadowJar").waitFor()
        if (result == 0) {
            println("✅ Worker built successfully.")
        } else {
            println("❌ Failed to build worker.")
        }
    }
}

data class JobMetrics(
    val pending: Int,
    val inFlight: Int? = null // útil en SQS
)

object JobMetricsCollector {
    private lateinit var context: String

    fun collect(context: String, config: JobConfiguration): JobMetrics {
        this.context = context

        return when (config.driver) {
            "file" -> collectFile(context, config)
            "redis" -> collectRedis(config)
            "sqs" -> collectSqs(config)
            "database" -> collectDb(config)
            else -> JobMetrics(0, 0)
        }
    }

    private fun collectFile(context: String, config: JobConfiguration): JobMetrics {
        val base = File("${context}/jobs${File.separator}${config.queue}")
        val pending = base.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.size ?: 0
        val failedDir = File(base, ".failed")
        val failed = failedDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.size ?: 0
        return JobMetrics(pending, failed)
    }

    private fun collectRedis(config: JobConfiguration): JobMetrics {
        Jedis("localhost", 6379).use { jedis ->
            val pending = jedis.llen(config.queue).toInt()
            val failed = jedis.llen("${config.queue}:failed").toInt()
            return JobMetrics(pending, failed)
        }
    }

    private fun collectSqs(config: JobConfiguration): JobMetrics {
        val region = Region.of(config.sqsRegion)
        val sqs = SqsClient.builder()
            .region(region)
            .credentialsProvider(sqsCredsProvider(config))
            .build()

        sqs.use {
            val mainUrl = config.sqsQueueUrl

            val attrs: MutableMap<QueueAttributeName, String> =
                it.getQueueAttributes { b ->
                    b.queueUrl(mainUrl).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                    )
                }.attributes()

            val pending  = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            val inFlight = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0

            return JobMetrics(pending, inFlight)
        }
    }

    private fun collectDb(config: JobConfiguration): JobMetrics {
        DriverManager.getConnection("jdbc:sqlite:jobs.db").use { conn ->
            fun count(sql: String): Int =
                conn.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            val pending = count("SELECT COUNT(1) FROM jobs WHERE queue='${config.queue}' AND status='pending'")
            val failed  = count("SELECT COUNT(1) FROM jobs WHERE queue='${config.queue}' AND status='failed'")
            return JobMetrics(pending, failed)
        }
    }
}

private object FileJobFS {
    fun queueDir(queue: String) = File("jobs/$queue")

    fun failedDir(queue: String) = File(queueDir(queue), ".failed").apply { mkdirs() }

    fun pendingFiles(queue: String): List<File> =
        queueDir(queue).listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name } ?: emptyList()

    fun failedFiles(queue: String): List<File> =
        failedDir(queue).listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name } ?: emptyList()

    fun moveToFailed(queue: String, file: File) {
        val target = File(failedDir(queue), file.name)
        file.copyTo(target, overwrite = true)
        file.delete()
    }

    fun retry(queue: String, jobId: String): Boolean {
        val src = File(failedDir(queue), if (jobId.endsWith(".json", true)) jobId else "$jobId.json")
        if (!src.exists()) return false
        val dst = File(queueDir(queue), src.name)
        src.copyTo(dst, overwrite = true)
        src.delete()
        return true
    }
}

object JobFailed {
    fun list(queue: String = "default") {
        val failed = FileJobFS.failedFiles(queue)
        if (failed.isEmpty()) {
            println("✅ No failed jobs in [$queue]")
            return
        }
        println("❌ Failed jobs in [$queue]: ${failed.size}")
        failed.forEach { f ->
            try {
                val task = JobSerializer.deserialize(f.readText())
                println(" - ${f.nameWithoutExtension} :: id=${task.id} fn=${task.functionName} ctx=${task.context} ver=${task.contextVersion}")
            } catch (e: Exception) {
                println(" - ${f.nameWithoutExtension} :: (unreadable) ${e.message}")
            }
        }
    }
}

object JobRetry {
    fun retry(queue: String = "default", jobId: String) {
        require(jobId.isNotBlank()) { "jobId is required" }
        val ok = FileJobFS.retry(queue, jobId)
        if (ok) println("🔄 Retried job [$jobId] in queue [$queue] (moved back to pending).")
        else println("⚠️ Failed job not found: [$jobId] in [$queue/.failed]")
    }
}

object JobDisplayer {
    fun showStatus(context: String, config: JobConfiguration, jobId: String?) {
        val m = JobMetricsCollector.collect(context, config)
0
        when (config.driver) {
            "sqs" -> {
                println("\n   📊 STATUS [${config.driver}]: pending=${m.pending}, inFlight=${m.inFlight ?: 0}")
            }
            "file" -> {
                println("\n   📊 STATUS [${config.driver}]: Pending: ${m.pending}")
            }
            else  -> println("\n📊 STATUS [${config.driver}]: pending=${m.pending}")
        }
    }
}

interface JobDriver {
    fun forEachPending(config: JobConfiguration, jobId: String?): List<JobResult>
    fun listPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult>
}

interface ContextualJobDriver : JobDriver {
    fun forEachPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult>
}

object FileJobDriver : ContextualJobDriver {
    override fun forEachPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()
        val dir = File("$context${File.separator}jobs/${config.queue}")

        val files = when {
            !jobId.isNullOrBlank() -> {
                val target = if (jobId.endsWith(".json", true)) jobId else "$jobId.json"
                dir.listFiles { f -> f.isFile && f.name.equals(target, true) }?.toList().orEmpty()
            }
            else -> dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }
                ?.sortedBy { it.name }
                .orEmpty()
        }

        if (files.isEmpty()) {
            results.add(JobResult.Error("⚠️ No jobs found in [$context]"))
            return results
        }

        files.forEach { file ->
            try {
                val task = JobSerializer.deserialize(file.readText())
                if (!file.delete()) {
                    results.add(JobResult.Error("⚠️ Could not delete processed job file: ${file.name}"))
                }
                results.add(JobResult.Ok(config.id, task))
            } catch (e: Exception) {
                results.add(JobResult.Error("❌ Failed to execute job from file '${file.name}': ${e.message}", e))
                FileJobFS.moveToFailed(config.queue!!, file)
            }
        }

        return results
    }

    override fun forEachPending(config: JobConfiguration, jobId: String?): List<JobResult> {
        TODO("Not yet implemented")
    }

    override fun listPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult> {
        val dir = File("$context${File.separator}jobs/${config.queue}")
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("json", true) } ?: return emptyList()

        if (files.isEmpty()) {
            return listOf(JobResult.Error("⚠️ No File messages found in [${dir.absoluteFile}]"))
        }

        return files.mapNotNull { file ->
            try {
                val task = JobSerializer.deserialize(file.readText())
                JobResult.Ok(config.id, task)
            } catch (e: Exception) {
                JobResult.Error("❌ Failed to read job '${file.name}': ${e.message}", e)
            }
        }
    }
}

object RedisJobDriver : JobDriver {

    override fun forEachPending(config: JobConfiguration, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()

        val host = config.redisHost ?: "127.0.0.1"
        val port = config.redisPort?.toIntOrNull() ?: 6379
        val password = config.redisPassword

        try {
            Jedis(host, port).use { jedis ->
                if (!password.isNullOrBlank()) jedis.auth(password)

                while (true) {
                    val jobJson = jedis.lpop(config.queue) ?: break

                    try {
                        val task = JobSerializer.deserialize(jobJson)

                        if (!jobId.isNullOrBlank() && task.id != jobId) {
                            jedis.rpush(config.queue, jobJson)
                            continue
                        }
                        results.add(JobResult.Ok(config.id, task))
                    } catch (e: Exception) {
                        results.add(JobResult.Error("❌ Failed Redis job: ${e.message}", e))
                        // Opcional: enviar a Dead Letter Queue
                        jedis.rpush("${config.queue}:failed", jobJson)
                    }
                }
            }

        } catch (e: Exception) {
            results.add(JobResult.Error("❌ Redis connection error: ${e.message}", e))
            return results
        }

        if (results.isEmpty()) {
            results.add(JobResult.Error("⚠️ No Redis jobs found in [${config.queue}]"))
        }

        return results
    }

    override fun listPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult> {
        TODO("Not yet implemented")
    }
}

object SqsJobDriver : JobDriver {
    override fun forEachPending(config: JobConfiguration, jobId: String?): List<JobResult> {
        val region = Region.of(config.sqsRegion)
        val results = mutableListOf<JobResult>()

        val sqsClient = SqsClient.builder()
            .region(region)
            .credentialsProvider(sqsCredsProvider(config))
            .build()

        sqsClient.use {
            val queueUrl = config.sqsQueueUrl
            while (true) {
                val msgs = it.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .messageAttributeNames("All")
                        .build()
                ).messages()

                if (msgs.isEmpty()) {
                    if (results.isEmpty()) {
                        results.add(JobResult.Error("⚠️ No SQS messages found in [${config.id}] configuration."))
                    }
                    break
                }

                msgs.forEach { msg ->
                    try {
                        val task = JobSerializer.deserialize(msg.body())
                        results.add(JobResult.Ok(config.id, task))

                        it.deleteMessage(
                            DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(msg.receiptHandle())
                                .build()
                        )
                    } catch (e: Exception) {
                        results.add(JobResult.Error("❌ Failed SQS job: ${e.message}", e))
                    }
                }

                Thread.sleep(200)
            }
        }

        return results
    }

    override fun listPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()
        val region = Region.of(config.sqsRegion)

        val sqs = SqsClient.builder()
            .region(region)
            .credentialsProvider(sqsCredsProvider(config))
            .build()

        sqs.use {
            val queueUrl = config.sqsQueueUrl
            val attrs = it.getQueueAttributes { b ->
                b.queueUrl(queueUrl)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                    )
            }.attributes()

            val pendingCount = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            val inFlightCount = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0

            if (pendingCount == 0 && inFlightCount == 0) {
                results += JobResult.Error("⚠️ No SQS messages found in [${config.id}] configuration.")
            } else {
                val pseudoTask = KouTask(
                    id = UUID.randomUUID().toString(),
                    fileName = "SqsQueueStatus",
                    functionName = "listPending",
                    params = mapOf(
                        "pending" to pendingCount.toString(),
                        "inFlight" to inFlightCount.toString()
                    ),
                    signature = Pair(emptyList(), "Unit"),
                    scriptPath = null,
                    packageName = null,
                    contextVersion = System.currentTimeMillis().toString()
                )
                results += JobResult.Ok(config.id, pseudoTask)
            }
        }

        return results
    }
}

object DbJobDriver : JobDriver {

    override fun forEachPending(config: JobConfiguration, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()

        val dbUrl  = config.databaseUrl ?: "jdbc:sqlite:jobs.db"
        val dbUser = config.databaseUser
        val dbPass = config.databasePassword

        val conn = try {
            if (dbUser != null && dbPass != null)
                DriverManager.getConnection(dbUrl, dbUser, dbPass)
            else
                DriverManager.getConnection(dbUrl)
        } catch (e: Exception) {
            results.add(JobResult.Error("❌ Cannot connect to database: ${e.message}", e))
            return results
        }

        val sql = if (!jobId.isNullOrBlank())
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending' AND id = ?"
        else
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending'"

        conn.use { c ->
            c.prepareStatement(sql).use { stmt ->
                stmt.setString(1, config.queue)
                if (!jobId.isNullOrBlank()) stmt.setString(2, jobId)

                val rs = stmt.executeQuery()
                var found = false

                while (rs.next()) {
                    found = true
                    val jobIdDb = rs.getString("id")
                    val jobJson = rs.getString("payload")

                    try {
                        val task = JobSerializer.deserialize(jobJson)
                        results.add(JobResult.Ok(config.id,task))

                        // ✅ marcar como 'done'
                        c.prepareStatement("UPDATE jobs SET status = 'done' WHERE id = ?").use { upd ->
                            upd.setString(1, jobIdDb)
                            upd.executeUpdate()
                        }

                    } catch (e: Exception) {
                        results.add(JobResult.Error("❌ Failed DB job [$jobIdDb]: ${e.message}", e))

                        // ❌ marcar como 'failed'
                        c.prepareStatement("UPDATE jobs SET status = 'failed' WHERE id = ?").use { upd ->
                            upd.setString(1, jobIdDb)
                            upd.executeUpdate()
                        }
                    }
                }

                if (!found) {
                    results.add(JobResult.Error("⚠️ No pending DB jobs found in queue [${config.queue}]."))
                }
            }
        }

        return results
    }

    override fun listPending(context: String, config: JobConfiguration, jobId: String?): List<JobResult> {
        TODO("Not yet implemented")
    }
}

object JobDrivers {
    fun resolve(driver: String): JobDriver = when (driver) {
        "file" -> FileJobDriver
        "redis" -> RedisJobDriver
        "sqs" -> SqsJobDriver
        "database" -> DbJobDriver
        else -> error("Unknown driver: $driver")
    }
}

object JobReplayer {
    fun replayJobsListenerScript(
        context: String,
        config: JobConfiguration,
        newParams: Map<String, Any?>,
        injector: (String) -> Any? = { null },
        logSpec: LogSpec? = null,
        symbol: Any? = null,
        onResult: (KouTask) -> Unit = {}
    ): List<JobResult> {
        LoggerHolder.initLogger(context)

        val configs = config.configurations ?: listOf(config)
        val allResults = mutableListOf<JobResult>()

        for (cfg in configs) {
            val driver = cfg.driver
            val d = JobDrivers.resolve(driver)

            LoggerHolder.LOGGER.info {
                "\n🔁 Replaying jobs using [$driver]\n"
            }

            val results = when (d) {
                is ContextualJobDriver -> d.forEachPending(config, jobId = null)
                else -> d.forEachPending(jobId = null, config = cfg)
            }

            results.forEach { res ->
                when (res) {
                    is JobResult.Ok -> {
                        val updated = res.task.copy(
                            params = newParams.mapValues { JobSerializer.mapper.writeValueAsString(it.value) }
                        )

                        if (logSpec != null) {
                            captureLogs<Any?>("Scripts.Dispatcher", logSpec) { logger ->
                                withScriptLogger(logger, logSpec.mdc) {
                                    val result = ScriptRunner.runScript(updated, symbol, injector)
                                    logger.info { result.toString() }
                                }
                            }
                        } else {
                            ScriptRunner.runScript(updated, symbol, injector)
                        }

                        onResult(updated)
                    }

                    is JobResult.Error -> {
                        LoggerHolder.LOGGER.warn { res.message }
                        res.exception?.printStackTrace()
                    }
                }
            }

            allResults += results
        }

        return allResults
    }
}

fun extractSignature(typeStr: String): Pair<List<String>, String> {
    val parts = typeStr.split("->").map { it.trim() }
    if (parts.size != 2) {
        error("Tipo inválido: $typeStr")
    }

    val argsPart = parts[0]
    val returnPart = parts[1]

    val args = argsPart.removePrefix("(").removeSuffix(")")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return args to returnPart
}

fun KProperty0<*>.asJob(vararg args: Any?): KouTask {
    val ref = this.get() ?: error("Function reference is null")

    val codeSource = ref.javaClass.protectionDomain?.codeSource?.location?.toURI()
    val scriptPath = codeSource?.path
    val file = codeSource?.let { File(it) }

    val packageName = ref.javaClass.`package`?.name ?: "unknown"
    val functionName = this.name
    val fullClassName = ref.javaClass.name
    val fileName = fullClassName.substringBefore("$$").substringAfterLast('.')
    val signature: Pair<List<String>, String> = extractSignature(this.returnType.toString())

    val params = args.mapIndexed { index, arg ->
        "arg$index" to JobSerializer.mapper.writeValueAsString(arg)
    }.toMap()

    val sourceType = when {
        scriptPath?.endsWith(".kts") == true -> "script"
        scriptPath?.endsWith(".jar") == true -> "jar"
        else -> "compiled"
    }

    val contextVersion = file?.lastModified()?.toString() ?: "unknown"

    val sourceSnapshot = if (sourceType == "script") readTextOrNull(scriptPath) else null
    val artifactSha256 = if (sourceType != "script" && file?.isFile == true) sha256Of(file) else null
    val artifactUri: String? = null

    return KouTask(
        id = UUID.randomUUID().toString(),
        fileName = fileName,
        functionName = functionName,
        params = params,
        signature = signature,
        scriptPath = scriptPath,
        packageName = packageName,
        sourceType = sourceType,
        contextVersion = contextVersion,
        sourceSnapshot = sourceSnapshot,
        artifactUri = artifactUri,
        artifactSha256 = artifactSha256
    )
}

inline fun <reified T, reified R> ((T) -> R).asJob(
    vararg args: Any?,
    functionName: String,
    scriptPath: String,
    packageName: String? = null,
    sourceType: String,
    artifactUri: String? = null
): KouTask {
    val params = args.mapIndexed { index, arg ->
        "arg$index" to JobSerializer.mapper.writeValueAsString(arg)
    }.toMap()

    val signature = Pair(
        listOf(T::class.qualifiedName ?: "kotlin.Any"),
        R::class.qualifiedName ?: "kotlin.Any"
    )

    val sourceSnapshot: String? =
        if (sourceType == "script") readTextOrNull(scriptPath) else null

    val contextVersion = "unknown"

    return KouTask(
        id = UUID.randomUUID().toString(),
        fileName = File(scriptPath).name,
        functionName = functionName,
        params = params,
        signature = signature,
        scriptPath = scriptPath,
        packageName = packageName,
        sourceType = sourceType,
        contextVersion = contextVersion,
        sourceSnapshot = sourceSnapshot,
        artifactUri = artifactUri,
    )
}

fun KouTask.dispatchToQueue(configId: String? = null) {
    val currentContext = File("${Paths.get("").toAbsolutePath()}")

    val config = JobConfig.loadOrFail(currentContext.absolutePath, configId)

    if (config.configurations.isNullOrEmpty()) {
        throw IllegalStateException("❌ No job configurations found in context: ${currentContext.absolutePath}")
    }

    var hasGlobalConfig = false

    config.configurations.forEach {
        if (it.forAllProjects) {
            hasGlobalConfig = true
            JobDispatcher.dispatch(this, it)
        }
    }

    if (hasGlobalConfig) {
        return
    }

    if (config.configurations.size > 1 && configId.isNullOrEmpty()) {
        throw IllegalArgumentException(
            "⚠️ Multiple job configurations detected. " +
                    "You must specify a configId to dispatch this task correctly or set a for-all-projects property for a preferred configuration."
        )
    } else {
        JobDispatcher.dispatch(this, config.configurations.first())
    }
}

