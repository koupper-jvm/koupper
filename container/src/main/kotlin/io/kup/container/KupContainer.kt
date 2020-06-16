package io.kup.container

import io.kup.container.extensions.asKClass
import io.kup.container.extensions.isConcrete
import io.kup.container.extensions.isNotAnyClass
import java.io.InputStream
import java.util.*
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

    private var singletons: MutableMap<Any, Any> = mutableMapOf()

    private var listeners: MutableMap<Any, Any> = mutableMapOf()

    override fun <T : Any> bind(abstractClass: T, callback: (container: Container) -> T) {
        this.bindings[abstractClass] = { callback(this) }
    }

    override fun <T : Any, V : Any> bind(abstractClass: T, concreteClass: V) {
        this.bindings[abstractClass] = (concreteClass as KClass<*>)
    }

    override fun create(): KupContainer {
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
