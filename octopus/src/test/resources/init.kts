import com.koupper.container.interfaces.ScriptManager

val init: (ScriptManager) -> ScriptManager = {
    it.runScript("example.kts", mapOf(
            "user_id" to 1234
    ))
}