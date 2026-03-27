package com.koupper.shared.resources

fun resource(name: String): String {
    return object {}.javaClass.getResource("/$name")?.readText()
        ?: error("❌ Resource not found: $name")
}