package com.koupper.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.orchestrator.config.JobConfig
import com.koupper.os.env
import com.koupper.shared.octopus.extractExportFunctionSignature
import redis.clients.jedis.Jedis
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.*
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
    val contextVersion: String
)

object JobSerializer {
    val mapper = jacksonObjectMapper()
    fun serialize(task: KouTask): String = mapper.writeValueAsString(task)
    fun deserialize(json: String): KouTask = mapper.readValue(json, KouTask::class.java)
}

object JobDispatcher {
    fun dispatch(task: KouTask, queue: String = "default", driver: String = "file") {
        when (driver) {
            "file" -> {
                val dir = File("${Paths.get("").toAbsolutePath()}/jobs/$queue").apply { mkdirs() }
                File(dir, "${task.id}.json").writeText(JobSerializer.serialize(task))
            }
            "database" -> {
                // insertar en una tabla jobs con el nombre del queue
            }
            "redis" -> {
                // encolar en lista Redis con nombre `queue`
            }
            "sqs" -> {
                val queueUrl = env("KQ_SQS_QUEUE_URL")
                val accessKey = env("KQ_SQS_ACCESS_KEY") ?: error("Missing KQ_SQS_ACCESS_KEY")
                val secretKey = env("KQ_SQS_SECRET_KEY") ?: error("Missing KQ_SQS_SECRET_KEY")

                val sqsClient = SqsClient.builder()
                    .region(Region.of(env("KQ_SQS_REGION")))
                    .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                    ))
                    .build()

                try {
                    val sendRequest = SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(JobSerializer.serialize(task))
                        .build()

                    val result = sqsClient.sendMessage(sendRequest)
                    println("✅ Job sent to SQS [${queueUrl}] with message ID: ${result.messageId()}")
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
        jobId: String? = null
    ) {
        println()
        println("🔧 Running jobs from [$queue] using [$driver] driver${if (jobId != null) " (jobId=$jobId)" else ""}")
        println()

        when (driver) {
            "file" -> runFromFile(queue, jobId)
            "redis" -> runFromRedis(queue, jobId)
            "sqs" -> {
                println("⚠️ Ignoring provided queueName [$queue]; using [KQ_SQS_QUEUE_URL] from environment instead.\n")
                println("⚠️ JobId filtering is not supported in safe-list mode.\n")
                runFromSQS()
            }
            "database" -> runFromDatabase(queue, jobId)
            else -> println("❌ Unknown driver: $driver")
        }
    }

    private fun runFromFile(queue: String, jobId: String?) {
        val dir = File("jobs/$queue")

        println("📂 Looking for jobs in: ${dir.absolutePath}${if (!jobId.isNullOrBlank()) " (jobId=$jobId)" else ""}\n")

        if (!dir.exists() || !dir.isDirectory) {
            println("⚠️  Queue folder not found: ${dir.path}")
            return
        }

        val jobs: List<File> = when {
            !jobId.isNullOrBlank() -> {
                val target = if (jobId.endsWith(".json", ignoreCase = true)) jobId else "$jobId.json"
                dir.listFiles { f -> f.isFile && f.name.equals(target, ignoreCase = true) }
                    ?.toList()
                    .orEmpty()
                    .also {
                        if (it.isEmpty()) println("⚠️  Job '$target' not found in ${dir.path}")
                    }
            }

            else -> {
                dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.toList()
                    .orEmpty()
            }
        }

        if (jobs.isEmpty()) {
            println("⚠️  No jobs to run.")
            return
        }

        println("🧾 Jobs found: ${jobs.size} \n")

        jobs.forEach { file ->
            try {
                val task = JobSerializer.deserialize(file.readText())
                println("🚀 Running job: ${task.id} from [$queue] (file=${file.name})")
                executeTask(task)
                val deleted = file.delete()
                if (!deleted) println("⚠️  Could not delete processed job file: ${file.name}")
            } catch (e: Exception) {
                println("❌ Failed to execute job from file '${file.name}': ${e.message}")
                FileJobFS.moveToFailed(queue, file)
            }
        }
    }

    private fun runFromRedis(queue: String, jobId: String? = null) {
        val jedis = Jedis("localhost", 6379)
        var processed = 0
        while (true) {
            val jobJson = jedis.lpop(queue) ?: break
            try {
                val task = JobSerializer.deserialize(jobJson)
                if (!jobId.isNullOrBlank() && task.id != jobId) {
                    // Reencola al final para no perderlo ni alterar demasiado el flujo.
                    jedis.rpush(queue, jobJson)
                    continue
                }
                println("🚀 Running Redis job: ${task.id}")
                executeTask(task)
                processed++
            } catch (e: Exception) {
                println("❌ Failed Redis job: ${e.message}")
                // Opción: mover a una DLQ: jedis.rpush("$queue:dead", jobJson)
            }
        }
        if (processed == 0) println("⚠️ No Redis jobs processed${if (!jobId.isNullOrBlank()) " for jobId=$jobId" else ""}.")
    }

    private fun runFromSQS() {
        val sqsClient = SqsClient.builder()
            .region(Region.of(env("KQ_SQS_REGION")))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(env("KQ_SQS_ACCESS_KEY"), env("KQ_SQS_SECRET_KEY"))
                )
            )
            .build()

        sqsClient.use { sqs ->
            val queueUrl = env("KQ_SQS_QUEUE_URL")

            var processed = 0

            val messages = sqs.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .messageAttributeNames("All")
                    .build()
            ).messages()

            if (messages.isEmpty()) {
                println("⚠️  No SQS messages found.")
                return
            }

            for (msg in messages) {
                try {
                    val task = JobSerializer.deserialize(msg.body())

                    executeTask(task)

                    sqs.deleteMessage(
                        DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(msg.receiptHandle())
                            .build()
                    )
                    processed++
                } catch (e: Exception) {
                    println("❌ Failed SQS job: ${e.message}")
                }
            }

            if (processed == 0) {
                println("⚠️  No SQS jobs processed.")
            }
        }
    }

    private fun runFromDatabase(queue: String, jobId: String? = null) {
        val conn = DriverManager.getConnection("jdbc:sqlite:jobs.db") // o Postgres, MySQL, etc.

        val sql = if (!jobId.isNullOrBlank()) {
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending' AND id = ?"
        } else {
            "SELECT id, payload FROM jobs WHERE queue = ? AND status = 'pending'"
        }

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, queue)
            if (!jobId.isNullOrBlank()) stmt.setString(2, jobId)

            val rs = stmt.executeQuery()
            var processed = 0
            while (rs.next()) {
                try {
                    val jobJson = rs.getString("payload")
                    val task = JobSerializer.deserialize(jobJson)

                    println("🚀 Running DB job: ${task.id}")
                    executeTask(task)

                    conn.prepareStatement("UPDATE jobs SET status = 'done' WHERE id = ?").use { upd ->
                        upd.setString(1, task.id)
                        upd.executeUpdate()
                    }
                    processed++
                } catch (e: Exception) {
                    println("❌ Failed DB job: ${e.message}")
                    conn.prepareStatement("UPDATE jobs SET status = 'failed' WHERE id = ?").use { upd ->
                        upd.setString(1, rs.getString("id"))
                        upd.executeUpdate()
                    }
                }
            }
            if (processed == 0) println("⚠️ No DB jobs processed${if (!jobId.isNullOrBlank()) " for jobId=$jobId" else ""}.")
            rs.close()
        }
        conn.close()
    }

    private fun executeTask(task: KouTask) {
        when (task.sourceType) {
            "compiled", "jar" -> runCompiled(task)
            "script" -> println("📜 Script-based jobs not supported in JobRunner (yet).")
            else -> println("❓ Unknown source type: ${task.sourceType}")
        }
    }

    private fun runCompiled(task: KouTask) {
        try {
            println("🔍 Buscando clase para ejecutar: ${task.functionName}")

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

                return if (urls.isNotEmpty())
                    URLClassLoader(urls.toTypedArray(), base)
                else
                    base
            }

            val base = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
            val loader: ClassLoader = when {
                !task.scriptPath.isNullOrBlank() && task.scriptPath.endsWith(".jar") -> {
                    val jar = File(task.scriptPath!!)
                    require(jar.exists()) { "❌ No existe jar: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(jar.toURI().toURL()), base)
                }
                !task.scriptPath.isNullOrBlank() &&
                        (task.scriptPath.contains("/kotlin/main") || task.scriptPath.contains("\\kotlin\\main")) -> {
                    val dir = File(task.scriptPath)
                    require(dir.exists() && dir.isDirectory) { "❌ Ruta inválida: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(dir.toURI().toURL()), base)
                }
                else -> {
                    println("🔍 scriptPath ausente/no usable; buscando build local (classes/jar)")
                    fallbackLocalLoader(base)
                }
            }

            (loader as? URLClassLoader)?.urLs?.let { urls ->
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

            val args = task.params.entries.sortedBy { it.key }.mapIndexed { index, entry ->
                val typeName = task.signature?.first?.get(index) ?: error("Missing type for arg$index")
                val rawType = typeName.removeSuffix("?")
                val paramClass = kotlinToJava[rawType] ?: Class.forName(rawType, true, loader)

                val raw = entry.value.toString()
                val jsonForJackson =
                    if (raw.length >= 2 && raw[0] == '"' && (raw[1] == '{' || raw[1] == '['))
                        JobSerializer.mapper.readValue(raw, String::class.java)
                    else raw

                JobSerializer.mapper.readValue(jsonForJackson, paramClass)
            }

            val oldCl = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                val result = when (args.size) {
                    0 -> (functionRef as Function0<*>).invoke()
                    1 -> (functionRef as Function1<Any?, *>).invoke(args[0])
                    2 -> (functionRef as Function2<Any?, Any?, *>).invoke(args[0], args[1])
                    3 -> (functionRef as Function3<Any?, Any?, Any?, *>).invoke(args[0], args[1], args[2])
                    4 -> (functionRef as Function4<Any?, Any?, Any?, Any?, *>).invoke(args[0], args[1], args[2], args[3])
                    5 -> (functionRef as Function5<Any?, Any?, Any?, Any?, Any?, *>).invoke(args[0], args[1], args[2], args[3], args[4])
                    else -> error("❌ Demasiados argumentos para ejecutar la función.")
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
        println()
        println("🔧 List jobs from [$queue] using [$driver] driver${if (!jobId.isNullOrBlank()) " (jobId=$jobId)" else ""}")

        when (driver) {
            "file" -> listFromFile(queue, jobId)
            "redis" -> listFromRedis(queue, jobId)
            "sqs" -> {
                println("\n⚠️ Ignoring provided queueName [$queue]; using [KQ_SQS_QUEUE_URL] from environment instead.\n")
                println("⚠️ JobId filtering is not supported in safe-list mode.\n")
                listFromSQS()
            }
            "database" -> listFromDatabase(queue, jobId)
            else -> println("❌ Unknown driver: $driver")
        }
    }

    private fun listFromFile(queue: String, jobId: String? = null) {
        val dir = File("jobs/$queue")
        val jobs = dir.listFiles()?.filter { it.extension == "json" } ?: emptyList()

        if (jobs.isEmpty()) {
            println("⚠️ No jobs found in [$queue]")
            return
        }

        jobs.forEach {
            try {
                val task = JobSerializer.deserialize(it.readText())
                val fileId = it.nameWithoutExtension

                if (jobId.isNullOrBlank() || task.id == jobId || fileId == jobId) {
                    println()
                    println("📦 File ID: $fileId")
                    println(" - Job ID: ${task.id}")
                    println(" - Function: ${task.functionName}")
                    println(" - Params: ${task.params}")
                    println(" - Source: ${task.scriptPath}")
                    println(" - Context: ${task.context}")
                    println(" - Version: ${task.contextVersion}")
                    println(" - Origin: ${task.origin}")
                    println(" - File: ${it.name}")
                }
            } catch (e: Exception) {
                println("❌ Failed to parse job file ${it.name}: ${e.message}")
            }
        }
    }

    private fun listFromRedis(queue: String, jobId: String? = null) {
        val jedis = Jedis("localhost", 6379)
        val jobs = jedis.lrange(queue, 0, -1)

        if (jobs.isEmpty()) {
            println("⚠️ No Redis jobs found in [$queue]")
            return
        }

        jobs.forEach { jobJson ->
            try {
                val task = JobSerializer.deserialize(jobJson)
                if (jobId.isNullOrBlank() || task.id == jobId) {
                    println("📦 Job ID: ${task.id}")
                    println(" - Function: ${task.functionName}")
                    println(" - Params: ${task.params}")
                    println(" - Source: ${task.scriptPath}")
                    println(" - Context: ${task.context}")
                    println(" - Version: ${task.contextVersion}")
                    println(" - Origin: ${task.origin}")
                    println()
                }
            } catch (e: Exception) {
                println("❌ Failed to parse Redis job: ${e.message}")
            }
        }
    }

    private fun listFromSQS() {
        val sqs = SqsClient.builder()
            .region(Region.of(env("KQ_SQS_REGION")))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        env("KQ_SQS_ACCESS_KEY"),
                        env("KQ_SQS_SECRET_KEY")
                    )
                )
            ).build()

        sqs.use {
            val queueUrl = env("KQ_SQS_QUEUE_URL")

            val attrs = it.getQueueAttributes { b ->
                b.queueUrl(queueUrl).attributeNames(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                )
            }.attributes()

            val pending  = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toIntOrNull() ?: 0
            val inFlight = attrs[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toIntOrNull() ?: 0

            if (pending == 0 && inFlight == 0) {
                println("⚠️ No SQS jobs found.")
                return
            }

            println("📊 STATUS [sqs][KQ_SQS_QUEUE_URL]: pending=$pending, inFlight=$inFlight")
        }
    }


    private fun listFromDatabase(queue: String, jobId: String? = null) {
        val conn = DriverManager.getConnection("jdbc:sqlite:jobs.db") // ajusta a tu motor

        val sql = if (!jobId.isNullOrBlank()) {
            "SELECT id, payload, status FROM jobs WHERE queue = ? AND id = ?"
        } else {
            "SELECT id, payload, status FROM jobs WHERE queue = ?"
        }

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, queue)
            if (!jobId.isNullOrBlank()) stmt.setString(2, jobId)

            val rs = stmt.executeQuery()
            var found = false
            while (rs.next()) {
                found = true
                try {
                    val jobJson = rs.getString("payload")
                    val task = JobSerializer.deserialize(jobJson)
                    println("📦 Job ID: ${task.id}")
                    println(" - Function: ${task.functionName}")
                    println(" - Params: ${task.params}")
                    println(" - Source: ${task.scriptPath}")
                    println(" - Context: ${task.context}")
                    println(" - Version: ${task.contextVersion}")
                    println(" - Origin: ${task.origin}")
                    println(" - Status: ${rs.getString("status")}")
                    println()
                } catch (e: Exception) {
                    println("❌ Failed DB job parse: ${e.message}")
                }
            }
            if (!found) println("⚠️ No DB jobs found in [$queue]${if (!jobId.isNullOrBlank()) " for jobId=$jobId" else ""}")
            rs.close()
        }
        conn.close()
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

fun KProperty0<*>.asJob(vararg args: Any?): KouTask {
    val ref = this.get() ?: error("Function reference is null")

    val codeSource = ref.javaClass.protectionDomain?.codeSource?.location?.toURI()
    val scriptPath = codeSource?.path
    val file = codeSource?.let { File(it) }

    val packageName = ref.javaClass.`package`?.name ?: "unknown"
    val functionName = this.name
    val fullClassName = this.get()!!.javaClass.name
    val fileName = fullClassName.substringBefore("$$")
        .substringAfterLast('.')
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

    return KouTask(
        id = UUID.randomUUID().toString(),
        fileName = fileName,
        functionName = functionName,
        params = params,
        signature = signature,
        scriptPath = scriptPath,
        packageName = packageName,
        sourceType = sourceType,
        contextVersion = contextVersion
    )
}

fun KouTask.dispatchToQueue(
    queue: String? = null,
    driver: String? = null
) {
    val config = JobConfig.loadOrFail()
    val resolvedQueue = queue ?: config.queue
    val resolvedDriver = driver ?: config.driver

    JobDispatcher.dispatch(this, resolvedQueue, resolvedDriver)
}