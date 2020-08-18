package io.kup.container

import io.kup.container.exceptions.MultipleAbstractImplementationsException
import io.kup.container.exceptions.ParameterNotInjectedException
import io.kup.container.extensions.asKClass
import io.kup.container.extensions.isConcrete
import io.kup.container.extensions.isNotAnyClass
import io.kup.container.interfaces.Container
import java.io.InputStream
import java.util.*
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

val app = KupContainer()

class KupContainer() : Container {
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
        if (this.bindings[abstractClass] != null && tag == "undefined") {
            throw MultipleAbstractImplementationsException("Type[${(abstractClass as KClass<*>).simpleName}] has multiple instances, use tag for exclude the instance.")
        }

        if (this.bindings[abstractClass] != null && tag != "undefined") {
            val value = mutableMapOf(
                    tag to { callback(this) }
            )
            value.putAll(this.bindings[abstractClass] as Map<out String, () -> T>)

            this.bindings[abstractClass] = value
        }

        if (this.bindings[abstractClass] == null && tag == "undefined") {
            this.bindings[abstractClass] = { callback(this) }
        }

        if (this.bindings[abstractClass] == null && tag != "undefined") {
            this.bindings[abstractClass] = mapOf(
                    tag to { callback(this) }
            )
        }

        this.bindingsMirror.putAll(this.bindings)
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
        }

        if (this.bindings[abstractClass] == null && tag == "undefined") {
            this.bindings[abstractClass] = concreteClass as KClass<*>
        }

        if (this.bindings[abstractClass] == null && tag != "undefined") {
            this.bindings[abstractClass] = mapOf(
                    tag to concreteClass as KClass<*>
            )
        }

        this.bindingsMirror.putAll(this.bindings)
    }

    override fun create(tagName: String): KupContainer {
        if (tagName != "undefined") {
            val filteredBinding = mutableMapOf<Any, Any>()

            this.bindingsMirror.forEach { binding ->
                if (binding.value is Map<*, *>) {
                    val value = binding.value as Map<String, Any>

                    filteredBinding[binding.key] = value[tagName]!!
                } else {
                    filteredBinding[binding.key] = binding.value
                }
            }

            this.bindings = filteredBinding
        }

        return this
    }

    override fun <T : Any> singleton(abstractClass: T, callback: () -> T) {
        this.singletons[abstractClass] = callback()
    }

    override fun <T : Any, V : Any> singleton(abstractClass: T, concreteClass: V) {
        this.singletons[abstractClass] = (concreteClass as KClass<*>).createInstance()
    }

    override fun get(): KupContainer {
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
            val kotlinResource = it.substring(0, it.indexOf("."))

            classes.add(Class.forName("$`package`.$kotlinResource").kotlin)
        }

        return classes
    }

}
