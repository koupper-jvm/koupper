package com.koupper.cli.languages

import com.koupper.cli.ANSIColors.ANSI_BLACK
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.ANSIColors.YELLOW_BACKGROUND_222
import com.koupper.cli.Wizard
import com.koupper.cli.constructions.ModuleOption
import com.koupper.cli.constructions.ScriptOption

class KotlinOption : Wizard {
    override fun init(args: Map<String, String>) {
        print(
            """

                Create a
                $ANSI_YELLOW_229
                1.- Module (default)
                2.- Script $ANSI_RESET
                
                Choose an option: 
        """.trimIndent()
        )

        val option = readLine()

        when {
            option!!.isEmpty() -> {
                print("\n$YELLOW_BACKGROUND_222$ANSI_BLACK Using default creation. $ANSI_RESET\n")

                ModuleOption().init()
            }
            option == "1" -> ModuleOption().init()
            option == "2" -> ScriptOption().init()
            else -> {
                println("\n$YELLOW_BACKGROUND_222$ANSI_BLACK Option $option is not valid. Using default creation.$ANSI_RESET\n")

                ModuleOption().init()
            }
        }
    }
}
