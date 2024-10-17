package com.koupper.octopus

val existInClassPath: (String) -> Boolean = {
    val classPath = System.getProperty("java.class.path")

    classPath.contains(it)
}

val currentClassPath: String = System.getProperty("java.class.path")

val dependsOfContainer: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isContainerType: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isScriptProcess: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ScriptProcess\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ScriptProcess,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isRoute: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Route\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Route,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isModuleProcess: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Process\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Process,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}
