package com.koupper.octopus

val existInClassPath: (String) -> Boolean = {
    val classPath = System.getProperty("java.class.path")

    classPath.contains(it)
}

val currentClassPath: String = System.getProperty("java.class.path")

val dependesOfContainer: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isValidSentence: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
        it.contains("val\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
        it.contains("val\\s[a-zA-Z0-9]+\\s:\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isTyped: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:".toRegex())
}
