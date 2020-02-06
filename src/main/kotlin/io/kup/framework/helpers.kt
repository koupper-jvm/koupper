package io.kup.framework

fun Package.clearName(): String {
    val sourcePackage = this.toString()

    return sourcePackage.substring(sourcePackage.indexOf(" ")).trim()
}
