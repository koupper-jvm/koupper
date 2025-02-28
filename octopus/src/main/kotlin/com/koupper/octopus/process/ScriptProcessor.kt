package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.isRelativeScriptFile
import com.koupper.octopus.modifiers.DeploymentConfigurator
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.aws.ExecutableJarBuilder
import com.koupper.octopus.modules.aws.LambdaFunctionBuilder
import com.koupper.octopus.modules.aws.LocalAWSDeploymentBuilder
import com.koupper.octopus.modules.http.service.GrizzlyGradleJerseyBuilder
import com.koupper.os.env
import com.koupper.providers.files.YmlFileHandler
import java.io.File
import kotlin.system.exitProcess

val getRealScriptNameFrom: (String) -> String = { script ->
    val scriptId = if (script.endsWith(".kts")) script else "${script}.kts"

    val finalScriptName = (if (!isRelativeScriptFile(scriptId)) {
        scriptId.substring(script.lastIndexOf("/") + 1)
    } else {
        scriptId
    })

    finalScriptName
}

class ScriptProcessor(val container: Container) : Process {
    private lateinit var name: String
    private lateinit var moduleType: String
    private lateinit var version: String
    private lateinit var packageName: String
    private lateinit var scripts: Map<String, String>

    override fun register(name: String,
                          moduleType: String,
                          version: String,
                          packageName: String,
                          scripts: Map<String, String>
    ) : Process {
        this.name = name
        this.moduleType = moduleType
        this.version = version
        this.packageName = packageName
        this.scripts = scripts


        return this
    }

    override fun processName(): String {
        return this.name
    }

    override fun processType(): String {
        return this.moduleType
    }

    override fun run() {
        val isDeployable = this.moduleType.equals("GRYZZLY2_GRADLE_JERSEY", true) ||
                this.moduleType.equals("DEPLOYABLE_AWS_LAMBDA_JAR", true) ||
                this.moduleType.equals("EXECUTABLE_JAR", true)

        if (File(this.name).exists()) {
            if (isDeployable) {
                Module.locateScripts(this.scripts, this.name, this.packageName)

                return
            }

            println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

            exitProcess(0)
        }

        this.buildByType()
    }

    private fun buildByType() {
        val ymlHandler = app.getInstance(YmlFileHandler::class)

        val content = ymlHandler.readFrom(env("CONFIG_DEPLOYMENT_FILE"))

        val server = content["server"]
        var serverPort = 8080
        var contextPath = "/"

        if (server != null) {
            val properties: List<Map<String, String>> = server as List<Map<String, String>>

            for (serverItem: Map<String, String> in properties) {
                if (serverItem.contains("port")) {
                    if (serverItem["port"] != null || serverItem["port"]!!.isNotEmpty()) {
                        val portValue = serverItem["port"]?.toIntOrNull()
                            ?: 8080

                        serverPort = portValue
                    }
                } else if (serverItem.contains("contextPath")) {
                    if (serverItem["contextPath"] != null || serverItem["contextPath"]!!.isNotEmpty()) {
                        contextPath = serverItem["contextPath"]!!
                    }
                }
            }
        }

        val self = this

        when {
            this.moduleType.equals("GRYZZLY_GRADLE_JERSEY", true) -> {
                GrizzlyGradleJerseyBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
            this.moduleType.equals("DEPLOYABLE_AWS_LAMBDA_JAR", true) -> {
                LambdaFunctionBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
            this.moduleType.equals("EXECUTABLE_JAR", true) -> {
                ExecutableJarBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                }
            }
            this.moduleType.equals("LOCAL_AWS_DEPLOYMENT", true) -> {
                LocalAWSDeploymentBuilder.build {
                    projectName = self.name
                    version = self.version
                    packageName = self.packageName
                    deployableScripts = self.scripts
                    rootPath = contextPath
                }

                DeploymentConfigurator.configure {
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
