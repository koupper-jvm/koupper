import com.koupper.container.interfaces.ScriptManager

val init: (ScriptManager) -> ScriptManager = {
    it.runScript("yourScript.kts") // the params are optional as a map in second place to 'runScript' function.

    //it.runScript("yourScript.kts", mapOf("key", value))
}
