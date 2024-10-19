package com.koupper.octopus.modifiers

import kotlin.reflect.KClass

class RequestHandlerControllerBuilder(
    location: String,
    path: String,
    controllerConsumes: List<String> = emptyList(),
    controllerProduces: List<String> = emptyList(),
    controllerName: String,
    packageName: String,
    methods: MutableList<Method> = mutableListOf()
) : JerseyControllerBuilder(
    location,
    path,
    controllerConsumes,
    controllerProduces,
    controllerName,
    packageName,
    methods
) {
    private lateinit var pathParams: List<MatchResult>

    companion object {
        inline fun build(location: String, block: Builder.() -> Unit) =
            Builder(location).apply(block).build().build()
    }

    private constructor(builder: Builder) : this(
        "${builder.location}/src/main/kotlin/io/mp/controllers/RequestHandlerController.kt",
        builder.path,
        builder.controllerConsumes,
        builder.controllerProduces,
        builder.controllerName,
        builder.packageName,
        builder.methods
    )

    override fun addMethodParameters(method: Method) {
        val genericParameters = "        @Context uriInfo: UriInfo,\n" +
                "        @Context request: HttpServletRequest"

        this.pathParams = "\\{\\w+}".toRegex().findAll(method.path).toList()

        if (pathParams.isNotEmpty()) {
            this.buildPathParams(pathParams)
        }

        this.finalCustomController.append(genericParameters)

        if (this.finalCustomController.endsWith(", \n")) {
            this.finalCustomController.setLength(this.finalCustomController.length - 3)
        }
    }

    override fun addMethodBody(method: Method) {
        super.textFileHandler.using(this.location)

        var methodBody = super.textFileHandler.getContentBetweenContent("): Response {", "} // EOM").joinToString(separator = "\n")

        val pathParamRegex = Regex("""val pathParam = mapOf\([\s\S]*?uriInfo.pathParameters.getFirst\("[\w-]+"\)[\s\S]*?\)""")

        if (pathParamRegex.containsMatchIn(methodBody)) {
            if (this.pathParams.isEmpty()) {
                methodBody = methodBody.replace(pathParamRegex, "val pathParam = emptyMap<String, String>()")
            } else {
                val pathParamsString = this.pathParams.joinToString(separator = ",\n") { pathParam ->
                    String.format("%-12s", " ") + "\"${pathParam.value.replace("{", "").replace("}", "")}\" to ${pathParam.value.replace("{", "").replace("}", "")}"
                }

                methodBody = methodBody.replace(
                    pathParamRegex,
                    "val pathParam = mapOf(\n$pathParamsString\n${String.format("%-8s", " ")})"
                )
            }
        }

        if (methodBody.contains("RequestHandler()")) {
            val handler = "RequestHandler${method.script.replaceFirstChar { it.uppercaseChar() }}"
            methodBody = methodBody.replace("RequestHandler()", "${handler.replaceFirstChar { it.uppercaseChar() }}()")
        }

        this.finalCustomController.append(methodBody)
    }

    override fun addMethodClosing(responseClass: KClass<*>?) {
        if (responseClass != null) {
            this.finalCustomController.append("\n${super.spaces}): ${responseClass.simpleName!!} {").appendLine()
        }
    }

    class Builder(var location: String) {
        var path: String = Property.UNDEFINED.name
        var controllerConsumes: List<String> = emptyList()
        var controllerProduces: List<String> = emptyList()
        var controllerName: String = Property.UNDEFINED.name
        var packageName: String = Property.UNDEFINED.name
        var methods: MutableList<Method> = mutableListOf()

        fun build() = RequestHandlerControllerBuilder(this)
    }
}
