package com.koupper.cli.constructions

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.Wizard
import com.koupper.cli.commands.RunCommand
import java.io.File
import kotlin.system.exitProcess

class ModuleOption : Wizard {
    private lateinit var moduleName: String
    private var moduleType: String = "FRONT"
    private lateinit var args: Map<String, String>

    override fun init(args: Map<String, String>) {
        this.args = args

        this.askForModuleName()

        this.askForModuleType()

        this.requestCreation()
    }

    private fun askForModuleName() {
        val moduleName = this.args["moduleName"]

        if (moduleName != null && moduleName.isNotEmpty()) {
            this.checkForModuleExistence(moduleName)

            this.moduleName = moduleName

            return
        } else {
            do {
                print(
                        """
            
                Module name -> 
            """.trimIndent()
                )

                this.moduleName = readLine() ?: ""

                if (this.moduleName.isEmpty()) {
                    print("\n${ANSI_YELLOW_229}The module name can't be empty.$ANSI_RESET\n")
                }
            } while (this.moduleName.isEmpty())

            this.checkForModuleExistence(this.moduleName)
        }
    }

    private fun askForModuleType() {
        val moduleType = this.args["moduleType"]

        if (moduleType != null && moduleType.isNotEmpty()) {
            this.moduleType = moduleType
        } else {
            print(
                    """
                        
                        ${ANSI_YELLOW_229}What build environment do you need?
                        $ANSI_YELLOW_229
                        1.- front
                        2.- back
                        3.- db
                        4.- docker $ANSI_RESET
            
                        -> 
                    """.trimIndent()
            )

            this.moduleType = when(readLine()) {
                "1" -> "FRONT"
                "2" -> "BACK"
                "3" -> "DATABASE"
                "4" -> "VIRTUALIZATION"
                else -> "FRONT"
            }

            println()
        }
    }

    private fun  checkForModuleExistence(moduleName: String) {
        val modulePath = File(moduleName)

        if (modulePath.exists()) {
            print("\n${ANSI_YELLOW_229}A folder named $ANSI_GREEN_155${this.moduleName}$ANSI_RESET$ANSI_YELLOW_229 already exist. Try using another name.$ANSI_RESET\n")

            exitProcess(0)
        }
    }

    private fun requestCreation() {
        val home = System.getProperty("user.home")

        this::class.java.classLoader.getResourceAsStream("module-config.txt").use { inputStream ->
            File("$home/.koupper/helpers/module-config.kts").outputStream().use {
                inputStream?.copyTo(it)
            }
        }

        RunCommand().execute("$home/.koupper/helpers/module-config.kts", "moduleName:${this.moduleName},moduleType:${this.moduleType}")
    }
}
