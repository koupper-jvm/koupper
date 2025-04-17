package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.app
import com.koupper.octopus.isRelativeScriptFile
import com.koupper.octopus.modifiers.SetupGrizzlyConfigurator
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.aws.AWSAGHandlerBuilder
import com.koupper.octopus.modules.aws.ExecutableJarBuilder
import com.koupper.octopus.modules.aws.LambdaFunctionBuilder
import com.koupper.octopus.modules.http.service.GrizzlyGradleJerseyBuilder
import com.koupper.os.env
import com.koupper.providers.files.YmlFileHandler
import java.io.File

val getRealScriptNameFrom: (String) -> String = { script ->
    val scriptId = if (script.endsWith(".kts")) script else "${script}.kts"

    val finalScriptName = (if (!isRelativeScriptFile(scriptId)) {
        scriptId.substring(script.lastIndexOf("/") + 1)
    } else {
        scriptId
    })

    finalScriptName
}

class ModuleProcessor(private val context: String) : Process {
    private lateinit var name: String
    private lateinit var type: String
    private lateinit var version: String
    private lateinit var packageName: String
    private lateinit var scripts: Map<String, String>

    fun name(name: String): ModuleProcessor {
        this.name = name
        return this
    }

    fun type(type: String): ModuleProcessor {
        this.type = type
        return this
    }

    fun version(version: String): ModuleProcessor {
        this.version = version
        return this
    }

    fun packageName(packageName: String): ModuleProcessor {
        this.packageName = packageName
        return this
    }

    fun scripts(scripts: Map<String, String>): ModuleProcessor {
        this.scripts = scripts
        return this
    }

    override fun processName(): String {
        return this.name
    }

    override fun processType(): String {
        return this.type
    }

    override fun run() {
        val isDeployable = this.type.equals("GRYZZLY2_GRADLE_JERSEY", true) ||
                this.type.equals("DEPLOYABLE_AWS_LAMBDA_JAR", true) ||
                this.type.equals("EXECUTABLE_JAR", true)

        if (File(this.context + File.separator + this.name).exists()) {
            if (isDeployable) {
                Module.locateScripts(this.context, this.scripts, this.name, this.packageName)

                return
            }

            println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

            return
        }

        this.buildByType()
    }

    private fun buildByType() {
        val ymlHandler = app.getInstance(YmlFileHandler::class)

        val content = ymlHandler.readFrom(context + File.separator + env("CONFIG_DEPLOYMENT_FILE", context = context))

        val server = content["server"] as? Map<*, *>
        var serverPort = 8080
        var contextPath = "/"

        if (server != null) {
            server["port"]?.let { port ->
                serverPort = port.toString().toIntOrNull() ?: 8080
            }

            server["contextPath"]?.let { path ->
                contextPath = path.toString()
            }
        }

        val self = this

        when {
            this.type.equals("GRYZZLY_GRADLE_JERSEY", true) -> {
                GrizzlyGradleJerseyBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
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
            this.type.equals("AWS_AG_HANDLERS", true) -> {
                AWSAGHandlerBuilder.build {
                    context = self.context
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                    rootPath = contextPath
                }

                SetupGrizzlyConfigurator.configure {
                    port = serverPort
                    packageName = self.packageName
                    projectName = self.name
                    version = self.version
                }

                /*val deployer = LocalAWSDeployer(container = self.container)
                deployer.register(self.name, self.metadata, "LOCAL_AWS_DEPLOYMENT", this.version, this.packageName, this.scripts)
                deployer.run()*/
            }
        }
    }
}
