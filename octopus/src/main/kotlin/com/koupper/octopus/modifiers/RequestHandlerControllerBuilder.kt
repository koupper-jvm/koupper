package com.koupper.octopus.modifiers

import com.koupper.octopus.modules.isPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.KClass

class RequestHandlerControllerBuilder(
    private val context: String,
    private val modelProject: String,
    private val rootLocation: String,
    path: String,
    controllerConsumes: List<String> = emptyList(),
    controllerProduces: List<String> = emptyList(),
    controllerName: String,
    packageName: String,
    methods: MutableList<Method> = mutableListOf(),
    registeredScripts: MutableMap<String, Pair<List<String>, String>> = mutableMapOf()
) : JerseyControllerBuilder(
    path,
    controllerConsumes,
    controllerProduces,
    controllerName,
    packageName,
    methods,
    registeredScripts
) {
    private lateinit var pathParams: List<MatchResult>

    companion object {
        inline fun build(block: Builder.() -> Unit) =
            Builder().apply(block).build().build()
    }

    private constructor(builder: Builder) : this(
        builder.context,
        builder.modelProject,
        builder.rootLocation,
        builder.path,
        builder.controllerConsumes,
        builder.controllerProduces,
        builder.controllerName,
        builder.packageName,
        builder.methods,
        builder.registeredScripts
    ) {
        super.baseControllerLocation = builder.rootLocation
    }

    override fun build() {
        super.build()

        val classDeclaration = this.textFileHandler.getNumberLineFor("class ${this.controllerName} {")

        this.textFileHandler.putLineAfter(classDeclaration, getRequestProperties(), overrideOriginal = true)

        val destinationPath = Paths.get(
            "${context + File.separator + modelProject}/src/main/kotlin/${
                this.packageName.replace(".", File.separator)
            }/controllers"
        )

        Files.createDirectories(destinationPath)

        Files.move(
            Paths.get(this.baseControllerLocation),
            destinationPath.resolve("${this.controllerName}.kt")
        )
    }

    private fun getRequestProperties(): String {
        val requestProperties = StringBuilder()

        val contextAt = "${spaces}@Context"
        val uriInfoSentence = "${spaces}private lateinit var uriInfo: UriInfo"
        val headersSentence = "${spaces}private lateinit var inputHeaders: HttpHeaders"

        return requestProperties.append(contextAt).appendLine().append(uriInfoSentence).appendLine().append(contextAt)
            .appendLine().append(headersSentence).appendLine().toString()
    }

    override fun addMethodParameters(method: Method) {
        if (method.action == Action.POST || method.action == Action.PUT) {
            this.finalCustomController.append("${spaces}${spaces}bodyJson: String")
        }

        if (this.finalCustomController.endsWith(", \n")) {
            this.finalCustomController.setLength(this.finalCustomController.length - 3)
        }
    }

    override fun addMethodClosing() {
        this.finalCustomController.append("\n${spaces}): APIGatewayProxyResponseEvent {").appendLine()
    }

    override fun addMethodBody(method: Method) {
        val body = this.textFileHandler.getContentBetweenContent("val apiGatewayProxyRequestEvent", "return {{REQUEST_HANDLER_VARIABLE_NAME}}.handleRequest", inclusiveMode = true)
        var requestEvent = body.joinToString(separator = "\n").replace("\"POST\"", "\"${method.action.name.uppercase()}\"")

        if (method.action != Action.POST || method.action == Action.PUT) {
            requestEvent = requestEvent.replace("body = bodyJson", "body = null")
        }

        val script = this.registeredScripts[method.script]
        val scriptReturnType = script?.second ?: ""
        val handlerName = "requestHandler${method.script.replaceFirstChar { it.uppercaseChar() }}"

        if (scriptReturnType == "Unit" || isPrimitive(scriptReturnType)) {
            requestEvent = replaceRequestHandler(requestEvent, handlerName)
        }

        this.finalCustomController.append(requestEvent)
    }

    private fun replaceRequestHandler(content: String, handlerName: String): String {
        return content.replace("{{REQUEST_HANDLER_VARIABLE_NAME}}", handlerName)
            .replace("{{REQUEST_HANDLER_NAME}}", handlerName.replaceFirstChar { it.uppercaseChar() })
    }


    override fun addMethodClosing(responseClass: KClass<*>?) {
        if (responseClass != null) {
            this.finalCustomController.append("\n${super.spaces}): ${responseClass.simpleName!!} {").appendLine()
        }
    }

    class Builder {
        var context = ""
        var modelProject = ""
        var rootLocation = "model-project/src/main/kotlin/io/mp/controllers/RequestHandlerController.kt"
        var path: String = Property.UNDEFINED.name
        var controllerConsumes: List<String> = emptyList()
        var controllerProduces: List<String> = emptyList()
        var controllerName: String = Property.UNDEFINED.name
        var packageName: String = Property.UNDEFINED.name
        var methods: MutableList<Method> = mutableListOf()
        var registeredScripts: MutableMap<String, Pair<List<String>, String>> = mutableMapOf()

        fun build() = RequestHandlerControllerBuilder(this)
    }
}
