package com.koupper.octopus.modifiers

import com.koupper.octopus.toCamelCase
import com.koupper.providers.files.TextFileHandlerImpl
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.internal.impl.resolve.calls.inference.CapturedType

enum class Action {
    POST,
    GET,
    PUT,
    DELETE
}

enum class Property {
    UNDEFINED
}

data class Method(
    var name: String,
    var action: Action,
    var path: String,
    var consumes: List<String>,
    var produces: List<String>,
    var queryParams: Map<String, KClass<*>> = emptyMap(),
    var matrixParams: Map<String, KClass<*>> = emptyMap(),
    var headerParams: Map<String, KClass<*>> = emptyMap(),
    var cookieParams: Map<String, KClass<*>> = emptyMap(),
    var formParams: Map<String, KClass<*>> = emptyMap(),
    var response: KClass<*>?,
    var script: String,
    var body: KClass<*>? = null
)

open class JerseyControllerBuilder protected constructor(
    protected val path: String,
    protected val controllerConsumes: List<String> = emptyList(),
    protected val controllerProduces: List<String> = emptyList(),
    protected val controllerName: String = Property.UNDEFINED.name,
    protected val packageName: String = Property.UNDEFINED.name,
    protected val methods: MutableList<Method> = mutableListOf(),
    protected var registeredScripts: MutableMap<String, Pair<List<String>, String>> = mutableMapOf()
) {
    protected val textFileHandler = TextFileHandlerImpl()
    protected val finalCustomController = StringBuilder()
    protected val spaces = String.format("%-4s", " ")
    protected var baseControllerLocation = "model-project/src/main/kotlin/io/mp/controllers/ModelController.kt"

    private constructor(builder: Builder) : this(
        builder.path,
        builder.controllerConsumes,
        builder.controllerProduces,
        builder.controllerName,
        builder.packageName,
        builder.methods
    )

    companion object {
        inline fun build(location: String, block: Builder.() -> Unit) =
            Builder(location).apply(block).build()
    }

    open fun build() {
        this.textFileHandler.using(this.baseControllerLocation)
        this.addPackage()
        this.addImports()
        this.addControllerPath()

        if (this.controllerProduces.isNotEmpty()) {
            this.addControllerProduces()
        }

        if (this.controllerConsumes.isNotEmpty()) {
            this.addControllerConsumes()
        }

        this.addClassDefinition()

        this.methods.forEach {
            this.addMethodAction(it.action)

            this.addMethodPath(it.path)

            if (it.consumes.isNotEmpty()) {
                this.addConsumesPath(it.consumes)
            }

            if (it.produces.isNotEmpty()) {
                this.addMethodProduces(it.produces)
            }

            this.addMethodOpening(it.name)

            this.addMethodParameters(it)

            if (it.response == null) {
                this.addMethodClosing()
            } else {
                this.addMethodClosing(it.response)
            }

            this.addMethodBody(it)

            this.finalCustomController.append("\n${spaces}}").appendLine().appendLine()

            this.addDataClasses(it)
        }

        this.finalCustomController.append("}").appendLine()

        val streamBuilder = buildString {
            append(finalCustomController)
        }

        val fileWriter = FileWriter(this.baseControllerLocation)

        val bufferedWriter = BufferedWriter(fileWriter)
        bufferedWriter.write(streamBuilder)
        bufferedWriter.close()

        File("model-project/src/main/kotlin/${this.packageName.replace(".", File.separator)}/controllers").mkdir()
    }

    protected open fun addPackage() {
        this.finalCustomController.append("package ${packageName}.controllers").appendLine("\n")
    }

    protected open fun addImports() {
        val importsFromTargetController = StringBuilder()

        this.methods.forEach { item ->
            val scriptImport = "import ${packageName}.extensions.${this.changeScriptName(item.script)}"

            if (!importsFromTargetController.contains(scriptImport)) {
                importsFromTargetController.append(scriptImport).appendLine()
            }
        }

        if (importsFromTargetController.isNotEmpty()) {
            importsFromTargetController.deleteCharAt(importsFromTargetController.length - 1)
        }

        val imports = this.textFileHandler.getNumberLinesFor("import")

        imports.forEach {
            var lineContent = this.textFileHandler.getContentForLine(it)

            if (lineContent == "import $packageName.extensions.script" && importsFromTargetController.isNotEmpty()) {
                lineContent =
                    lineContent.replace("import $packageName.extensions.script", importsFromTargetController.toString())
            }

            this.finalCustomController.append(lineContent).appendLine()
        }

        this.finalCustomController.appendLine()
    }

    protected open fun changeScriptName(scriptName: String): String {
        return if (scriptName.contains("-")) scriptName.substring(0, scriptName.indexOf("-")) + scriptName.substring(
            scriptName.indexOf("-") + 1
        ).replaceFirstChar { it.uppercase() } else scriptName
    }

    protected open fun addControllerPath() {
        this.finalCustomController.append(if (this.path.isNotEmpty()) "@Path(\"${this.path}\")" else "@Path")
            .appendLine()
    }

    protected open fun addControllerProduces() {
        val produces = StringBuilder()

        this.controllerProduces.forEachIndexed { index, produce ->
            if (index == this.controllerProduces.size - 1) {
                produces.append("\"${produce}\"")
            } else {
                produces.append("\"${produce}\", ")
            }
        }

        this.finalCustomController.append("@Produces($produces)").appendLine()
    }

    protected open fun addControllerConsumes() {
        val consumes = StringBuilder()

        this.controllerConsumes.forEachIndexed { index, consume ->
            if (index == this.controllerConsumes.size - 1) {
                consumes.append("\"${consume}\"")
            } else {
                consumes.append("\"${consume}\", ")
            }
        }

        this.finalCustomController.append("@Consumes($consumes)").appendLine()
    }

    protected open fun addClassDefinition() {
        val classDeclaration = "class ${this.controllerName} {"

        this.finalCustomController.append(classDeclaration).appendLine()
    }

    protected open fun addMethodAction(method: Action) {
        if (method == Action.POST) {
            this.finalCustomController.append("$spaces@POST").appendLine()
        }

        if (method == Action.PUT) {
            this.finalCustomController.append("$spaces@PUT").appendLine()
        }

        if (method == Action.GET) {
            this.finalCustomController.append("$spaces@GET").appendLine()
        }

        if (method == Action.DELETE) {
            this.finalCustomController.append("$spaces@DELETE").appendLine()
        }
    }

    protected open fun addMethodPath(path: String) {
        this.finalCustomController.append("$spaces@Path(\"$path\")").appendLine()
    }

    protected open fun addConsumesPath(consumes: List<String>) {
        this.finalCustomController.append("$spaces@Consumes(${this.buildMethodAnnotationParameters(consumes)})")
            .appendLine()
    }

    protected open fun addMethodProduces(produces: List<String>) {
        this.finalCustomController.append("$spaces@Produces(${this.buildMethodAnnotationParameters(produces)})")
            .appendLine()
    }

    protected open fun buildMethodAnnotationParameters(parameters: List<String>): StringBuilder {
        val finalParameters = StringBuilder()

        parameters.forEachIndexed { index, parameter ->
            if (index == parameters.size - 1) {
                finalParameters.append("\"${parameter}\"")
            } else {
                finalParameters.append("\"${parameter}\", ")
            }
        }

        return finalParameters
    }

    protected open fun addMethodOpening(name: String) {
        this.finalCustomController.append("${spaces}fun $name(").appendLine()
    }

    protected open fun addMethodParameters(method: Method) {
        if (this.isElegibleForBeanCreation(method)) {
            this.finalCustomController.append("$spaces$spaces@BeanParam inputBean: ${this.getDataClassName()}, ")
                .appendLine()
        } else {
            val pathParams = "\\{\\w+}".toRegex().findAll(method.path).toList()

            if (pathParams.isNotEmpty()) {
                this.buildPathParams(pathParams)
            }

            if (method.queryParams.isNotEmpty()) {
                this.buildQueryParams(method.queryParams)
            }

            if (method.matrixParams.isNotEmpty()) {
                this.buildMatrixParams(method.matrixParams)
            }

            if (method.headerParams.isNotEmpty()) {
                this.buildHeaderParams(method.headerParams)
            }

            if (method.cookieParams.isNotEmpty()) {
                this.buildCookieParams(method.cookieParams)
            }

            if (method.formParams.isNotEmpty()) {
                this.buildFormParams(method.formParams)
            }
        }

        if (method.action == Action.POST || method.action == Action.PUT) {
            this.finalCustomController.append("${spaces}${spaces}${(method.body as KClass<*>).simpleName!!.toCamelCase()}: ${(method.body as KClass<*>).simpleName}")
                .appendLine()
        }

        if (this.finalCustomController.endsWith(", \n")) {
            this.finalCustomController.setLength(this.finalCustomController.length - 3)
        }
    }

    protected open fun isElegibleForBeanCreation(method: Method): Boolean {
        return method.queryParams.isNotEmpty() &&
                method.matrixParams.isNotEmpty() &&
                method.headerParams.isNotEmpty() &&
                method.cookieParams.isNotEmpty() &&
                method.formParams.isNotEmpty() &&
                "\\{\\w+}".toRegex().findAll(method.path).toList().isNotEmpty()
    }

    protected open fun buildPathParams(pathParams: List<MatchResult>) {
        pathParams.forEach { pathParam ->
            val param = pathParam.value.replace("{", "").replace("}", "")

            this.finalCustomController.append("$spaces$spaces@PathParam(\"${param}\") ${param}: String, ").appendLine()
        }
    }

    protected open fun buildQueryParams(queryParams: Map<String, KClass<*>>) {
        queryParams.forEach { queryParam ->
            this.finalCustomController.append("$spaces$spaces@QueryParam(\"${queryParam.key}\") ${queryParam.key}: ${queryParam.value.simpleName}, ")
                .appendLine()
        }
    }

    protected open fun buildMatrixParams(matrixParams: Map<String, KClass<*>>) {
        matrixParams.forEach { matrixParam ->
            this.finalCustomController.append("$spaces$spaces@MatrixParam(\"${matrixParam.key}\") ${matrixParam.key}: ${matrixParam.value.simpleName}, ")
                .appendLine()
        }
    }

    protected open fun buildHeaderParams(headerParams: Map<String, KClass<*>>) {
        headerParams.forEach { headerParam ->
            this.finalCustomController.append("$spaces$spaces@HeaderParam(\"${headerParam.key}\") ${headerParam.key}: ${headerParam.value.simpleName}, ")
                .appendLine()
        }
    }

    protected open fun buildCookieParams(cookieParams: Map<String, KClass<*>>) {
        cookieParams.forEach { cookieParam ->
            this.finalCustomController.append("$spaces$spaces@CookieParam(\"${cookieParam.key}\") ${cookieParam.key}: ${cookieParam.value.simpleName}, ")
                .appendLine()
        }
    }

    protected open fun buildFormParams(formParams: Map<String, KClass<*>>) {
        formParams.forEach { formParam ->
            this.finalCustomController.append("$spaces$spaces@FormParam(\"${formParam.key}\") ${formParam.key}: ${formParam.value.simpleName}, ")
                .appendLine()
        }
    }

    protected open fun addMethodClosing() {
        this.finalCustomController.append("\n${spaces}): Any {").appendLine()
    }

    protected open fun addMethodClosing(responseClass: KClass<*>?) {
        this.finalCustomController.append("\n${spaces}): Any {".replace("Any", responseClass!!.simpleName!!))
            .appendLine()
    }

    protected open fun addMethodBody(method: Method) {
        val returnStructure = StringBuilder()

        if (this.isElegibleForBeanCreation(method)) {
            returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"inputBean\" to inputBean, ").appendLine()
        } else {
            method.queryParams.forEach {
                returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${it.key}\" to ${it.key}, ").appendLine()
            }
            method.matrixParams.forEach {
                returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${it.key}\" to ${it.key}, ").appendLine()
            }
            method.headerParams.forEach {
                returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${it.key}\" to ${it.key}, ").appendLine()
            }
            method.cookieParams.forEach {
                returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${it.key}\" to ${it.key}, ").appendLine()
            }
            method.formParams.forEach {
                returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${it.key}\" to ${it.key}, ").appendLine()
            }

            "\\{\\w+}".toRegex().findAll(method.path).toList().forEach {
                returnStructure.append(
                    "${String.format("%-4s", " ")}\"${it.value.replace("{", "").replace("}", "")}\" to ${
                        it.value.replace("{", "").replace("}", "")
                    }, "
                ).appendLine()
            }
        }

        if (method.body != null) {
            returnStructure.append("${spaces}${spaces}${spaces}${spaces}\"${(method.body as KClass<*>).simpleName!!.toCamelCase()}\" to ${(method.body as KClass<*>).simpleName!!.toCamelCase()}, ")
                .appendLine()
        }

        if (returnStructure.endsWith(", \n")) {
            returnStructure.setLength(returnStructure.length - 3)
        }

        this.finalCustomController.append(
            "${spaces}${spaces}return executor.call(\n${spaces}${spaces}${spaces}${
                this.changeScriptName(
                    method.script
                )
            }, \n${spaces}${spaces}${spaces}mapOf(\n$returnStructure\n${spaces}${spaces}${spaces})\n${spaces}${spaces})"
        )
    }

    protected open fun addDataClasses(method: Method) {
        if (this.isElegibleForBeanCreation(method)) {
            this.locateDataClass(this.buildBeanDataClass(method))
        }

        if (method.body != null && method.body!!.isData && !this.finalCustomController.contains("data class ${method.body!!.simpleName!!}")) {
            this.locateDataClass(this.buildDataClass(method.body!!))
        }

        if (method.response != null && method.response!!.isData && !this.finalCustomController.contains("data class ${method.response!!.simpleName}")) {
            this.locateDataClass(this.buildDataClass(method.response!!))
        }
    }

    protected open fun buildDataClass(dataClass: KClass<*>): StringBuilder {
        val dataClassParams = StringBuilder()

        dataClass.memberProperties.forEach {
            val type = it.returnType.toString()

            dataClassParams.append("val ${it.name}: ${(type.substring(type.indexOf(".") + 1))}, ")
        }

        dataClassParams.setLength(dataClassParams.length - 2)

        return StringBuilder("data class ${dataClass.simpleName!!}(${dataClassParams})").appendLine()
    }

    protected open fun buildBeanDataClass(method: Method): StringBuilder {
        val pathParams = "\\{\\w+}".toRegex().findAll(method.path).toList()

        val inputs = StringBuilder()

        pathParams.forEach { pathParam ->
            val param = pathParam.value.replace("{", "").replace("}", "")

            inputs.append("@PathParam(\"${param}\") ${param}: String, ").appendLine()
        }

        method.queryParams.forEach { header ->
            inputs.append("$spaces@QueryParam(\"${header.key}\") ${header.key}: ${header.value.simpleName}, ")
                .appendLine()
        }

        method.matrixParams.forEach { header ->
            inputs.append("$spaces@MatrixParam(\"${header.key}\") ${header.key}: ${header.value.simpleName}, ")
                .appendLine()
        }

        method.headerParams.forEach { header ->
            inputs.append("$spaces@HeaderParam(\"${header.key}\") ${header.key}: ${header.value.simpleName}, ")
                .appendLine()
        }

        method.cookieParams.forEach { header ->
            inputs.append("$spaces@CookieParam(\"${header.key}\") ${header.key}: ${header.value.simpleName}, ")
                .appendLine()
        }

        if (method.formParams.size > 3) {
            method.formParams.forEach { header ->
                inputs.append("MultivaluedMap<String, String> formParams, ").appendLine()
            }
        } else {
            method.formParams.forEach { header ->
                inputs.append("$spaces@FormParam(\"${header.key}\") ${header.key}: ${header.value.simpleName}, ")
                    .appendLine()
            }
        }

        return StringBuilder("data class ${this.getDataClassName()}($inputs)").appendLine()
    }

    protected open fun getDataClassName(): String {
        var dataClassName = "InputBean"

        while (this.finalCustomController.contains("data class $dataClassName")) {
            var secuence = 0

            secuence++

            dataClassName += secuence
        }

        return dataClassName
    }

    protected open fun locateDataClass(dataClassBlock: StringBuilder) {
        this.finalCustomController.insert(
            this.finalCustomController.indexOf("@Path(\"${this.path}\")") - 1,
            "\n$dataClassBlock"
        )
    }

    class Builder(var location: String) {
        var path: String = Property.UNDEFINED.name
        var controllerConsumes: List<String> = emptyList()
        var controllerProduces: List<String> = emptyList()
        var controllerName: String = Property.UNDEFINED.name
        var packageName: String = Property.UNDEFINED.name
        var methods: MutableList<Method> = mutableListOf()

        fun build() = JerseyControllerBuilder(this)
    }
}
