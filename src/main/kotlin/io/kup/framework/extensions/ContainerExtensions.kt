package io.kup.framework.extensions

inline fun <reified T> MutableMap<Any, Any>.instance(): T {
    if (this[T::class] is Function<*>) return (this[T::class] as () -> T).invoke()

    return this[T::class] as T
}

inline fun <reified T> MutableMap<Any, Any>.singletonOf(): T {
    return this[T::class] as T
}

