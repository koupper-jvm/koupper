package com.koupper.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.logging.*
import com.koupper.shared.runtime.ScriptBackend
import com.koupper.orchestrator.config.JobConfig
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
    val signature: Pair<List<String>, String>?,
    val scriptPath: String?,
    val packageName: String?,
    val origin: String = "koupper",
    val context: String = "default",
    val sourceType: String = "compiled",
    val queue: String = "default",
    val driver: String = "default",
    val contextVersion: String,
    val sourceSnapshot: String? = null,
    val artifactUri: String? = null,
    val artifactSha256: String? = null
)

private fun sqsCredsProvider(): AwsCredentialsProvider {
    val ak = envOptional("KQ_SQS_ACCESS_KEY")
    val sk = envOptional("KQ_SQS_SECRET_KEY")

    return if (!ak.isNullOrBlank() && !sk.isNullOrBlank()) {
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ak, sk)
        )
    } else {
        DefaultCredentialsProvider.create()
    }
}

object LoggerHolder {
    lateinit var LOGGER: KLogger

    init {
        if (GlobalLogger.log.name.equals("GlobalLogger")) {
            val logSpec = LogSpec(
                level = "INFO",
                destination = "console",
                mdc = mapOf(
                    "LOGGING_LOGS" to UUID.randomUUID().toString(),
                ),
                async = true
            )

            captureLogs("JobsOrchestrator.Dispatcher", logSpec) { log ->
                LOGGER = log
                "initialized"
            }
        } else {
            LOGGER = GlobalLogger.log
        }
    }
}

object JobSerializer {
    val mapper = jacksonObjectMapper()
    fun serialize(task: KouTask): String = mapper.writeValueAsString(task)
    fun deserialize(json: String): KouTask = mapper.readValue(json, KouTask::class.java)
}

object JobDispatcher {
    fun dispatch(task: KouTask, queue: String = "default", driver: String = "file") {
        when (driver) {
            "file" -> {
                val baseDir = Paths.get("").toAbsolutePath().toString()
                val jobsDir = File("$baseDir/jobs/$queue")

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
                val region   = Region.of(env("KQ_SQS_REGION"))
                val queueUrl = env("KQ_SQS_QUEUE_URL")

                val sqsClient = SqsClient.builder()
                    .region(region)
                    .credentialsProvider(sqsCredsProvider())
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
                error("Unknown driver: $driver")
            }
        }

        println("✅ Job dispatched to queue [$queue] with driver [$driver]")
    }
}

object JobRunner {
    fun runPendingJobs(
        queue: String = "default",
        driver: String = "file",
        jobId: String? = null,
        onResult: (KouTask) -> Unit = {}
    ): List<JobResult> {
        val d = JobDrivers.resolve(driver)

        println("\n🔧 Running jobs from [$queue] using [$driver]${if (jobId != null) " (jobId=$jobId)" else ""}")

        val results = d.forEachPending(queue, jobId)

        results.forEach { res ->
            when (res) {
                is JobResult.Ok -> {
                    val task = res.task
                    runCompiled(task)
                    onResult(task)
                }
                is JobResult.Error -> {
                    println(res.message)
                    res.exception?.printStackTrace()
                }
            }
        }

        return results
    }

    private fun runCompiled(task: KouTask): Any? {
        try {
            println("\n🔍 Buscando clase para ejecutar: ${task.functionName}")

            fun fallbackLocalLoader(base: ClassLoader): ClassLoader {
                val urls = mutableListOf<java.net.URL>()
                val projectRoot = Paths.get("").toAbsolutePath().toFile()

                val classesDir = File(projectRoot, "build/classes/kotlin/main")
                if (classesDir.exists() && classesDir.isDirectory) {
                    println("📦 Usando clases locales: ${classesDir.absolutePath}")
                    urls += classesDir.toURI().toURL()
                }

                val libsDir = File(projectRoot, "build/libs")
                if (libsDir.exists() && libsDir.isDirectory) {
                    val latestJar = libsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                        ?.maxByOrNull { it.lastModified() }
                    if (latestJar != null) {
                        println("📦 Usando jar local: ${latestJar.absolutePath}")
                        urls += latestJar.toURI().toURL()
                    }
                }
                return if (urls.isNotEmpty()) URLClassLoader(urls.toTypedArray(), base) else base
            }

            val base = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
            val loader: ClassLoader = when {
                !task.scriptPath.isNullOrBlank() && task.scriptPath!!.endsWith(".jar") -> {
                    val jar = File(task.scriptPath!!)
                    require(jar.exists()) { "❌ No existe jar: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(jar.toURI().toURL()), base)
                }
                !task.scriptPath.isNullOrBlank() &&
                        (task.scriptPath!!.contains("/kotlin/main") || task.scriptPath!!.contains("\\kotlin\\main")) -> {
                    val dir = File(task.scriptPath!!)
                    require(dir.exists() && dir.isDirectory) { "❌ Ruta inválida: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(dir.toURI().toURL()), base)
                }
                else -> {
                    println("🔍 scriptPath ausente/no usable; buscando build local (classes/jar)")
                    fallbackLocalLoader(base)
                }
            }

            (loader as? URLClassLoader)?.let { cl ->
                val urls = cl.urLs ?: cl.getURLs()
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
    data class Ok(val task: KouTask) : JobResult()
    data class Error(val message: String, val exception: Exception? = null) : JobResult()
}

data class JobInfo(
    val id: String,
    val function: String,
    val params: Map<String, Any?>,
    val source: String?,
    val context: String?,
    val version: String?,
    val origin: String?
)

object JobLister {
    fun list(queue: String = "default", driver: String = "file", jobId: String? = null): List<Any> {
        val d = JobDrivers.resolve(driver)
        val results = d.forEachPending(queue, jobId)

        return if (results.isEmpty()) {
            listOf(JobResult.Error("⚠️ No jobs found"))
        } else {
            results.map { res ->
                when (res) {
                    is JobResult.Ok -> {
                        val t = res.task
                        JobInfo(
                            id = t.id,
                            function = t.functionName,
                            params = t.params,
                            source = t.scriptPath,
                            context = t.context,
                            version = t.contextVersion,
                            origin = t.origin
                        )
                    }
                    is JobResult.Error -> res
                }
            }
        }
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
    val failed: Int,
    val inFlight: Int? = null // útil en SQS
)

object JobMetricsCollector {
    private val KEY_PENDING =
        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES.toString()
    private val KEY_NOT_VISIBLE =
        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE.toString()

    fun collect(queue: String, driver: String): JobMetrics = when (driver) {
        "file" -> collectFile(queue)
        "redis" -> collectRedis(queue)
        "sqs" -> collectSqs()
        "database" -> collectDb(queue)
        else -> JobMetrics(0, 0)
    }

    private fun collectFile(queue: String): JobMetrics {
        val base = File("jobs/$queue")
        val pending = base.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.size ?: 0
        val failedDir = File(base, ".failed")
        val failed = failedDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.size ?: 0
        return JobMetrics(pending, failed)
    }

    private fun collectRedis(queue: String): JobMetrics {
        Jedis("localhost", 6379).use { jedis ->
            val pending = jedis.llen(queue).toInt()
            val failed = jedis.llen("$queue:failed").toInt()
            return JobMetrics(pending, failed)
        }
    }

    private fun collectSqs(): JobMetrics {
        val region = Region.of(env("KQ_SQS_REGION"))
        val sqs = SqsClient.builder()
            .region(region)
            .credentialsProvider(sqsCredsProvider())
            .build()

        sqs.use {
            val mainUrl = env("KQ_SQS_QUEUE_URL")

            val attrs: MutableMap<QueueAttributeName, String> =
                it.getQueueAttributes { b ->
                    b.queueUrl(mainUrl).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                    )
                }.attributes()

            val pending  = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            val inFlight = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0
            var failed   = 0

            val dlqUrl = env("KQ_SQS_DLQ_URL")
            if (dlqUrl.isNotBlank()) {
                val dlqAttrs: MutableMap<QueueAttributeName, String> =
                    it.getQueueAttributes { b ->
                        b.queueUrl(dlqUrl).attributeNames(
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES
                        )
                    }.attributes()

                failed = dlqAttrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            }

            return JobMetrics(pending, failed, inFlight)
        }
    }

    private fun collectDb(queue: String): JobMetrics {
        DriverManager.getConnection("jdbc:sqlite:jobs.db").use { conn ->
            fun count(sql: String): Int =
                conn.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            val pending = count("SELECT COUNT(1) FROM jobs WHERE queue='$queue' AND status='pending'")
            val failed  = count("SELECT COUNT(1) FROM jobs WHERE queue='$queue' AND status='failed'")
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
    fun showStatus(queue: String, driver: String) {
        val m = JobMetricsCollector.collect(queue, driver)
0
        when (driver) {
            "sqs" -> {
                println("\n   ⚠️ Ignoring provided queueName [$queue]; using [KQ_SQS_QUEUE_URL] from environment instead.")
                println("\n   📊 STATUS [$driver]: pending=${m.pending}, inFlight=${m.inFlight ?: 0}, failed=${m.failed}")
            }
            "file" -> {
                println("\n   📊 STATUS [$driver]: Pending: ${m.pending} | Failed: ${m.failed}")
            }
            else  -> println("\n📊 STATUS [$driver][$queue]: pending=${m.pending}, failed=${m.failed}")
        }
    }
}

interface JobDriver {
    fun forEachPending(
        queue: String,
        jobId: String? = null
    ): List<JobResult>
}

object FileJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()
        val dir = File("jobs/$queue")

        val files = when {
            !jobId.isNullOrBlank() -> {
                val target = if (jobId.endsWith(".json", true)) jobId else "$jobId.json"
                dir.listFiles { f -> f.isFile && f.name.equals(target, true) }?.toList().orEmpty()
            }
            else -> dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name }.orEmpty()
        }

        if (files.isEmpty()) {
            results.add(JobResult.Error("⚠️ No jobs to run."))
            return results
        }

        files.forEach { file ->
            try {
                val task = JobSerializer.deserialize(file.readText())
                if (!file.delete()) {
                    results.add(JobResult.Error("⚠️ Could not delete processed job file: ${file.name}"))
                }
                results.add(JobResult.Ok(task))
            } catch (e: Exception) {
                results.add(JobResult.Error("❌ Failed to execute job from file '${file.name}': ${e.message}", e))
                FileJobFS.moveToFailed(queue, file)
            }
        }

        return results
    }
}

object RedisJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()

        Jedis("localhost", 6379).use { jedis ->
            while (true) {
                val jobJson = jedis.lpop(queue) ?: break
                try {
                    val task = JobSerializer.deserialize(jobJson)

                    // Si se pide jobId específico y no coincide, requeue
                    if (!jobId.isNullOrBlank() && task.id != jobId) {
                        jedis.rpush(queue, jobJson) // volver a la cola
                        continue
                    }

                    results.add(JobResult.Ok(task))
                } catch (e: Exception) {
                    results.add(JobResult.Error("❌ Failed Redis job: ${e.message}", e))
                    // Opcional: Dead Letter Queue
                    // jedis.rpush("$queue:dead", jobJson)
                }
            }
        }

        if (results.isEmpty()) {
            results.add(JobResult.Error("⚠️ No Redis jobs found in [$queue]"))
        }

        return results
    }
}

object SqsJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?): List<JobResult> {
        val region   = Region.of(env("KQ_SQS_REGION"))
        val results  = mutableListOf<JobResult>()

        val sqsClient = SqsClient.builder()
            .region(region)
            .credentialsProvider(sqsCredsProvider())
            .build()

        sqsClient.use {
            val queueUrl = env("KQ_SQS_QUEUE_URL")
            val msgs = it.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .messageAttributeNames("All")
                    .build()
            ).messages()

            if (msgs.isEmpty()) {
                results.add(JobResult.Error("⚠️ No SQS messages found."))
                return results
            }

            msgs.forEach { msg ->
                try {
                    val task = JobSerializer.deserialize(msg.body())
                    results.add(JobResult.Ok(task))
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
        }
        return results
    }
}

object DbJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?): List<JobResult> {
        val results = mutableListOf<JobResult>()
        val conn = DriverManager.getConnection("jdbc:sqlite:jobs.db")

        val sql = if (!jobId.isNullOrBlank())
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending' AND id = ?"
        else
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending'"

        conn.use { c ->
            c.prepareStatement(sql).use { stmt ->
                stmt.setString(1, queue)
                if (!jobId.isNullOrBlank()) stmt.setString(2, jobId)
                val rs = stmt.executeQuery()

                var found = false
                while (rs.next()) {
                    found = true
                    val jobIdDb = rs.getString("id")
                    val jobJson = rs.getString("payload")
                    try {
                        val task = JobSerializer.deserialize(jobJson)
                        results.add(JobResult.Ok(task))

                        // marcar como done
                        c.prepareStatement("UPDATE jobs SET status = 'done' WHERE id = ?").use { upd ->
                            upd.setString(1, task.id)
                            upd.executeUpdate()
                        }
                    } catch (e: Exception) {
                        results.add(JobResult.Error("❌ Failed DB job: ${e.message}", e))

                        // marcar como failed
                        c.prepareStatement("UPDATE jobs SET status = 'failed' WHERE id = ?").use { upd ->
                            upd.setString(1, jobIdDb)
                            upd.executeUpdate()
                        }
                    }
                }

                if (!found) {
                    results.add(JobResult.Error("⚠️ No DB jobs found in queue [$queue]"))
                }
            }
        }

        return results
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
        backend: ScriptBackend,             // ← antes era ScriptEngine
        queue: String = "default",
        driver: String = "file",
        jobId: String? = null,
        newParams: Map<String, Any?>,
        injector: (String) -> Any? = { null },
        logSpec: LogSpec? = null,
        onResult: (KouTask) -> Unit = {}
    ): List<JobResult> {
        val d = JobDrivers.resolve(driver)

        LoggerHolder.LOGGER.info {
            "\n🔁 Replaying jobs from [$queue] using [$driver]${if (jobId != null) " (jobId=$jobId)" else ""}\n"
        }

        val results = d.forEachPending(queue, jobId)

        results.forEach { res ->
            when (res) {
                is JobResult.Ok -> {
                    val updated = res.task.copy(
                        params = newParams.mapValues { JobSerializer.mapper.writeValueAsString(it.value) }
                    )

                    if (logSpec != null) {
                        captureLogs<Any?>("Scripts.Dispatcher", logSpec) { logger ->
                            withScriptLogger(logger, logSpec.mdc) {
                                val result = ScriptRunner.runScript(updated, backend, injector) // ← backend
                                logger.info { result.toString() }
                            }
                        }
                    } else {
                        ScriptRunner.runScript(updated, backend, injector) // ← backend
                    }

                    onResult(updated)
                }
                is JobResult.Error -> {
                    LoggerHolder.LOGGER.warn { res.message }
                    res.exception?.printStackTrace()
                }
            }
        }

        return results
    }
}

fun extractSignature(typeStr: String): Pair<List<String>, String> {
    // 1. Buscar la flecha "->"
    val parts = typeStr.split("->").map { it.trim() }
    if (parts.size != 2) {
        error("Tipo inválido: $typeStr")
    }

    val argsPart = parts[0]
    val returnPart = parts[1]

    // 2. Limpiar paréntesis de los argumentos
    val args = argsPart.removePrefix("(").removeSuffix(")")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() } // puede ser vacío si no tiene parámetros

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

fun KouTask.dispatchToQueue(
    queue: String? = null,
    driver: String? = null
) {
    val config = if (queue == null || driver == null) JobConfig.loadOrFail() else null

    val resolvedQueue = queue ?: config?.queue ?: error("Queue is required but not found")
    val resolvedDriver = driver ?: config?.driver ?: error("Driver is required but not found")

    JobDispatcher.dispatch(this, resolvedQueue, resolvedDriver)
}
