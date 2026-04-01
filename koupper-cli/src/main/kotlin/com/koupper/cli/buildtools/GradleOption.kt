package com.koupper.cli.buildtools

import com.koupper.cli.ANSIColors
import com.koupper.cli.Wizard
import com.koupper.cli.commands.RunCommand
import java.io.File
import java.nio.file.Paths

class GradleOption : Wizard {
    private lateinit var moduleName: String
    private lateinit var version: String
    private lateinit var `package`: String

    override fun init(args: Map<String, String>) {
        this.askForModuleName()

        this.askForVersion()

        this.askForPackage()

        this.requestCreation()
    }

    private fun askForModuleName() {
        do {
            print(
                    """
            
                Module name: 
            """.trimIndent()
            )

            this.moduleName = readLine() ?: ""

            if (this.moduleName.isEmpty()) {
                print("\n${ANSIColors.YELLOW_BACKGROUND_222}${ANSIColors.ANSI_BLACK} The module name can't be empty. ${ANSIColors.ANSI_RESET}\n")
            }

            if (File("${Paths.get("").toAbsolutePath()}/${this.moduleName}").exists()) {
                print("\n${ANSIColors.YELLOW_BACKGROUND_222}${ANSIColors.ANSI_BLACK} A module with the name ${this.moduleName} already exist in the current location. ${ANSIColors.ANSI_RESET}\n")

                this.moduleName = ""
            }
        } while (this.moduleName.isEmpty())
    }

    private fun askForVersion() {
        print(
                """
            
            Type your version (default 1.0.0): 
        """.trimIndent()
        )

        this.version = readLine()!!

        if (this.version.isEmpty()) {
            println("\n${ANSIColors.YELLOW_BACKGROUND_222}${ANSIColors.ANSI_BLACK} Using version 1.0.0 ${ANSIColors.ANSI_RESET}")

            this.version = "1.0.0"
        }
    }

    private fun askForPackage() {
        do {
            print(
                    """
            
                Package name: 
            """.trimIndent()
            )

            this.`package` = readLine() ?: ""

            if (`package`.isEmpty()) {
                print("\n${ANSIColors.YELLOW_BACKGROUND_222}${ANSIColors.ANSI_BLACK} The package name name can't be empty. Create a location for your code. ${ANSIColors.ANSI_RESET}\n")
            }
        } while (`package`.isEmpty())
    }

    private fun requestCreation() {
        val home = System.getProperty("user.home")

        this::class.java.classLoader.getResourceAsStream("module-config.txt").use { inputStream ->
            File("$home/.koupper/helpers/module-config.kts").outputStream().use {
                inputStream?.copyTo(it)
            }
        }

        RunCommand().execute("$home/.koupper/helpers/module-config.kts", "projectName:${this.moduleName},package:${this.`package`},version:${this.version}")
    }
}
