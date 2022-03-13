package com.koupper.providers.controllers

import com.koupper.os.env
import com.koupper.providers.files.TextFileHandlerImpl
import io.zeko.db.sql.utilities.toCamelCase
import java.io.File
import java.lang.Exception
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

enum class Type {
    JERSEY,
    KTOR
}

sealed interface RouteDefinition {
    fun type(type: () -> Type)
    fun path(path: () -> String)
    fun controllerName(controllerName: () -> String)
    fun produces(produces: () -> List<String>)
    fun consumes(consumes: () -> List<String>)
    fun post(route: Post.() -> Unit)
    fun get(route: Get.() -> Unit)
    fun put(route: Put.() -> Unit)
    fun delete(route: Delete.() -> Unit)
    fun registerRouters(route: RouteDefinition.() -> Unit)
    fun build(config: Map<String, Any>): Boolean
    fun deployOn(port: Int)
    fun stop()
}

open class Route : RouteDefinition {
    private var path: String = "/"
    private var type: Type = Type.JERSEY
    private lateinit var controllerName: String
    private var produces: List<String> = emptyList()
    private var consumes: List<String> = emptyList()
    lateinit var name: String
    var middlewares: List<String> = emptyList()
    var queryParams: Map<String, KClass<*>> = emptyMap()
    var matrixParams: Map<String, KClass<*>> = emptyMap()
    var headerParams: Map<String, KClass<*>> = emptyMap()
    var cookieParams: Map<String, KClass<*>> = emptyMap()
    var formParams: Map<String, KClass<*>> = emptyMap()
    lateinit var response: Any
    lateinit var script: String
    private var postMethods: MutableList<Any> = mutableListOf()
    private var getMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var updateMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var deleteMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var textFileHandler: TextFileHandlerImpl = TextFileHandlerImpl()

    override fun type(type: () -> Type) {
        this.type = type()
    }

    override fun path(path: () -> String) {
        this.path = path()
    }

    override fun controllerName(controllerName: () -> String) {
        this.controllerName = controllerName()
    }

    override fun produces(produces: () -> List<String>) {
        this.produces = produces()
    }

    override fun consumes(consumes: () -> List<String>) {
        this.produces = consumes()
    }

    override fun post(route: Post.() -> Unit) {
        val post = Post().apply(route)

        this.postMethods.add(post)
    }

    override fun get(route: Get.() -> Unit) {
        Get().apply(route)
    }

    override fun put(route: Put.() -> Unit) {
        Put().apply(route)
    }

    override fun delete(route: Delete.() -> Unit) {
        Delete().apply(route)
    }

    override fun registerRouters(route: RouteDefinition.() -> Unit) {
        route()
    }

    override fun build(config: Map<String, Any>): Boolean {
        val modelProject = File(env("MODEL_BACK_PROJECT_URL"))

        File("${modelProject.name}.zip").delete()

        this.setRootProjectNameFor(modelProject, config["name"] as String)

        this.setVersionFor(modelProject, config["moduleVersion"] as String)

        this.modelController(modelProject)

        return true
    }

    override fun deployOn(port: Int) {
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    private fun setVersionFor(modelProject: File, version: String) {
        this.textFileHandler.using("${modelProject.path}/build.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '{VERSION}'"),
            "version = '${version}'",
            overrideOriginal = true
        )
    }

    private fun setRootProjectNameFor(modelProject: File, name: String) {
        this.textFileHandler.using("${modelProject.path}/settings.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${name}'",
            overrideOriginal = true
        )
    }

    private fun modelController(modelProject: File) {
        this.textFileHandler.using("${modelProject.path}/src/main/kotlin/controllers/ModelController.kt")

        val packageDefinition = this.textFileHandler.getContentForLine(
            this.textFileHandler.getNumberLineFor("package controllers")
        )
        val importsDefinitions = this.textFileHandler.getNumberLinesFor("import").map {
            textFileHandler.getContentForLine(it)
        }
        val classDefinitionParts = this.textFileHandler.getContentBetweenContent("@Path", "{", inclusiveMode = true)
        val postMethodStructure = this.textFileHandler.getContentBetweenContent("@POST", "    }", inclusiveMode = true)
        val getMethodStructure = this.textFileHandler.getContentBetweenContent("@GET", "    }", inclusiveMode = true)
        val putMethodStructure = this.textFileHandler.getContentBetweenContent("@PUT", "    }", inclusiveMode = true)
        val deleteMethodStructure = this.textFileHandler.getContentBetweenContent("@DELETE", "    }", inclusiveMode = true)

        val customModel = StringBuilder().append(packageDefinition).appendLine("\n")

        importsDefinitions.forEach { importSentence ->
            customModel.append(importSentence).appendLine()
        }

        customModel.appendLine()

        val dataClassesDefinitions = StringBuilder()

        customModel.append("DATA_CLASS_SPOT").appendLine("\n")

        classDefinitionParts.forEach lit@{ chunk ->
            if (chunk.contains("@Path")) {
                customModel.append(chunk.replace("ROOT_PATH", path)).appendLine()
            }

            if (chunk.contains("@Produces") && produces.isNotEmpty()) {
                val producesValue = StringBuilder()

                produces.forEachIndexed() { index, produce ->
                    if (produce.isEmpty()) {
                        throw Exception("Consume can't be empty.")
                    }

                    if (index == produces.size - 1) {
                        producesValue.append("\"${produce}\"")
                    } else {
                        producesValue.append("\"${produce}\", ") 
                    }
                }

                customModel.append("${chunk}({${producesValue}})").appendLine()
            }

            if (chunk.contains("@Produces") && produces.isEmpty()) {
                return@lit
            }

            if (chunk.contains("class ModelController")) {
                customModel.append(chunk.replace("ModelController", controllerName)).appendLine("\n")
            }
        }

        val customPostMethod: StringBuilder = StringBuilder("")

        this.postMethods.forEach { method ->
            val postMethod = method as Route

            var existBeanParam = false

            val beanInputs = StringBuilder()

            val methodInputs = StringBuilder()

            postMethodStructure.forEachIndexed { index, chunk ->
                if (existBeanParam && chunk.contains("@")) {
                    return@forEachIndexed
                }

                if (index == 0) {
                    customPostMethod.append(chunk).appendLine()
                }

                if (chunk.contains("@Path") && postMethod.path.isNotEmpty()) {
                    var finalPath = ""

                    finalPath = if (postMethod.path.startsWith("/")) {
                        postMethod.path
                    } else {
                        "/${postMethod.path}"
                    }

                    if (postMethod.path.endsWith("/")) {
                        finalPath = finalPath.substring(finalPath.lastIndexOf("/") - 1)
                    }

                    customPostMethod.append(chunk.replace("/{path-param}", finalPath)).appendLine()
                }

                if (chunk.contains("@Consumes") && postMethod.consumes.isNotEmpty()) {
                    val consumesValue = StringBuilder()

                    postMethod.consumes.forEachIndexed() { index, consume ->
                        if (consume.isEmpty()) {
                            throw Exception("Consume can't be empty.")
                        }

                        if (index == postMethod.consumes.size - 1) {
                            consumesValue.append(consume)
                        } else {
                            consumesValue.append("${consume}, ")
                        }
                    }

                    customPostMethod.append("${chunk}({${consumesValue}})").appendLine()
                }


                if (chunk.contains("@Produces") && postMethod.produces.isNotEmpty()) {
                    val producesValue = StringBuilder()

                    postMethod.produces.forEachIndexed() { index, produce ->
                        if (produce.isEmpty()) {
                            throw Exception("Produce can't be empty.")
                        }

                        if (index == postMethod.produces.size - 1) {
                            producesValue.append(produce)
                        } else {
                            producesValue.append("${produce}, ")
                        }
                    }

                    customPostMethod.append("${chunk}({\"${producesValue}\"})").appendLine()
                }

                if (chunk.contains("fun postMethod(")) {
                    customPostMethod.append(chunk.replace("postMethod", postMethod.name)).appendLine()
                }

                // Check by path params to build the method's params
                val pathParams = "\\{\\w+}".toRegex().findAll(postMethod.path).toList()

                if (chunk.contains("@BeanParam") &&
                    postMethod.queryParams.isNotEmpty() &&
                    postMethod.headerParams.isNotEmpty() &&
                    postMethod.matrixParams.isNotEmpty() &&
                    pathParams.isNotEmpty() &&
                    (postMethod.headerParams.size > 2 ||
                            postMethod.queryParams.size > 2 ||
                            postMethod.matrixParams.size > 2 ||
                            pathParams.size > 2)
                ) {
                    existBeanParam = true

                    pathParams.forEach { pathParam ->
                        val param = pathParam.value.replace("{", "").replace("}", "")

                        beanInputs.append("@PathParam(\"${param}\") val ${param}: String, ")
                            .appendLine()
                    }

                    postMethod.queryParams.forEach { header ->
                        beanInputs.append("@QueryParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }

                    postMethod.matrixParams.forEach { header ->
                        beanInputs.append("@MatrixParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }

                    postMethod.headerParams.forEach { header ->
                        beanInputs.append("@HeaderParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }

                    postMethod.cookieParams.forEach { header ->
                        beanInputs.append("@CookieParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }

                    if (postMethod.formParams.size > 3) {
                        postMethod.formParams.forEach { header ->
                            beanInputs.append("MultivaluedMap<String, String> formParams, ").appendLine()
                        }
                    } else {
                        postMethod.formParams.forEach { header ->
                            beanInputs.append("@FormParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                                .appendLine()
                        }
                    }

                    beanInputs.setLength(beanInputs.length - 1)

                    if (beanInputs.endsWith(", ")) {
                        beanInputs.setLength(beanInputs.length - 2)
                    }

                    dataClassesDefinitions.append("data class InputBean(${beanInputs})").appendLine("\n")

                    customPostMethod.append(chunk.plus(" inputBean: InputBean")).appendLine()

                    return@forEachIndexed
                }

                if (chunk.contains("@PathParam")) {
                    pathParams.forEach { pathParam ->
                        val param = pathParam.value.replace("{", "").replace("}", "")

                        methodInputs.append("@PathParam(\"${param}\") val ${param}: String, ")
                            .appendLine()
                    }
                }

                if (chunk.contains("@QueryParam")) {
                    postMethod.queryParams.forEach { header ->
                        methodInputs.append("@QueryParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }
                }

                if (chunk.contains("@MatrixParam")) {
                    postMethod.matrixParams.forEach { header ->
                        methodInputs.append("@MatrixParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }
                }

                if (chunk.contains("@HeaderParam")) {
                    postMethod.headerParams.forEach { header ->
                        methodInputs.append("@HeaderParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }
                }

                if (chunk.contains("@CookieParam")) {
                    postMethod.cookieParams.forEach { header ->
                        methodInputs.append("@CookieParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                            .appendLine()
                    }
                }

                if (chunk.contains("@FormParam")) {
                    if (postMethod.formParams.size > 3) {
                        postMethod.formParams.forEach { header ->
                            methodInputs.append("MultivaluedMap<String, String> formParams, ").appendLine()
                        }
                    } else {
                        postMethod.formParams.forEach { header ->
                            methodInputs.append("@FormParam(\"${header.key}\") val ${header.key}: ${header.value.simpleName}, ")
                                .appendLine()
                        }
                    }
                }

                if (methodInputs.isNotEmpty()) {
                    if (methodInputs.endsWith(", ")) {
                        methodInputs.setLength(methodInputs.length - 2)
                    }

                    customPostMethod.append(methodInputs.appendLine()).appendLine()
                }

                if (chunk.contains("): Any {")) {
                    if ((postMethod as Post).body != null) {
                        customPostMethod.append("val ${(postMethod.body as KClass<*>).simpleName!!.toCamelCase()}: ${(postMethod.body as KClass<*>).simpleName}, ")

                        if (customPostMethod.endsWith(", ")) {
                            customPostMethod.setLength(customPostMethod.length - 2)
                        }

                        customPostMethod.appendLine()
                    }

                    val responseClass = (postMethod.response as KClass<*>)

                    customPostMethod.append(chunk.replace("Any", responseClass.simpleName!!)).appendLine()

                    if (responseClass.isData) {
                        val dataClassParams = StringBuilder()

                        responseClass.memberProperties.forEach {
                            val type = it.returnType.toString()

                            dataClassParams.append("val ${it.name}: ${(type.substring(type.indexOf(".") + 1))}, ")
                        }

                        dataClassParams.setLength(dataClassParams.length - 2)

                        dataClassesDefinitions.append("data class ${(postMethod.response as KClass<*>).simpleName!!}(${dataClassParams})").appendLine("\n")
                    }
                }

                if (chunk.contains("return ")) {
                    val finalReturn = StringBuilder()

                    if (existBeanParam) {
                        finalReturn.append("\"inputBean\" to InputBean, ").appendLine()
                    }

                    if ((postMethod as Post).body != null) {
                        finalReturn.append(
                            "\"${(postMethod.body as KClass<*>).simpleName!!.lowercase(Locale.getDefault())}\" to ${
                                (postMethod.body as KClass<*>).simpleName!!.lowercase(
                                    Locale.getDefault()
                                )
                            }"
                        )
                            .appendLine()
                    }

                    if (finalReturn.endsWith(", ")) {
                        finalReturn.setLength(finalReturn.length - 2)
                    }

                    customPostMethod.append(chunk.replace("emptyMap()", finalReturn.toString())).appendLine()
                }
            }

            customPostMethod.append("}").appendLine()
        }

        customModel.replace(
            customModel.indexOf("DATA_CLASS_SPOT"),
            customModel.indexOf("DATA_CLASS_SPOT") + "DATA_CLASS_SPOT".length,
            dataClassesDefinitions.toString()
        )

        customModel.append(customPostMethod).appendLine()

        print("joder")
    }
}

class Post : Route() {
    var body: KClass<*>? = null
}

class Get : Route()

class Put : Route() {
    var body: KClass<*>? = null
}

class Delete : Route()
