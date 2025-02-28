package com.koupper.octopus

val existInClassPath: (String) -> Boolean = {
    val classPath = System.getProperty("java.class.path")

    classPath.contains(it)
}

val currentClassPath: String = System.getProperty("java.class.path")

val dependsOfContainer: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isParameterizable: (String) -> Boolean = {
    it.contains("\\(Container\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex()) ||
            it.contains("\\(Map<String,\\s*Any>\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex())
}


val isScriptProcess: (String) -> Boolean = {
    it.contains("\\(ScriptProcessor\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex()) ||
            it.contains("\\(ScriptProcess,\\s*Map<String,\\s*Any>\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex())
}


val isRoute: (String) -> Boolean = {
    it.contains("\\(Route\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex()) ||
            it.contains("\\(Route,\\s*Map<String,\\s*Any>\\)\\s*->\\s*[a-zA-Z0-9]+".toRegex())
}

val isModuleProcess: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ScriptProcessor\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Process,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}
