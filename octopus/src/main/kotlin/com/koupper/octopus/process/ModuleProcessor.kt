package com.koupper.octopus.process

import com.koupper.octopus.Octopus
import com.koupper.octopus.isRelativeScriptFile
import com.koupper.octopus.modifiers.ControllersAnalyzer
import com.koupper.octopus.modifiers.ControllersBuilder
import com.koupper.octopus.modifiers.SetupGrizzlyConfigurator
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.aws.AWSAGHandlerBuilder
import com.koupper.octopus.modules.aws.ExecutableJarBuilder
import com.koupper.octopus.modules.aws.LambdaFunctionBuilder
import com.koupper.octopus.modules.validateScript
import com.koupper.shared.octopus.extractExportFunctionName
import java.io.File
import java.security.MessageDigest

val getRealScriptNameFrom: (String) -> String = { script ->
    val scriptId = if (script.endsWith(".kts")) script else "$script.kts"
    if (!isRelativeScriptFile(scriptId)) {
        scriptId.substring(script.lastIndexOf("/") + 1)
    } else {
        scriptId
    }
}

fun extractServerPort(moduleDir: File): String? {
    val setupFile = findKtFileRecursively(
        File(moduleDir, "src/main/kotlin"),
        "Setup"
    ) ?: return null

    val content = setupFile.readText()

    return Regex("""const val PORT\s*=\s*(\d+)""")
        .find(content)
        ?.groupValues?.get(1)
}

fun findKtFileRecursively(baseDir: File, fileName: String): File? {
    if (!baseDir.exists()) return null
    return baseDir.walkTopDown().find {
        it.isFile && it.name == "$fileName.kt"
    }
}

class ModuleProcessor(private val context: String, private vararg val flags: String) : Process {
    private var name: String = "Not Set"
    private var type: String = "Not Set"
    private var version: String = "Not Set"
    private var packageName: String = "Not Set"
    private var scripts: Map<String, String> = emptyMap()
    private var moduleProcessorInfo: String? = null

    var isProcessorInfo: Boolean = false
        private set

    fun name(name: String) = apply { this.name = name }
    fun type(type: String) = apply { this.type = type }
    fun version(version: String) = apply { this.version = version }
    fun packageName(packageName: String) = apply { this.packageName = packageName }
    fun scripts(scripts: Map<String, String>) = apply { this.scripts = scripts }

    fun getModuleProcessorInfo(): String? = moduleProcessorInfo

    private fun setModuleProcessorInfo(info: String) {
        this.moduleProcessorInfo = info
    }

    override fun processName() = this.name
    override fun processType() = this.type

    override fun run() {
        val ANSI_GREEN = "\u001B[32m"
        val ANSI_RED = "\u001B[31m"
        val ANSI_YELLOW = "\u001B[33m"
        val ANSI_CYAN = "\u001B[36m"
        val ANSI_RESET = "\u001B[0m"

        val baseDir = File(this.context)
        val moduleDir = if (flags.contains("--info")) {
            val normalPath = File(baseDir, this.name)
            if (normalPath.exists()) {
                normalPath
            } else {
                File(baseDir.parentFile, this.name)
            }
        } else {
            File(baseDir, this.name)
        }

        val existModule = moduleDir.exists()
        val statusIcon = if (existModule) "${ANSI_GREEN}✔️${ANSI_RESET}" else "${ANSI_RED}❌${ANSI_RESET}"
        val isDeployable = this.type.equals("GRYZZLY2_GRADLE_JERSEY", true) ||
                this.type.equals("DEPLOYABLE_AWS_LAMBDA_JAR", true) ||
                this.type.equals("EXECUTABLE_JAR", true)

        if (flags.contains("--info")) {
            isProcessorInfo = true
            val versionStatus = checkVersionSync(moduleDir)
            val packageStatus = checkPackageSync(moduleDir)
            val syncStatus = verifyScriptSync(moduleDir)
            val port = extractServerPort(moduleDir)?.toInt()!!

            val moduleAnalyzer = ControllersAnalyzer()
            moduleAnalyzer.analyzeControllers(moduleDir, port = port)

            val info = buildString {
                appendLine("${ANSI_CYAN}📦 Module Setup Info:${ANSI_RESET}")
                appendLine("  ${ANSI_YELLOW}- Manage to${ANSI_RESET}: $name $statusIcon")
                appendLine("  ${ANSI_YELLOW}- Type${ANSI_RESET}    : $type")
                appendLine("  ${ANSI_YELLOW}- Target version${ANSI_RESET} : $version $versionStatus")
                appendLine("  ${ANSI_YELLOW}- Package${ANSI_RESET} : $packageName $packageStatus")
                appendLine("  ${ANSI_YELLOW}- Scripts linked to their handlers [handlerName | scriptName] on target:${ANSI_RESET} :")
                scripts.forEach { (handlerName, scriptName) ->
                    val ktsFile = findKtFileRecursively(
                        File(moduleDir, "src/main/kotlin/${packageName.replace('.', '/')}"),
                        scriptName.removeSuffix(".kts")
                    )
                    val handlerFile = findKtFileRecursively(
                        File(moduleDir, "src/main/kotlin"),
                        "RequestHandler" + handlerName.replaceFirstChar { it.uppercaseChar() }
                    )

                    val handlerExists = handlerFile != null && handlerFile.exists()
                    val scriptExists = ktsFile != null && ktsFile.exists()

                    val usageCheck = if (handlerExists && scriptExists) {
                        val exportFunction = extractExportFunctionName(ktsFile!!.readText())
                        val handlerContent = handlerFile!!.readText()
                        handlerContent.contains("${exportFunction}(")
                    } else false


                    val finalHandlerColor = if (usageCheck) ANSI_GREEN else ANSI_RED
                    val finalScriptColor = if (usageCheck) ANSI_GREEN else ANSI_RED

                    appendLine("      ${finalHandlerColor}$handlerName[RequestHandler${handlerName.replaceFirstChar { it.uppercaseChar() }}] ${ANSI_RESET}⇄ ${finalScriptColor}$scriptName${ANSI_RESET}")
                }

                appendLine("\n" + syncStatus)
            }

            setModuleProcessorInfo(info)
            return
        }

        if (existModule) {
            if (isDeployable) {
                Module.locateScripts(moduleDir.absolutePath, this.scripts, this.name, this.packageName)
                return
            }
            println("\n${ANSI_YELLOW}A module named '$name' already exists.${ANSI_RESET}\n")
            return
        }

        this.buildByType()
    }

    private fun checkVersionSync(moduleDir: File): String {
        val buildFile = File(moduleDir, "build.gradle")
        if (!buildFile.exists()) return "❌ No build.gradle"
        val content = buildFile.readText()
        return if (content.contains("version = \"$version\"")) "✔️" else "❌ Mismatch"
    }

    private fun checkPackageSync(moduleDir: File): String {
        val srcPath = File(moduleDir, "src/main/kotlin/${packageName.replace('.', '/')}")
        return if (srcPath.exists()) "✔️" else "❌ Missing"
    }

    private fun verifyScriptSync(moduleDir: File): String {
        val srcPath = File(moduleDir, "src/main/kotlin/${packageName.replace('.', '/')}")
        val results = mutableListOf<String>()
        val GREEN = "\u001B[32m"
        val RED = "\u001B[31m"
        val RESET = "\u001B[0m"
        val HEADER_COLOR = "\u001B[36m"

        results.add("${HEADER_COLOR}📋 Script Synchronization with target:${RESET}")

        scripts.forEach { (handlerName, scriptFileName) ->
            val ktsFile = File(this.context, scriptFileName)
            val ktFile = findKtFileRecursively(srcPath, scriptFileName.removeSuffix(".kts"))

            val ktsValidation = if (ktsFile.exists()) {
                validateScript(ktsFile.path).isSuccess
            } else false

            val ktValidation = if (ktFile != null && ktFile.exists()) {
                validateScript(ktFile.path).isSuccess
            } else false

            val ktsIcon = if (ktsValidation) "✔️" else "❌"
            val ktIcon = if (ktValidation) "✔️" else "❌"

            val ktsColored = if (ktsValidation) "$GREEN$handlerName.kts$RESET" else "$RED$handlerName.kts$RESET"
            val ktColored = if (ktValidation) "$GREEN$handlerName.kt$RESET" else "$RED$handlerName.kt$RESET"

            val status = if (ktsFile.exists() && ktFile != null && ktFile.exists()) {
                val ktsHash = calculateHash(ktsFile)
                val ktHash = calculateHash(ktFile)

                val syncStatus = if (ktsHash == ktHash) "✔️ Synced" else "❌ Out of sync"
                "  $ktsIcon $ktsColored ⇄ $ktIcon $ktColored : $syncStatus"
            } else {
                "  ❌ $ktsColored ⇄ ❌ $ktColored : ❌ Missing file(s)"
            }

            results.add(status)
        }

        return results.joinToString("\n")
    }

    private fun calculateHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildByType() {
        val self = this

        when {
            this.type.equals("DEPLOYABLE_AWS_LAMBDA_JAR", true) -> {
                LambdaFunctionBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
            this.type.equals("EXECUTABLE_JAR", true) -> {
                ExecutableJarBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
            this.type.equals("HANDLERS_CONTROLLERS_SCRIPTS", true) -> {
                val awsModule = AWSAGHandlerBuilder.build {
                    context = self.context
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }

                SetupGrizzlyConfigurator.configure {
                    context = self.context
                    packageName = self.packageName
                    projectName = self.name
                    version = self.version
                }

                ControllersBuilder.build {
                    context = self.context
                    packageName = self.packageName
                    projectName = self.name
                    registeredScripts = awsModule.registeredScripts
                }

                /*val deployer = LocalAWSDeployer(container = self.container)
                deployer.register(self.name, self.metadata, "LOCAL_AWS_DEPLOYMENT", this.version, this.packageName, this.scripts)
                deployer.run()*/
            }
        }
    }
}
