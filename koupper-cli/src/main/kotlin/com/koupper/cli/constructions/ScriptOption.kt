package com.koupper.cli.constructions

import com.koupper.cli.ANSIColors.ANSI_BLACK
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.ANSIColors.YELLOW_BACKGROUND_222
import com.koupper.cli.Wizard
import java.io.File
import java.io.InputStream

class ScriptOption : Wizard {
    override fun init(args: Map<String, String>) {
        var fileName: String?

        do {
            print("$ANSI_YELLOW_229\nName of file: $ANSI_RESET")

            fileName = readLine()

            if (fileName!!.isEmpty()) {
                print("\n$YELLOW_BACKGROUND_222$ANSI_BLACK The name of file can not be empty. $ANSI_RESET\n")
            }
        } while (fileName!!.isEmpty())

        val finalFileName = this.sanitizeFileName(fileName)

        val currentDirectory = System.getProperty("user.dir")

        println("\n${ANSI_WHITE}file created with path: $currentDirectory/$finalFileName$ANSI_RESET\n")

        this::class.java.classLoader.getResourceAsStream("script.txt").toFile("$currentDirectory/$finalFileName")
    }

    private fun sanitizeFileName(fileName: String): String {
        val hasExtension = fileName.contains(".kts")

        if (hasExtension) {
            return fileName
        }

        return fileName.plus(".kts")
    }

    private fun InputStream.toFile(path: String) {
        File(path).outputStream().use { this.copyTo(it) }
    }
}
