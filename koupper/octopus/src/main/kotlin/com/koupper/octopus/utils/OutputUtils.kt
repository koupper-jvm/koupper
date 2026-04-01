package com.koupper.octopus.utils

import java.io.ByteArrayOutputStream
import java.io.PrintStream

fun <R> captureOutputWithConfig(
    loggerLevel: String = "INFO",
    destination: String = "console",
    block: () -> R
): String {
    val originalOut = System.out
    val originalErr = System.err

    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream, true, "UTF-8")

    return try {
        if (destination == "console") {
            System.setOut(printStream)
            System.setErr(printStream)
        }

        val resultValue = block()
        if (resultValue !is Unit) {
            print(resultValue)
        }

        outputStream.toString("UTF-8")
    } catch (e: Exception) {
        e.printStackTrace()
        outputStream.toString("UTF-8")
    } finally {
        if (destination == "console") {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
