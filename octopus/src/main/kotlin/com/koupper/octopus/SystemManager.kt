package com.koupper.octopus

val existInClassPath: (String) -> Boolean = {
    val classPath = System.getProperty("java.class.path")

    classPath.contains(it)
}

val currentClassPath: String = System.getProperty("java.class.path")

val dependsOfContainer: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isValidSentence: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+\\s:\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ScriptManager\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex()) ||
            it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ProjectManager\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isContainerType: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isConFigType: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ScriptManager\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isParameterized: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(Container,\\s*Map<String,\\s*Any>\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}

val isBuilding: (String) -> Boolean = {
    it.contains("val\\s[a-zA-Z0-9]+:\\s\\(ProjectManager\\)\\s->\\s[a-zA-Z0-9]+\\s=".toRegex())
}