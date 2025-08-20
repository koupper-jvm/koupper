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
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
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
    val params: Map<String, Any>,
    val signature: Pair<List<String>, String>?,
    val scriptPath: String?,
    val packageName: String?,
    val origin: String = "koupper",
    val context: String = "default",
    val sourceType: String = "compiled",
    val queue: String = "default",
    val contextVersion: String ,
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
                    .region(Region.US_EAST_1)
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
    fun runPendingJobs(queue: String = "default", driver: String = "file") {
        println("🔧 Running jobs from [$queue] using [$driver] driver")

        when (driver) {
            "file" -> runFromFile(queue)
            "redis" -> runFromRedis(queue)
            "sqs" -> runFromSQS(queue)
            "database" -> runFromDatabase(queue)
            else -> println("❌ Unknown driver: $driver")
        }
    }

    private fun runFromFile(queue: String) {
        val dir = File("jobs/$queue")
        val jobs = dir.listFiles()?.filter { it.extension == "json" } ?: return

        jobs.forEach {
            try {
                val task = JobSerializer.deserialize(it.readText())
                println("🚀 Running job: ${task.id} from [$queue]")
                executeTask(task)
                it.delete()
            } catch (e: Exception) {
                println("❌ Failed to execute job from file: ${e.message}")
            }
        }
    }

    private fun runFromRedis(queue: String) {
        val jedis = Jedis("localhost", 6379)
        while (true) {
            val jobJson = jedis.lpop(queue) ?: break
            try {
                val task = JobSerializer.deserialize(jobJson)
                println("🚀 Running Redis job: ${task.id}")
                executeTask(task)
            } catch (e: Exception) {
                println("❌ Failed Redis job: ${e.message}")
            }
        }
    }

    private fun runFromSQS(queueName: String) {
        val queueUrl = env("KQ_SQS_QUEUE_URL")

        val sqsClient = SqsClient.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(env("KQ_SQS_ACCESS_KEY"), env("KQ_SQS_SECRET_KEY"))
                )
            )
            .build()

        val messages = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(5)
                .build()
        ).messages()

        for (msg in messages) {
            try {
                val task = JobSerializer.deserialize(msg.body())
                println("🚀 Running SQS job: ${task.id}")
                executeTask(task)

                sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(msg.receiptHandle())
                        .build()
                )
            } catch (e: Exception) {
                println("❌ Failed SQS job: ${e.message}")
            }
        }
    }

    private fun runFromDatabase(queue: String) {
        val conn = DriverManager.getConnection("jdbc:sqlite:jobs.db") // o Postgres, MySQL, etc.
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT id, payload FROM jobs WHERE queue = '$queue' AND status = 'pending'")

        while (rs.next()) {
            try {
                val jobJson = rs.getString("payload")
                val task = JobSerializer.deserialize(jobJson)

                println("🚀 Running DB job: ${task.id}")
                executeTask(task)

                val update = conn.prepareStatement("UPDATE jobs SET status = 'done' WHERE id = ?")
                update.setString(1, task.id)
                update.executeUpdate()
            } catch (e: Exception) {
                println("❌ Failed DB job: ${e.message}")
            }
        }

        rs.close()
        stmt.close()
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

            val loader: URLClassLoader = when {
                task.scriptPath?.endsWith(".jar") == true -> {
                    val jarFile = File(task.scriptPath)
                    require(jarFile.exists()) { "❌ El archivo .jar no existe: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(jarFile.toURI().toURL()), Thread.currentThread().contextClassLoader)
                }
                task.scriptPath?.contains("/kotlin/main") == true || task.scriptPath?.contains("\\kotlin\\main") == true -> {
                    val dir = File(task.scriptPath)
                    require(dir.exists() && dir.isDirectory) { "❌ La ruta no existe o no es un directorio: ${task.scriptPath}" }
                    URLClassLoader(arrayOf(dir.toURI().toURL()), Thread.currentThread().contextClassLoader)
                }
                else -> {
                    println("🔍 scriptPath ignorado, usando classloader de entorno empaquetado")
                    Thread.currentThread().contextClassLoader as URLClassLoader
                }
            }

            println("🧭 Loader URLs:")
            loader.urLs.forEach { println(" - $it") }

            val classNameGuess = "${task.packageName}.${task.fileName}"
            val fallbackClassName = "${task.packageName}.${task.functionName.replaceFirstChar { it.uppercase() }}Kt"

            val clazz = try {
                loader.loadClass(classNameGuess)
            } catch (_: ClassNotFoundException) {
                println("⚠ No se encontró $classNameGuess, intentando con $fallbackClassName")
                loader.loadClass(fallbackClassName)
            }

            println("🧪 Campos disponibles en ${clazz.name}:")
            clazz.declaredFields.forEach { println(" - ${it.name}") }

            val field = clazz.getDeclaredField(task.functionName)
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

            val args = task.params.entries
                .sortedBy { it.key }
                .mapIndexed { index, entry ->
                    val typeName = task.signature?.first?.get(index)
                        ?: error("Missing type for arg$index")

                    val rawType = typeName.replace("?", "")
                    val paramClass = kotlinToJava[rawType] ?: Class.forName(rawType, true, loader)

                    val raw = entry.value.toString() ?: "null"

                    val jsonForJackson: String = when {
                        (raw.length >= 2 && raw[0] == '"' && (raw[1] == '{' || raw[1] == '[')) -> {
                            JobSerializer.mapper.readValue(raw, String::class.java)
                        }
                        else -> raw
                    }

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
                println("✅ Job result: $result")
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
            }
        } catch (e: Exception) {
            println("❌ Error ejecutando job compilado: ${e.message}")
            e.printStackTrace()
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
    val fileName = fullClassName.substringBefore("$$") // 👉 Elimina el sufijo de lambda
        .substringAfterLast('.')  // 👉 Solo el nombre sin el paquete
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