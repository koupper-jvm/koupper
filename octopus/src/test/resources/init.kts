import com.koupper.octopus.process.ScriptProcess

val init: (ScriptProcess) -> ScriptProcess = {
    it.runScript("yourScript.kts") // the params are optional as a map in second place to 'runScript' function.

    //it.runScript("yourScript.kts", mapOf("key", value))
}
