package com.koupper.providers.extensions

import java.io.File
import java.io.InputStream

fun File.copyInputStreamToFile(inputStream: InputStream) {
    this.outputStream().use { fileOut ->
        inputStream.copyTo(fileOut)
    }
}

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

fun ArrayList<Int>.getProperty(propertyName: String) : ByteArray {
    return this.foldIndexed(ByteArray(this.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
}
