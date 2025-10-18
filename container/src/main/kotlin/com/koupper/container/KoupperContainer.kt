package com.koupper.container

import com.koupper.container.exceptions.BindingException
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
import com.koupper.container.injector.injector
import com.koupper.container.interfaces.Container
import java.io.InputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass
import kotlin.reflect.KType

val app = KoupperContainer()
var context: String? = null

class KoupperContainer() : Container {
    private var scope: String = ""

    constructor(scope: String) : this() {
        this.scope = scope

        this.loadAbstractClassesFromScope()
    }

    private var bindings: MutableMap<Any, Any> = mutableMapOf()

    private var bindingsMirror: MutableMap<Any, Any> = mutableMapOf()

    private var singletonMirror: MutableMap<Any, Any> = mutableMapOf()

    private var singletons: MutableMap<Any, Any> = mutableMapOf()

    private var listeners: MutableMap<Any, Any> = mutableMapOf()

    override fun <T : Any> bind(abstractClass: T, callback: (container: Container) -> T, tag: String) {
        if (this.bindings[abstractClass] != null) {
            if (this.bindings[abstractClass] is Map<*, *>) {
                val value = this.bindings[abstractClass] as Map<String, () -> T>

                if (value[tag] != null) {
                    throw MultipleAbstractImplementationsException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                }
            }
        }

        val value = mutableMapOf(
            tag to { callback(this) }
        )

        if (this.bindings[abstractClass] != null) {
            value.putAll(this.bindings[abstractClass] as Map<String, () -> T>)
        }

        this.bindings[abstractClass] = value

        this.bindingsMirror[abstractClass] = value
    }

    override fun <T : Any, V : Any> bind2(abstractClass: T, concreteClass: V, tag: String) {
        if (this.bindings[abstractClass] != null) {
            if (this.bindings[abstractClass] is Map<*, *>) {
                val value = this.bindings[abstractClass] as Map<String, () -> T>

                if (value[tag] != null) {
                    throw MultipleAbstractImplementationsException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                }
            }

            if (this.bindings[abstractClass] is ArrayList<*>) {
                val preloaded = this.bindings[abstractClass] as ArrayList<*>

                preloaded.forEach { if (it.toString() == concreteClass.toString()) {
                    throw BindingException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                } }
            }
        }

        val value = mutableMapOf(
            tag to { concreteClass as KClass<*> }
        )

        if (this.bindings[abstractClass] != null) {
            value.putAll(this.bindings[abstractClass] as Map<out String, () -> KClass<*>>)
        }

        this.bindings[abstractClass] = value

        this.bindingsMirror[abstractClass] = value
    }

    override fun <T : Any> getInstance(kClass: KClass<T>, tagName: String): T {
        this.bindings[kClass]
            ?: throw BindingException("Type[$kClass] is not bound in the container")

        val filteredBinding = mutableMapOf<Any, Any>()

        this.bindings.forEach { binding ->
            if (binding.value is Map<*, *>) {
                val implementations = binding.value as Map<String, Any>

                implementations.forEach { (k, v) ->
                    if (kClass.toString() == binding.key.toString() && k == tagName) {
                        filteredBinding[binding.key] = implementations[tagName]!!
                    }
                }
            }

            if (binding.value is Function<*>) {
                filteredBinding[binding.key] = (binding.value as () -> T).invoke()
            }
        }

        val container = this

        val instance = when {
            filteredBinding[kClass] is Function<*> -> {
                val binding = filteredBinding[kClass] as () -> Any?
                when (val result = binding.invoke()) {
                    is KClass<*> -> {
                        injector.resolveDependenciesFor(container, result as KClass<T>)
                    }
                    else -> {
                        result as T
                    }
                }
            }
            filteredBinding[kClass] is List<*> -> {
                if (filteredBinding[kClass] != null && (filteredBinding[kClass] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (filteredBinding[kClass] as List<*>)[0] as KClass<*>) as T
            }
            filteredBinding[kClass] is List<*> -> {
                if (filteredBinding[kClass.java] != null && (filteredBinding[kClass.java] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (filteredBinding[kClass.java] as List<*>)[0] as KClass<*>) as T
            }
            filteredBinding[kClass] is Map<*, *> -> {
                val instances = filteredBinding[kClass] as Map<String, () -> T>

                if (instances[tagName] != null) {
                    val instance = instances[tagName]
                    injector.resolveDependenciesFor(container, instance?.invoke() as KClass<T>)
                } else {
                    throw BindingException("Type[$tagName] is not bound in the container")
                }
            }
            else -> {
                injector.resolveDependenciesFor(container, filteredBinding[kClass] as KClass<T>)
            }
        }

        if (container.getListeners()[kClass] is Function<*>) {
            val callback = container.getListeners()[kClass] as (T) -> Unit

            callback(instance)
        }

        return instance
    }

    override fun <T : Any> singleton(abstractClass: T, callback: (container: Container) -> T, tag: String?) {
        if (this.singletons[abstractClass] != null) {
            if (this.singletons[abstractClass] is Map<*, *>) {
                val value = this.singletons[abstractClass] as Map<String, () -> T>

                if (value[tag] != null) {
                    throw BindingException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                }
            }
        }

        val instances = mutableMapOf(
            tag to callback(this)
        )

        if (this.singletons[abstractClass] != null) {
            (this.singletons[abstractClass] as Map<*, *>).forEach { key, value ->
                val instance: T = when (value) {
                    is Function<*> -> (value as () -> T).invoke()
                    else -> value as T
                }
                instances[key as String] = instance
            }
        }

        this.singletons[abstractClass] = instances

        this.singletonMirror[abstractClass] = instances
    }

    override fun <T : Any, V : Any> singleton2(abstractClass: T, concreteClass: V, tag: String)  {
        if (this.singletons[abstractClass] != null) {
            if (this.singletons[abstractClass] is Map<*, *>) {
                val value = this.singletons[abstractClass] as Map<String, () -> T>

                if (value[tag] != null) {
                    throw BindingException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                }
            }

            if (this.singletons[abstractClass] is ArrayList<*>) {
                val preloaded = this.singletons[abstractClass] as ArrayList<*>

                preloaded.forEach { if (it.toString() == concreteClass.toString()) {
                    throw BindingException("Type[${(abstractClass as KClass<*>).simpleName}] exist in the container.")
                } }
            }
        }

        val instances = mutableMapOf(
            tag to concreteClass as KClass<*>
        )

        if (this.singletons[abstractClass] != null) {
            (this.singletons[abstractClass] as Map<*, *>).forEach { key, value ->
                val instance = value as KClass<*>
                instances[key as String] = instance
            }
        }

        this.singletons[abstractClass] = instances

        this.singletonMirror[abstractClass] = instances
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

    override fun loadAbstractClassesFromScope(tag: String) {
        if (scope.isNotEmpty()) {
            val implementations = mutableMapOf<Any, Any>()

            this.getClasses(this::class.java.classLoader, scope)!!.forEach { kClass ->
                if (kClass.isConcrete()) {
                    kClass.supertypes.forEach { superType ->
                        if (superType.isNotAnyClass()) {
                            if (implementations[superType.asKClass()] != null) {
                                implementations[superType.asKClass()] = mapOf(tag to kClass)
                            } else {
                                val concrete = mutableListOf(kClass)

                                implementations[superType.asKClass()] = mapOf(tag to concrete)
                            }
                        }
                    }
                } else {
                    if (implementations[kClass] == null) {
                        implementations[kClass] = emptyMap<Any, Any>()
                    }
                }
            }

            this.bindings.putAll(implementations)
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

    override fun <T : Any> createSingleton(kClass: KClass<T>, tagName: String): T {
        this.singletons[kClass]
            ?: throw BindingException("Singleton for [${kClass.simpleName}] not found in container")

        val filteredSingletons = mutableMapOf<Any, Any>()

        this.singletons.forEach { singleton ->
            if (singleton.value is Map<*, *>) {
                val implementations = singleton.value as Map<String, Any>

                implementations.forEach { (k, v) ->
                    if (kClass.toString() == singleton.key.toString() && k == tagName) {
                        filteredSingletons[singleton.key] = implementations[tagName]!!
                    }
                }
            }

            if (singleton.value is Function<*>) {
                filteredSingletons[singleton.key] = (singleton.value as () -> T).invoke()
            }
        }

        val instance = when (filteredSingletons[kClass]) {
            is KClass<*> -> {
                val newDependency = injector.resolveDependenciesFor(this, filteredSingletons[kClass] as KClass<T>)

                val innerMap = this.singletons[kClass] as? MutableMap<Any, Any>
                    ?: mutableMapOf<Any, Any>().also { this.singletons[kClass] = it }

                innerMap.forEach { (k, _) ->
                    if (k == tagName) {
                        innerMap[k] = newDependency
                    }
                }

                newDependency
            }

            else -> {
                filteredSingletons[kClass] as T
            }
        }

        if (this.getListeners()[kClass] is Function<*>) {
            val callback = this.getListeners()[kClass] as (T) -> Unit
            callback(instance)
        }

        return instance
    }

    override fun <T : Any> getInstance(kClassName: String, tagName: String): T {
        val kClass = Class.forName(kClassName).kotlin

        this.bindings[kClass]
            ?: throw BindingException("Type[$kClass] is not bound in the container")

        val filteredBinding = mutableMapOf<Any, Any>()

        this.bindings.forEach { binding ->
            if (binding.value is Map<*, *>) {
                val implementations = binding.value as Map<String, Any>

                implementations.forEach { (k, v) ->
                    if (kClass.toString() == binding.key.toString() && k == tagName) {
                        filteredBinding[binding.key] = implementations[tagName]!!
                    }
                }
            }

            if (binding.value is Function<*>) {
                filteredBinding[binding.key] = (binding.value as () -> T).invoke()
            }
        }

        val container = this

        val instance = when {
            filteredBinding[kClass] is Function<*> -> {
                val binding = filteredBinding[kClass] as () -> Any?
                when (val result = binding.invoke()) {
                    is KClass<*> -> {
                        injector.resolveDependenciesFor(container, result as KClass<T>)
                    }
                    else -> {
                        result as T
                    }
                }
            }
            filteredBinding[kClass] is List<*> -> {
                if (filteredBinding[kClass] != null && (filteredBinding[kClass] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (filteredBinding[kClass] as List<*>)[0] as KClass<*>) as T
            }
            filteredBinding[kClass] is List<*> -> {
                if (filteredBinding[kClass.java] != null && (filteredBinding[kClass.java] as List<*>).size > 1) {
                    throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
                }

                injector.resolveDependenciesFor(container, (filteredBinding[kClass.java] as List<*>)[0] as KClass<*>) as T
            }
            filteredBinding[kClass] is Map<*, *> -> {
                val instances = filteredBinding[kClass] as Map<String, () -> T>

                if (instances[tagName] != null) {
                    val instance = instances[tagName]
                    injector.resolveDependenciesFor(container, instance?.invoke() as KClass<T>)
                } else {
                    throw BindingException("Type[$tagName] is not bound in the container")
                }
            }
            else -> {
                injector.resolveDependenciesFor(container, filteredBinding[kClass] as KClass<T>)
            }
        }

        if (container.getListeners()[kClass] is Function<*>) {
            val callback = container.getListeners()[kClass] as (T) -> Unit

            callback(instance)
        }

        return instance
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
