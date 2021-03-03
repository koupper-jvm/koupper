package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_RED
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.exceptions.InvalidScriptException
import com.koupper.octopus.managers.ProcessExecutionManager
import com.koupper.octopus.process.ModuleProcess
import com.koupper.octopus.process.ScriptProcess
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.http.Client
import com.koupper.providers.parsing.JsonToObject
import com.koupper.providers.parsing.TextJsonParser
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.extensions.splitKeyValue
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import javax.script.ScriptEngineManager
import kotlin.reflect.KClass
import kotlin.system.exitProcess

val isRelativeFile: (String) -> Boolean = {
    it.contains("^[a-zA-Z0-9]+.kts$".toRegex())
}

class Octopus(private var container: Container) : ProcessExecutionManager {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()
    private val userHomePath = System.getProperty("user.home")
    //private val NULL_FILE = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")

    override fun <T> run(sentence: String, params: Map<String, Any>, result: (value: T) -> Unit) {
        System.setProperty("kotlin.script.classpath", currentClassPath)

        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid. $sentence")
            }

            val firstSpaceInSentence = sentence.indexOf(" ")

            when {
                isContainerType(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (Container) -> T

                        result(targetCallback.invoke(container))
                    } else {
                        val targetCallback = eval(valName) as (Container, Map<String, Any>) -> T

                        result(targetCallback.invoke(container, params))
                    }

                }
                isScriptProcess(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (ScriptProcess) -> T

                        result(targetCallback.invoke(ScriptConfiguration()))
                    } else {
                        val targetCallback = eval(valName) as (ScriptProcess, Map<String, Any>) -> T

                        result(targetCallback.invoke(ScriptConfiguration(), params))
                    }
                }
                isModuleProcess(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    if (params.isEmpty()) {
                        val targetCallback = eval(valName) as (ModuleProcess) -> T

                        result(targetCallback.invoke(ModuleConfiguration()))
                    } else {
                        val targetCallback = eval(valName) as (ModuleProcess, Map<String, Any>) -> T

                        result(targetCallback.invoke(ModuleConfiguration(), params))
                    }
                }
                else -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf("=") - 1

                    val valName = sentence.substring(firstSpaceInSentence, endOfVariableNameInSentence).trim()

                    result(eval(valName) as T)
                }
            }
        }
    }

    override fun <T> runScriptFile(scriptPath: String, params: String, result: (value: T) -> Unit) {
        val content = File(scriptPath).readText(Charsets.UTF_8)

        this.runScriptByType(scriptPath, params, content, result)
    }

    override fun <T> runScriptFileFromUrl(scriptUrl: String, params: String, result: (value: T) -> Unit) {
        val content = URL(scriptUrl).readText()

        this.runScriptByType(scriptUrl, params, content, result)
    }

    private fun <T> runScriptByType(scriptFile: String, params: String, content: String, result: (value: T) -> Unit) {
        when {
            "init.kts" in scriptFile -> {
                this.run(content) { scriptManager: ScriptProcess ->
                    result(scriptManager as T)
                }
            }
            params == "EMPTY_PARAMS" || params.isEmpty() -> {
                this.run(content) { container: Container ->
                    result(container as T)
                }
            }
            "module-config.kts" in scriptFile -> {
                this.run(content, this.convertStringParamsToListParams(params)) { moduleProcess: ModuleProcess ->
                    result(moduleProcess as T)
                }
            }
            else -> {
                this.run(content, this.convertStringParamsToListParams(params)) { container: Container ->
                    result(container as T)
                }
            }
        }
    }

    private fun convertStringParamsToListParams(args: String): Map<String, Any> {
        if (args.isEmpty()) emptyMap<String, Any>()

        val params = mutableMapOf<String, Any>()

        args.split(",").forEach { arg ->
            val keyValue = arg.split(":")

            val key = keyValue[0]

            val value = keyValue[1]

            params[key] = value
        }

        return params
    }

    override fun <T> runScriptFiles(scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit) {
        scripts.forEach { (scriptPath, params) ->
            if (scriptPath.isNotEmpty()) {
                if (".kts" !in scriptPath) {
                    println("\n\u001B[31m The file should be an [kts] extension.\n")

                    exitProcess(7)
                }

                var finalInitPath = ""

                finalInitPath += if (isRelativeFile(scriptPath)) {
                    Paths.get("").toAbsolutePath().toString() + "/$scriptPath "
                } else {
                    scriptPath
                }.trim()

                val scriptContent = File(finalInitPath).readText(Charsets.UTF_8)

                val scriptName = File(finalInitPath).name

                if (params.isEmpty()) {
                    this.run(scriptContent) { container: Container ->
                        result(container as T, scriptName)
                    }

                    return@forEach
                }

                this.run(scriptContent, params) { container: Container ->
                    result(container as T, scriptName)
                }
            }
        }
    }

    override fun buildModule(moduleProcess: ModuleProcess) {
        val modulePath = File("${Paths.get("").toAbsolutePath()}/${moduleProcess.moduleName()}")

        if (modulePath.exists()) {
            println("The $ANSI_RED ${moduleProcess.moduleName()} already exist. $ANSI_RESET")

            return
        }

        println("\u001B[38;5;155mPreparing module This take a while...\u001B[0m")

        moduleProcess.build()

        println("\u001B[38;5;155mBuild done.\u001B[0m")
    }

    private fun changeFileContent(filePath: String, oldContent: String, newContent: String) {
        val file = File(filePath)

        val tempFile = createTempFile()

        tempFile.printWriter().use { writer ->
            file.forEachLine { line ->
                writer.println(when {
                    line.contains(oldContent) -> line.replace(oldContent, newContent)
                    else -> line
                })
            }
        }

        check(file.delete() && tempFile.renameTo(file)) { "failed to replace file." }
    }

    private fun convertToKtExtensionFor(name: String): String {
        return name.replace(".kts", ".kt")
    }

    override fun execute(callable: (container: Container, params: Map<String, Any>) -> Container, params: Map<String, String>) {
        callable(container, params)
    }

    fun availableServiceProviders(): List<KClass<*>> {
        return this.registeredServiceProviders
    }

    fun registerBuildInServicesProvidersInContainer(): Map<KClass<*>, Any> {
        this.registeredServiceProviders.forEach { provider ->
            ((provider).constructors.elementAt(0).call() as ServiceProvider).up()
        }

        return this.container.getBindings() as Map<KClass<*>, Any>
    }

    fun registerExternalServiceProviders(providers: List<ServiceProvider>) {
        providers.forEach { provider ->
            provider.up()
        }
    }

    private fun downloadFile(url: URL, targetFileName: String) {
        url.openStream().use { `in` -> Files.copy(`in`, Paths.get(targetFileName)) }
    }
}

fun main(args: Array<String>) {
    val processManager = createDefaultConfiguration()

    if (args.isNotEmpty() && args[0] == "UPDATING_CHECK") {
        checkForUpdates()
    } else {
        var params = ""

        if (args.size > 1) params = args[1]

        val scriptPath = args[0]

        processManager.runScriptFile(scriptPath, params) { result: Any ->
            processCallback(processManager, scriptPath, result)
        }
    }

    exitProcess(0)
}

fun checkForUpdates(): Boolean {
    val parser = app.createInstanceOf(TextParser::class)
    parser.readFromResource(".env")

    val properties = parser.splitKeyValue("=".toRegex())

    val checkForUpdateUrl = properties["CHECK_FOR_UPDATED_URL"]

    val httpClient = app.createInstanceOf(Client::class)

    val response = httpClient.get {
        url = checkForUpdateUrl!!
    }

    val apps = response?.asString()!!

    val textJsonParser = app.createInstanceOf(TextJsonParser::class) as JsonToObject<*>

    textJsonParser.load(apps)

    data class Versioning(val statusCode: String, val body: String)

    val versioning = textJsonParser.toType<Versioning>()

    textJsonParser.load(versioning.body)

    data class Project(val name: String, val version: String)

    data class Info(val apps: ArrayList<Project>)

    textJsonParser.toType<Info>().apps.forEach { project ->
        if ((project.name == "octopus" && project.version != properties["OCTOPUS_VERSION"]) ||
                (project.name == "koupper-installer" && project.version != properties["KOUPPER_CLI_VERSION"])) {
            print("AVAILABLE_UPDATES")

            exitProcess(0)
        }
    }

    return false
}

fun runScriptFileFromUrl(context: ProcessExecutionManager, url: String, args: String) {
    context.runScriptFileFromUrl(url, args) { result: Any ->
        processCallback(context, url, result)
    }
}

private fun processCallback(context: ProcessExecutionManager, scriptName: String, result: Any) {
    when (result) {
        is ScriptProcess -> {
            val listScripts = result.listScriptsToExecute()

            context.runScriptFiles(listScripts) { _: Container, nameScript: String ->
                println("script [$nameScript] ->\u001B[38;5;155m executed.\u001B[0m")
            }
        }
        is Container -> {
            println("\nscript [$scriptName] ->\u001B[38;5;155m executed.\u001B[0m")
        }
        is ModuleProcess -> {
            context.buildModule(result)
        }
    }
}

fun createDefaultConfiguration(): ProcessExecutionManager {
    val container = app

    val octopus = Octopus(container)
    octopus.registerBuildInServicesProvidersInContainer()

    return octopus
}
