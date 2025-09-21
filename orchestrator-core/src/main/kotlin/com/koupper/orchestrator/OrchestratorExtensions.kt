package com.koupper.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.logging.GlobalLogger
import com.koupper.logging.KLogger
import com.koupper.logging.LogSpec
import com.koupper.logging.captureLogs
import com.koupper.orchestrator.config.JobConfig
import com.koupper.os.env
import com.koupper.os.envOptional
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.readTextOrNull
import com.koupper.shared.octopus.sha256Of
import redis.clients.jedis.Jedis
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
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
import javax.script.ScriptEngine
import kotlin.reflect.KProperty0

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
                val region    = Region.of(env("KQ_SQS_REGION"))
                val accessKey = env("KQ_SQS_ACCESS_KEY")
                val secretKey = env("KQ_SQS_SECRET_KEY")
                val queueUrl  = env("KQ_SQS_QUEUE_URL")
                val dlqUrl    = envOptional("KQ_SQS_DLQ_URL")

                val sqsClient = SqsClient.builder()
                    .region(region)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)
                        )
                    )
                    .build()

                try {
                    val sendRequest = SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(JobSerializer.serialize(task))
                        .build()

                    val result = sqsClient.sendMessage(sendRequest)
                    println("✅ Job sent to SQS [$queueUrl] with message ID: ${result.messageId()}")

                    if (dlqUrl.isNotBlank()) {
                        println("ℹ️  DLQ configured: $dlqUrl")
                    }
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
    ) {
        val d = JobDrivers.resolve(driver)
        d.forEachPending(queue, jobId) { task ->
            println("\n🔧 Running jobs from [$queue] using [$driver]${if (jobId != null) " (jobId=$jobId)" else ""}")
            runCompiled(task)
            onResult(task)
        }
    }

    private fun runCompiled(task: KouTask) {
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
            val fileBase = task.fileName.removeSuffix(".class").removeSuffix(".kt").removeSuffix("Kt")
            val candidates = linkedSetOf(
                "$pkg.${task.fileName}",
                "$pkg.$fileBase",
                "$pkg.${fileBase}Kt"
            )

            val clazz = candidates.asSequence()
                .mapNotNull { fqcn -> try { loader.loadClass(fqcn) } catch (_: ClassNotFoundException) { null } }
                .firstOrNull() ?: error("No se encontró clase para ${task.functionName}. Probé: $candidates")

            println("🧪 Campos disponibles en ${clazz.name}:")
            clazz.declaredFields.forEach { println(" - ${it.name}") }

            val field = try { clazz.getDeclaredField(task.functionName) } catch (_: NoSuchFieldException) {
                error("No existe el field '${task.functionName}' en ${clazz.name}. Campos: ${clazz.declaredFields.joinToString { it.name }}")
            }
            field.isAccessible = true
            val functionRef = field.get(null)

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

            // ⚠️ Ordena por índice numérico: arg0 < arg1 < arg10
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
                        if (raw.length >= 2 && raw[0] == '"' && (raw[1] == '{' || raw[1] == '['))
                            JobSerializer.mapper.readValue(raw, String::class.java)
                        else raw

                    JobSerializer.mapper.readValue(jsonForJackson, paramClass)
                }

            val oldCl = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                val result: Any? = when (functionRef) {
                    is kotlin.reflect.KFunction<*> -> {
                        // Referencia a función exportada como ::miFuncion
                        functionRef.call(*args.toTypedArray())
                    }
                    else -> {
                        // Lambda / FunctionN: invoca dinámicamente 'invoke' con la aridad exacta
                        val invoke = functionRef.javaClass.methods.firstOrNull {
                            it.name == "invoke" && it.parameterCount == args.size
                        } ?: error("❌ No se encontró método 'invoke' con aridad ${args.size} en ${functionRef.javaClass.name}")
                        invoke.invoke(functionRef, *args.toTypedArray())
                    }
                }

                print("✅ Job result: $result")
                println()
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
            }
        } catch (e: Exception) {
            println("❌ Error ejecutando job compilado: ${e.message}")
            e.printStackTrace()
        }
    }
}

object JobLister {
    fun list(queue: String = "default", driver: String = "file", jobId: String? = null) {
        val d = JobDrivers.resolve(driver)
        LoggerHolder.LOGGER.info { "\n🔧 List jobs from [$queue] using [$driver]${if (!jobId.isNullOrBlank()) " (jobId=$jobId)" else ""}\n" }
        d.forEachPending(queue, jobId) { task ->
            LoggerHolder.LOGGER.info { "📦 Job ID: ${task.id}" }
            LoggerHolder.LOGGER.info { " - Function: ${task.functionName}" }
            LoggerHolder.LOGGER.info { " - Params: ${task.params}" }
            LoggerHolder.LOGGER.info { " - Source: ${task.scriptPath}" }
            LoggerHolder.LOGGER.info { " - Context: ${task.context}"}
            LoggerHolder.LOGGER.info { " - Version: ${task.contextVersion}" }
            LoggerHolder.LOGGER.info { " - Origin: ${task.origin}\n" }
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
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(env("KQ_SQS_ACCESS_KEY"), env("KQ_SQS_SECRET_KEY"))
                )
            ).build()

        sqs.use {
            val mainUrl = env("KQ_SQS_QUEUE_URL")

            val attrs: MutableMap<QueueAttributeName, String> = it.getQueueAttributes { b ->
                b.queueUrl(mainUrl).attributeNames(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                )
            }.attributes()

            val pending  = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            val inFlight = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0

            var failed = 0
            val dlqUrl = env("KQ_SQS_DLQ_URL")
            if (dlqUrl.isNotBlank()) {
                val dlqAttrs: MutableMap<QueueAttributeName, String> = it.getQueueAttributes { b ->
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
        jobId: String? = null,
        consume: (task: KouTask) -> Unit
    )
}

object FileJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?, consume: (KouTask) -> Unit) {
        val dir = File("jobs/$queue")
        val files = when {
            !jobId.isNullOrBlank() -> {
                val target = if (jobId.endsWith(".json", true)) jobId else "$jobId.json"
                dir.listFiles { f -> f.isFile && f.name.equals(target, true) }?.toList().orEmpty()
            }
            else -> dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name }.orEmpty()
        }

        if (files.isEmpty()) {
            LoggerHolder.LOGGER.info { "⚠️ No jobs to run." }
            return
        }

        files.forEach { file ->
            try {
                val task = JobSerializer.deserialize(file.readText())
                if (!file.delete()) println("⚠️ Could not delete processed job file: ${file.name}")
                consume(task)
            } catch (e: Exception) {
                println("❌ Failed to execute job from file '${file.name}': ${e.message}")
                FileJobFS.moveToFailed(queue, file)
            }
        }
    }
}

object RedisJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?, consume: (KouTask) -> Unit) {
        Jedis("localhost", 6379).use { jedis ->
            while (true) {
                val jobJson = jedis.lpop(queue) ?: break
                try {
                    val task = JobSerializer.deserialize(jobJson)
                    if (!jobId.isNullOrBlank() && task.id != jobId) {
                        jedis.rpush(queue, jobJson) // requeue
                        continue
                    }
                    consume(task)
                } catch (e: Exception) {
                    println("❌ Failed Redis job: ${e.message}")
                    // opcional DLQ: jedis.rpush("$queue:dead", jobJson)
                }
            }
        }
    }
}

object SqsJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?, consume: (KouTask) -> Unit) {
        val sqs = SqsClient.builder()
            .region(Region.of(env("KQ_SQS_REGION")))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(env("KQ_SQS_ACCESS_KEY"), env("KQ_SQS_SECRET_KEY"))
                )
            ).build()

        sqs.use {
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
                println("⚠️  No SQS messages found.")
                return
            }

            msgs.forEach { msg ->
                try {
                    val task = JobSerializer.deserialize(msg.body())
                    // Nota: safe-list → no filtramos por jobId aquí (como ya hacías)
                    consume(task)
                    it.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build())
                } catch (e: Exception) {
                    println("❌ Failed SQS job: ${e.message}")
                }
            }
        }
    }
}

object DbJobDriver : JobDriver {
    override fun forEachPending(queue: String, jobId: String?, consume: (KouTask) -> Unit) {
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
                while (rs.next()) {
                    val jobJson = rs.getString("payload")
                    try {
                        val task = JobSerializer.deserialize(jobJson)
                        consume(task)
                        c.prepareStatement("UPDATE jobs SET status = 'done' WHERE id = ?").use { upd ->
                            upd.setString(1, task.id)
                            upd.executeUpdate()
                        }
                    } catch (e: Exception) {
                        println("❌ Failed DB job: ${e.message}")
                        c.prepareStatement("UPDATE jobs SET status = 'failed' WHERE id = ?").use { upd ->
                            upd.setString(1, rs.getString("id"))
                            upd.executeUpdate()
                        }
                    }
                }
            }
        }
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
        engine: ScriptEngine,
        queue: String = "default",
        driver: String = "file",
        jobId: String? = null,
        newParams: Map<String, Any?>,
        injector: (String) -> Any? = { null },
        onResult: (KouTask) -> Unit = {}
    ) {
        val d = JobDrivers.resolve(driver)
        println("\n🔁 Replaying jobs from [$queue] using [$driver]${if (jobId != null) " (jobId=$jobId)" else ""}\n")
        d.forEachPending(queue, jobId) { task ->
            val updated = task.copy(
                params = newParams.mapValues { JobSerializer.mapper.writeValueAsString(it.value) }
            )

            ScriptRunner.runScript(updated, engine, injector)
            onResult(updated)
        }
    }
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
    val signature = extractExportFunctionSignature(this.returnType.toString())

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
