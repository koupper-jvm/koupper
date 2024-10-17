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

    companion object {
        inline fun build(location: String, block: Builder.() -> Unit) =
            Builder(location).apply(block).build().build()
    }

    private constructor(builder: Builder) : this(
        "${builder.location}/src/main/kotlin/${builder.packageName.replace(".", "/")}/controllers/RequestHandlerController.kt",
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

        this.finalCustomController.append(genericParameters).appendLine()
    }

    override fun addMethodBody(method: Method) {
        super.textFileHandler.using(this.location)

        val methodBody = super.textFileHandler.getContentBetweenContent("): Response {", "} // EOM")

        val updatedMethodBody = methodBody.mapIndexed { index, line ->
            var updatedLine = line

            if (line.contains("RequestHandler()")) {
                val handler = "RequestHandler${method.script.replaceFirstChar { it.uppercaseChar() }}"

                updatedLine = line.replace(
                    "RequestHandler()",
                    "RequestHandler${handler.replaceFirstChar { it.uppercaseChar() }}()"
                )
            }

            updatedLine
        }

        val finalMethodBody = updatedMethodBody.joinToString(separator = "\n")

        this.finalCustomController.append(finalMethodBody).appendLine()
    }

    override fun addMethodClosing(responseClass: KClass<*>) {
        this.finalCustomController.append("\n${super.spaces}): ${responseClass.simpleName!!} {").appendLine()
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
