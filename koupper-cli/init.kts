import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.process.ModuleProcessor

@Export
val setup: (ModuleProcessor) -> String = { processor ->
    processor.name("deleteme-service")
        .version("1.0.0")
        .packageName("com.example")
        .template("DEFAULT")
        .scripts(mapOf(
            "main" to "script.kts",
        ))
        .run()

    "SUCCESS"
}