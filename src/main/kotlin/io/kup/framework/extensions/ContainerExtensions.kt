package io.kup.framework.extensions

inline fun <reified T> MutableMap<Any, Any>.instance(): T {
    val map: MutableMap<Any, Any> = this

    val key = T::class

    val value = map[key]

    if (value is Function<*>) return (value as () -> T).invoke()

    return map[key] as T
}

