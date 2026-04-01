package com.koupper.octopus.process

import com.koupper.octopus.isRelativeScriptFile
import com.koupper.octopus.modifiers.SetupGrizzlyConfigurator
import com.koupper.octopus.modules.ExecutableJarBuilder
import com.koupper.octopus.modules.aws.AWSAGHandlerBuilder
import com.koupper.octopus.modules.aws.LambdaFunctionBuilder
import com.koupper.octopus.modules.http.ControllersBuilder
import java.io.File

val getRealScriptNameFrom: (String) -> String = { script ->
    val scriptId = if (script.endsWith(".kts")) script else "$script.kts"
    if (!isRelativeScriptFile(scriptId)) {
        scriptId.substring(script.lastIndexOf("/") + 1)
    } else {
        scriptId
    }
}

fun findKtFileRecursively(baseDir: File, fileName: String): File? {
    if (!baseDir.exists()) return null
    return baseDir.walkTopDown().find {
        it.isFile && it.name == "$fileName.kt"
    }
}

class ModuleProcessor(private val context: String, private vararg val flags: String) : Process {
    private var name: String = "ModuleProcessor"
    private var template: String = "default"
    private var artifactType: String = "script"
    private var version: String = "Not Set"
    private var packageName: String = "Not Set"
    private var scripts: Map<String, String> = emptyMap()  // 👈 AHORA SÍ SE USA
    private var moduleProcessorInfo: String? = null

    var isProcessorInfo: Boolean = false
        private set

    fun name(name: String) = apply { this.name = name }
    fun template(template: String) = apply { this.template = template }
    fun artifactType(type: String) = apply { this.artifactType = type }
    fun version(version: String) = apply { this.version = version }
    fun packageName(packageName: String) = apply { this.packageName = packageName }
    fun scripts(scripts: Map<String, String>) = apply { this.scripts = scripts }  // 👈 DESCOMENTADO!

    override fun processName() = this.name
    override fun processType() = this.template

    override fun run() {
        this.buildByTemplate()
    }

    private fun buildByTemplate() {
        val self = this

        when (self.template.trim().lowercase()) {
            "default", "executable-jar" -> {
                ExecutableJarBuilder.build {
                    context = self.context
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    artifactType = self.artifactType
                }
            }

            "lambda" -> {
                LambdaFunctionBuilder.build {
                    context = self.context
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }

            "http" -> {
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
            }

            else -> {
                ExecutableJarBuilder.build {
                    context = self.context
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    artifactType = self.artifactType
                    scripts = self.scripts  // 👈 PASANDO LOS SCRIPTS!
                }
            }
        }
    }
}
