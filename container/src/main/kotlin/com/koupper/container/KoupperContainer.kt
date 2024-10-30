package com.koupper.container

import com.koupper.container.exceptions.BindingException
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
import com.koupper.container.injector.injector
import com.koupper.container.interfaces.Container
import java.io.InputStream
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance

val app = KoupperContainer()

class KoupperContainer() : Container {
    private var scope: String = ""

    constructor(scope: String) : this() {
        this.scope = scope

        this.loadAbstractClassesFromScope()
    }

    private var bindings: MutableMap<Any, Any> = mutableMapOf()

    private var bindingsMirror: MutableMap<Any, Any> = mutableMapOf()

    private var singletons: MutableMap<Any, Any> = mutableMapOf()

    private var listeners: MutableMap<Any, Any> = mutableMapOf()

    override fun <T : Any> bind(abstractClass: T, callback: (container: Container) -> T, tag: String) {
        if (this.bindings[abstractClass] != null) {
            if (tag == "undefined") {
                throw MultipleAbstractImplementationsException("Type[${(abstractClass as KClass<*>).simpleName}] has multiple instances, use tag for exclude the instance.")
            }

            if (this.bindings[abstractClass] is Map<*, *>) {
                val value = this.bindings[abstractClass] as Map<String, () -> T>

                if (value[tag] != null) {
                    throw BindingException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                }
            }
        }

        if (this.bindings[abstractClass] != null && tag != "undefined") {
            val value = mutableMapOf(
                    tag to { callback(this) }
            )
            value.putAll(this.bindings[abstractClass] as Map<String, () -> T>)

            this.bindings[abstractClass] = value

            this.bindingsMirror[abstractClass] = value

            return
        }

        if (this.bindings[abstractClass] == null && tag == "undefined") {
            this.bindings[abstractClass] = { callback(this) }

            this.bindingsMirror[abstractClass] = { callback(this) }

            return
        }

        if (this.bindings[abstractClass] == null && tag != "undefined") {
            this.bindings[abstractClass] = mapOf(
                    tag to { callback(this) }
            )

            this.bindingsMirror[abstractClass] = mapOf(
                    tag to { callback(this) }
            )

            return
        }
    }

    override fun <T : Any, V : Any> bind(abstractClass: T, concreteClass: V, tag: String) {
        if (this.bindings[abstractClass] != null && tag == "undefined") {
            throw MultipleAbstractImplementationsException("Type[${(abstractClass as KClass<*>).simpleName}] has multiple instances, use tag for exclude the instance.")
        }

        if (this.bindings[abstractClass] != null && tag != "undefined") {
            val value = mutableMapOf(
                    tag to concreteClass as KClass<*>
            )
            value.putAll(this.bindings[abstractClass] as Map<out String, KClass<*>>)

            this.bindings[abstractClass] = value

            this.bindingsMirror[abstractClass] = value

            return
        }

        if (this.bindings[abstractClass] == null && tag == "undefined") {
            this.bindings[abstractClass] = concreteClass as KClass<*>

            this.bindingsMirror[abstractClass] = concreteClass as KClass<*>

            return
        }

        if (this.bindings[abstractClass] == null && tag != "undefined") {
            this.bindings[abstractClass] = mapOf(
                    tag to concreteClass as KClass<*>
            )

            this.bindingsMirror[abstractClass] = mapOf(
                    tag to concreteClass as KClass<*>
            )

            return
        }
    }

    override fun <T : Any> createInstanceOf(kClass: KClass<T>, tagName: String): T {
        if (tagName != "undefined") {
            val filteredBinding = mutableMapOf<Any, Any>()

            this.bindingsMirror.forEach { binding ->
                if (binding.value is Map<*, *>) {
                    val value = binding.value as Map<String, Any>

                    value.forEach { (k, v) ->
                        if (k == tagName) {
                            filteredBinding[binding.key] = value[tagName]!!
                        }
                    }
                } else {
                    filteredBinding[binding.key] = binding.value
                }
            }

            this.bindings = filteredBinding
        }

        return createInstance(this, kClass)
    }

    override fun <T : Any> singleton(abstractClass: T, callback: () -> T) {
        this.singletons[abstractClass] = callback()
    }

    override fun <T : Any, V : Any> singleton(abstractClass: T, concreteClass: V) {
        this.singletons[abstractClass] = (concreteClass as KClass<*>).createInstance()
    }

    override fun get(): KoupperContainer {
        return this
    }

    override fun <T : Any> listenFor(abstractClass: T, callback: (instance: Any) -> Unit) {
        this.listeners[abstractClass] = callback
    }

    override fun getBindings(): MutableMap<Any, Any> {
        return this.bindings
    }

    override fun getSingletons(): MutableMap<Any, Any> {
        return this.singletons
    }

    override fun getListeners(): MutableMap<Any, Any> {
        return this.listeners
    }

    override fun loadAbstractClassesFromScope() {
        if (scope.isNotEmpty()) {
            val abstractClassesList = mutableMapOf<Class<*>, List<KClass<*>>>()

            this.getClasses(this::class.java.classLoader, scope)!!.forEach { kClass ->
                if (kClass.isConcrete()) {
                    kClass.supertypes.forEach { superType ->
                        if (superType.isNotAnyClass()) {
                            if (abstractClassesList[superType.asKClass().java] != null) {
                                (abstractClassesList[superType.asKClass().java] as MutableList).add(kClass)
                            } else {
                                val concrete = mutableListOf(kClass)

                                abstractClassesList[superType.asKClass().java] = concrete
                            }
                        }
                    }
                } else {
                    if (abstractClassesList[kClass.java] == null) {
                        abstractClassesList[kClass.java] = mutableListOf()
                    }
                }
            }

            this.bindings.putAll(abstractClassesList)
        }
    }

    @Throws(Exception::class)
    fun getClasses(classLoader: ClassLoader, `package`: String): List<KClass<*>>? {
        val resourceLocation = classLoader.getResource(`package`.replace(".", "/"))

        val inputStream = resourceLocation.content as InputStream

        val classes: MutableList<KClass<*>> = ArrayList()

        val resources = inputStream.bufferedReader().readLines()

        resources.forEach {
            if (it.contains(".")) {
                val kotlinResource = it.substring(0, it.indexOf("."))

                classes.add(Class.forName("$`package`.$kotlinResource").kotlin)
            }
        }

        return classes
    }

    fun <T : Any> createSingletonOf(kClass: KClass<T>): T {
        val instance = this.getSingletons()[kClass] as T

        if (this.getListeners()[kClass] is Function<*>) {
            val callback = this.getListeners()[kClass] as (T) -> Unit

            callback(instance)
        }

        return instance
    }

    fun <T : Any> createInstanceOf(kClass: KClass<T>): T {
        return createInstance(this, kClass)
    }

    private fun <T : Any> createInstance(container: Container, kClass: KClass<T>): T {
        val instance = when {
            container.getBindings()[kClass] is Function<*> -> {
                (container.getBindings()[kClass] as () -> T).invoke()
            }
            container.getBindings()[kClass] is List<*> -> {
                if (container.getBindings()[kClass] != null && (container.getBindings()[kClass] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (container.getBindings()[kClass] as List<*>)[0] as KClass<*>) as T
            }
            container.getBindings()[kClass.java] is List<*> -> {
                if (container.getBindings()[kClass.java] != null && (container.getBindings()[kClass.java] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (container.getBindings()[kClass.java] as List<*>)[0] as KClass<*>) as T
            }
            container.getBindings()[kClass] == null -> {
                throw BindingException("Type[$kClass] is not bound in the container")
            }
            else -> {
                injector.resolveDependenciesFor(container, container.getBindings()[kClass] as KClass<T>)
            }
        }

        if (container.getListeners()[kClass] is Function<*>) {
            val callback = container.getListeners()[kClass] as (T) -> Unit

            callback(instance)
        }

        return instance
    }

    fun <T : Any> createInstanceOf(name: String): T {
        var instance: T? = null

        this.getBindings().forEach lit@{ key, value ->
            if ((key as KClass<*>).qualifiedName == name || key.simpleName == name) {
                instance = when (value) {
                    is Function<*> -> {
                        (value as () -> T).invoke()
                    }
                    is KClass<*> -> {
                        injector.resolveDependenciesFor(this, value as KClass<T>)
                    }
                    else -> {
                        value as T
                    }
                }

                return@lit
            }
        }

        return instance!!
    }

    fun KType.isNotAnyClass(): Boolean {
        return !(this.classifier as KClass<*>).simpleName.equals("Any")
    }

    fun KClass<*>.isConcrete(): Boolean {
        return !this.isAbstract
    }

    fun KType.asKClass(): KClass<*> {
        return this.classifier as KClass<*>
    }
}
