package com.koupper.octopus.extensions

import java.io.File

fun File.getProperty(propertyName: String) : String {
    var name = "undefined";

    if (".env" in this.name) {
        this.readLines().forEach {
            if (propertyName in it) {
                name = it.substring(it.indexOf("=") + 1)
            }
        }
    }

    return name
}
