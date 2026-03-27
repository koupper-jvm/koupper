package com.koupper.octopus.modules.http

fun normalizePath(base: String, child: String): String {

    val left = base.trimEnd('/')
    val right = child.trimStart('/')

    return when {
        left.isBlank() && right.isBlank() -> "/"
        left.isBlank() -> "/$right"
        right.isBlank() -> left
        else -> "$left/$right"
    }
}