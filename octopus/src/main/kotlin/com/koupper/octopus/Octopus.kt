package com.koupper.octopus

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.exceptions.InvalidScriptException
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.extensions.splitKeyValue
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import javax.script.ScriptEngineManager
import kotlin.reflect.KClass
import kotlin.system.exitProcess

val isSingleFileName: (String) -> Boolean = {
    it.contains("^[a-zA-Z0-9]+.kts$".toRegex())
}

class Octopus(private var container: Container, private var config: Config) : ProcessManager {
    private var registeredServiceProviders: List<KClass<*>> = ServiceProviderManager().listProviders()
    private val userHomePath = System.getProperty("user.home")
    //private val NULL_FILE = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")

    override fun <T> run(sentence: String, result: (value: T) -> Unit) {
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

                    val targetCallback = eval(valName) as (Container) -> T

                    result(targetCallback.invoke(container) as T)
                }
                isConFigType(sentence) -> {
                    eval(sentence)

                    val endOfVariableNameInSentence = sentence.indexOf(":")

                    val startOfSentence = sentence.indexOf("val")

                    val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

                    val targetCallback = eval(valName) as (ScriptManager) -> ScriptManager

                    result(targetCallback.invoke(config) as T)
                }
                isParameterized(sentence) -> {
                    run(sentence, emptyMap()) { container: Container ->
                        result(container as T)
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

    override fun <T> run(sentence: String, params: Map<String, Any>, result: (value: T) -> Unit) {
        with(ScriptEngineManager().getEngineByExtension("kts")) {
            if (!isValidSentence(sentence)) {
                throw InvalidScriptException("The script is invalid.")
            }

            eval(sentence)

            val endOfVariableNameInSentence = sentence.indexOf(":")

            val startOfSentence = sentence.indexOf("val")

            val valName = sentence.substring(startOfSentence + "al".length + 1, endOfVariableNameInSentence).trim()

            val targetCallback = eval(valName) as (Container, Map<String, Any>) -> T

            result(targetCallback.invoke(container, params))
        }
    }

    override fun <T> runScriptFile(scriptPath: String, args: String, result: (value: T) -> Unit) {
        val content = File(scriptPath).readText(Charsets.UTF_8)

        this.runByType(scriptPath, args, content, result)
    }

    override fun <T> runScriptFileFromUrl(scriptUrl: String, args: String, result: (value: T) -> Unit) {
        val content = URL(scriptUrl).readText()

        this.runByType(scriptUrl, args, content, result)
    }

    private fun <T> runByType(scriptFile: String, args: String, content: String, result: (value: T) -> Unit) {
        if ("init.kts" in scriptFile) {
            this.run(content) { scriptManager: ScriptManager ->
                result(scriptManager as T)
            }

            return
        }

        if (args == "EMPTY_PARAMS" || args.isEmpty()) {
            this.run(content) { container: Container ->
                result(container as T)
            }

            return
        }

        this.run(content, this.buildParams(args)) { container: Container ->
            result(container as T)
        }
    }

    private fun buildParams(args: String): Map<String, Any> {
        if (args.isEmpty()) emptyMap<String, Any>()

        val params = mutableMapOf<String, Any>()

        val input = args.split(",").forEach { arg ->
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

                finalInitPath += if (isSingleFileName(scriptPath)) {
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

    override fun buildFrom(scriptManager: ScriptManager) {
        println("\u001B[38;5;155mPreparing deployable application This take a while...\u001B[0m")

        val parser = this.container.createInstanceOf(TextParser::class, "TextParserEnvPropertiesTemplate")
        parser.readFromResource(".env")
        val properties = parser.splitKeyValue("=".toRegex())

        val modelProjectUrl = properties["MODEL_PROJECT_URL"]

        downloadFile(
                URL(modelProjectUrl),
                "${Paths.get("").toAbsolutePath()}/unnamed.zip"
        )

        ProcessBuilder()
                .command("unzip", "-qq", "${Paths.get("").toAbsolutePath()}/unnamed.zip")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor()

        File("${Paths.get("").toAbsolutePath()}/unnamed.zip").delete()

        println("Locating scripts...")

        File("${Paths.get("").toAbsolutePath()}/unnamed").renameTo(File(scriptManager.deployableName()))

        this.changeFileContent(
                "${Paths.get("").toAbsolutePath()}/${scriptManager.deployableName()}/settings.gradle",
                "rootProject.name = 'unnamed'",
                "rootProject.name = '${scriptManager.deployableName()}'"
        )

        scriptManager.listScriptsToExecute().forEach {
            print("${it.key} ...")

            this.locateScript(
                    it.key,
                    "${Paths.get("").toAbsolutePath()}/${scriptManager.deployableName()}/src/main/kotlin/scripts/${this.convertToKtExtensionFor(it.key)}"
            )

            println("\u001B[38;5;155m[ok]\u001B[0m")
        }

        val processManager = properties["OPTIMIZED_PROCESS_MANAGER"]

        print("\u001B[38;5;155mRequesting an optimized process manager... \u001B[0m")

        downloadFile(
                URL(processManager),
                "${Paths.get("").toAbsolutePath()}/${scriptManager.deployableName()}/libs/octopus-1.0.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mProcess Manager located.\u001B[0m")

        println("\u001B[38;5;155mScripts located.\u001B[0m")

        println("\u001B[38;5;155mBuilding done.\u001B[0m")
    }

    private fun locateScript(scriptPath: String, destinationPath: String) {
        val file = File(scriptPath)

        val tempFile = createTempFile()

        tempFile.printWriter().use { writer ->
            var lineNumber = 0

            file.forEachLine { line ->
                if (lineNumber == 0) {
                    writer.println("package scripts\n\n$line")
                } else {
                    writer.println(line)
                }

                lineNumber++
            }
        }

        tempFile.renameTo(File(destinationPath))
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
}

fun main(args: Array<String>) {
    if (args.isEmpty()) print("No parameters provided.")

    val octopus = createDefaultConfiguration()

    var params = ""

    if (args.size > 1) params = args[1]

    octopus.runScriptFile(args[0], params) { result: Any ->
        executeCallback(octopus, args[0], result)
    }
}

fun runScriptFileFromUrl(context: ProcessManager, url: String, args: String) {
    context.runScriptFileFromUrl(url, args) { result: Any ->
        executeCallback(context, url, result)
    }
}

private fun executeCallback(context: ProcessManager, scriptName: String, result: Any) {
    if (result is ScriptManager) {
        val listScripts = result.listScriptsToExecute()

        if (result.deployType() == DeploymentType.NONE) {
            context.runScriptFiles(listScripts) { _: Container, nameScript: String ->
                println("script [$nameScript] ->\u001B[38;5;155m executed.\u001B[0m")
            }

            return
        }

        context.buildFrom(result)
    } else if (result is Container) {
        println("\nscript [$scriptName] ->\u001B[38;5;155m executed.\u001B[0m")
    }
}

fun createDefaultConfiguration(): ProcessManager {
    val containerImplementation = app

    val octopus = Octopus(containerImplementation, Config())

    octopus.registerBuildInServicesProvidersInContainer()

    return octopus
}

fun downloadFile(url: URL, targetFileName: String) {
    url.openStream().use { `in` -> Files.copy(`in`, Paths.get(targetFileName)) }
}
