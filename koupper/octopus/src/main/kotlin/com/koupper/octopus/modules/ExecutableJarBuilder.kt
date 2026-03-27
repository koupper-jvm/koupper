package com.koupper.octopus.modules

import com.koupper.container.app
import com.koupper.octopus.modifiers.GradleConfigurator
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ExecutableJarBuilder(
    private val context: String,
    private val projectName: String,
    private val moduleVersion: String,
    private val packageName: String,
    private val artifactType: String
) : Module() {
    private val fileHandler = app.getInstance(FileHandler::class)

    private constructor(builder: Builder) : this(
        builder.context,
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.artifactType
    )

    companion object {
        inline fun build(config: Builder.() -> Unit) = Builder().apply(config).build().build()
    }

    override fun build() {
        // 1. Descargar y descomprimir el proyecto base
        val projectRoot = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL"), projectName)

        // 2. Limpiar el zip temporal (best effort)
        try {
            File("${context}${File.separator}model-project.zip").delete()
        } catch (e: Exception) {
            println("⚠️ No se pudo eliminar el zip temporal: ${e.message}")
        }

        // 3. Configurar Gradle con el nombre y versión del proyecto
        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.version = moduleVersion
        }

        // 4. Descargar el process manager en libs/
        val libsDir = projectRoot.resolve("libs")
        libsDir.mkdirs()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL")),
            libsDir.resolve("octopus-${env("OCTOPUS_VERSION")}.jar").toString()
        )

        // 5. LIMPIAR el proyecto y crear scripts
        cleanProjectAndCreateBootstrapping(projectRoot)

        println("✅ Proyecto listo en: ${projectRoot.absolutePath}")
    }

    private fun cleanProjectAndCreateBootstrapping(projectRoot: File) {
        val kotlinRoot = Paths.get(projectRoot.toString(), "src", "main", "kotlin")

        if (!Files.exists(kotlinRoot)) {
            throw IllegalStateException("Model project missing src/main/kotlin")
        }

        println("🧹 LIMPIANDO PROYECTO - Eliminando solo io.mp, server, http.controllers")
        println("📂 projectRoot: ${projectRoot.absolutePath}")

        // 1. Buscar Bootstrapping.kt original (está en io/mp/)
        val originalBootstrapping = findFileByName(kotlinRoot.toFile(), "Bootstrapping.kt")
            ?: throw IllegalStateException("Bootstrapping.kt not found in model project")

        println("🔍 Bootstrapping original encontrado en: ${originalBootstrapping.absolutePath}")

        // 2. Leer y preparar el nuevo contenido
        val originalContent = originalBootstrapping.readText(Charsets.UTF_8)
        val newBootstrappingContent = generateBootstrappingContent(originalContent, artifactType, packageName)

        if (artifactType.lowercase() != "job") {

            val fileToDelete = File(projectRoot, "jobs.json")

            if (fileToDelete.exists()) {
                fileToDelete.delete()
            }
        }

        // 3. Buscar y respaldar temporalmente la carpeta extensions (si existe)
        val extensionsBackup = backupExtensions(kotlinRoot.toFile())

        // 4. ELIMINAR SOLO LAS CARPETAS ESPECÍFICAS (NO TODO)
        println("🗑️ Eliminando carpetas no deseadas:")

        // Lista de carpetas a eliminar (solo las que están en la raíz de kotlin)
        val foldersToDelete = listOf("io", "http", "server")

        kotlinRoot.toFile().listFiles()?.forEach { file ->
            if (file.isDirectory && file.name in foldersToDelete) {
                println("   Eliminando carpeta: ${file.name}")
                file.deleteRecursively()
            } else if (file.isFile) {
                println("   Conservando archivo: ${file.name}")
            } else if (file.isDirectory && file.name !in foldersToDelete) {
                println("   Conservando carpeta: ${file.name}")
            }
        }

        // 5. Restaurar extensions si existían
        if (extensionsBackup != null) {
            restoreExtensions(kotlinRoot.toFile(), extensionsBackup)
        }

        // 6. Crear el directorio del paquete destino
        val packagePath = packageName.replace('.', File.separatorChar)
        val targetDir = File(kotlinRoot.toFile(), packagePath)
        targetDir.mkdirs()

        // 7. Mover/Crear Bootstrapping.kt en el nuevo paquete
        val targetBootstrapping = File(targetDir, "Bootstrapping.kt")

        // Si el Bootstrapping original estaba en io/mp/, lo movemos
        if (originalBootstrapping.parentFile.parentFile?.name == "io" && originalBootstrapping.parentFile.name == "mp") {
            // El original está en io/mp/Bootstrapping.kt, lo movemos
            originalBootstrapping.delete() // Eliminamos el original después de leerlo
        }

        targetBootstrapping.writeText(newBootstrappingContent, Charsets.UTF_8)
        println("✅ Bootstrapping creado en: ${targetBootstrapping.absolutePath}")

        // 8. Limpiar directorios vacíos (opcional)
        cleanupEmptyDirectories(Paths.get(projectRoot.toString(), "src", "main"))
    }

    private fun backupExtensions(kotlinRoot: File): File? {
        val extensionsDir = File(kotlinRoot, EXTENSIONS_FOLDER_NAME)
        if (!extensionsDir.exists()) return null

        val tempDir = File(System.getProperty("java.io.tmpdir"), "ext_${System.currentTimeMillis()}")
        extensionsDir.copyRecursively(tempDir, overwrite = true)
        println("📦 Extensions respaldadas temporalmente en: ${tempDir.absolutePath}")
        return tempDir
    }

    private fun restoreExtensions(kotlinRoot: File, backup: File) {
        val extensionsDir = File(kotlinRoot, EXTENSIONS_FOLDER_NAME)
        backup.copyRecursively(extensionsDir, overwrite = true)
        backup.deleteRecursively()
        println("✅ Extensions restauradas en: ${extensionsDir.absolutePath}")
    }

    private fun cleanupEmptyDirectories(path: Path) {
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .filter { Files.isDirectory(it) }
            .forEach { dir ->
                try {
                    if (Files.list(dir).count() == 0L) {
                        Files.delete(dir)
                        println("📁 Directorio vacío eliminado: ${dir.toAbsolutePath()}")
                    }
                } catch (e: Exception) {
                    // Ignorar errores al limpiar
                }
            }
    }

    private fun generateBootstrappingContent(originalContent: String, artifactType: String, targetPackage: String): String {
        // 1. Actualizar el package
        val pkgRegex = Regex("""(?m)^\s*package\s+.+\s*$""")
        val contentWithPackage = if (pkgRegex.containsMatchIn(originalContent)) {
            originalContent.replace(pkgRegex, "package $targetPackage")
        } else {
            "package $targetPackage\n\n$originalContent"
        }

        // 2. Extraer los imports originales para mantenerlos
        val importRegex = Regex("""^import .+$""", RegexOption.MULTILINE)
        val originalImports = importRegex.findAll(originalContent).map { it.value }.toList()

        // 3. Procesar según el artifactType
        return when (artifactType.lowercase()) {
            "script" -> processScriptExample(contentWithPackage, originalImports, targetPackage)
            "job" -> processJobExample(contentWithPackage, originalImports, targetPackage)
            "pipeline" -> processPipelineExample(contentWithPackage, originalImports, targetPackage)
            else -> contentWithPackage
        }
    }

    private fun processScriptExample(content: String, originalImports: List<String>, targetPackage: String): String {
        val scriptBlock = Regex("""/\*\s*#SCRIPT_EXAMPLE\s*(.*?)\s*\*/""", RegexOption.DOT_MATCHES_ALL)

        // Extraer el contenido del script
        val scriptContent = scriptBlock.find(content)?.groupValues?.get(1)?.trim() ?: ""

        // Imports específicos para script
        val scriptImports = listOf(
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.Input",
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.Output",
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.myScript",
            "import com.koupper.octopus.createDefaultConfiguration"
        )

        return buildString {
            appendLine("package $targetPackage")
            appendLine()
            scriptImports.forEach { appendLine(it) }
            appendLine()
            appendLine("private val processManager = createDefaultConfiguration()")
            appendLine()
            appendLine("fun main() {")
            appendLine("    // Use the processManager to use a default configurations and execute the script files.")
            appendLine()
            val lines = scriptContent.lines()

            lines.forEachIndexed { index, line ->
                if (index == 0) {
                    appendLine("    $line")
                } else {
                    appendLine(line)
                }
            }
            appendLine("}")
        }.trim()
    }

    private fun processJobExample(content: String, originalImports: List<String>, targetPackage: String): String {
        val jobBlock = Regex("""/\*\s*#JOB_EXAMPLE\s*(.*?)\s*\*/""", RegexOption.DOT_MATCHES_ALL)

        // Extraer el contenido del job
        val jobContent = jobBlock.find(content)?.groupValues?.get(1)?.trim() ?: ""

        // Imports específicos para job
        val jobImports = listOf(
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.Input",
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.myScript",
            "import com.koupper.octopus.createDefaultConfiguration",
            "import com.koupper.orchestrator.asJob",
            "import com.koupper.orchestrator.dispatchToQueue"
        )

        return buildString {
            appendLine("package $targetPackage")
            appendLine()
            jobImports.forEach { appendLine(it) }
            appendLine()
            appendLine("private val processManager = createDefaultConfiguration()")
            appendLine()
            appendLine("fun main() {")
            appendLine("    // Use the processManager to use a default configurations and execute the script files.")
            appendLine()
            jobContent.lines().forEach { line ->
                appendLine("    $line")
            }
            appendLine("}")
        }.trim()
    }

    private fun processPipelineExample(content: String, originalImports: List<String>, targetPackage: String): String {
        val pipelineBlock = Regex("""/\*\s*#PIPELINE_EXAMPLE\s*(.*?)\s*\*/""", RegexOption.DOT_MATCHES_ALL)

        // Extraer el contenido del pipeline
        val pipelineContent = pipelineBlock.find(content)?.groupValues?.get(1) ?: ""
        val normalized = pipelineContent.trimIndent().trim()

        // Imports específicos para pipeline
        val pipelineImports = listOf(
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.script1",
            "import $targetPackage.$EXTENSIONS_FOLDER_NAME.script2",
            "import com.koupper.octopus.ScriptExecutor",
            "import com.koupper.octopus.createDefaultConfiguration",
            "import com.koupper.shared.octopus.dependsOn"
        )

        return buildString {
            appendLine("package $targetPackage")
            appendLine()
            pipelineImports.forEach { appendLine(it) }
            appendLine()
            appendLine("fun main() {")
            appendLine("    // Use the processManager to use a default configurations and execute the script files.")
            appendLine()
            val normalized = pipelineContent.trimIndent()
            val lines = normalized.lines()

            lines.forEachIndexed { index, line ->
                if (index == 0) {
                    appendLine("    $line")
                } else {
                    appendLine(line)
                }
            }
            appendLine("}")
        }.trim()
    }

    private fun findFileByName(baseDir: File, fileName: String): File? {
        if (!baseDir.exists()) return null
        return baseDir.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
    }

    class Builder {
        var context: String = ""
        var projectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""
        var artifactType: String = ""
        var scripts: Map<String, String> = emptyMap()  // 👈 AGREGADO!

        fun build() = ExecutableJarBuilder(this)
    }
}
