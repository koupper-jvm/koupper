/**
 * Hello World Script
 * 
 * Demonstrates the basic execution of a Kotlin script within Koupper.
 * You can execute this script directly via the CLI:
 *   koupper run hello-world.kts "Your_Name"
 * 
 * Features showcased:
 * - @Export: Tells the Octopus engine which block of code acts as the entry point.
 */
import com.koupper.octopus.annotations.Export

@Export
val sayHello: (String) -> Unit = { name ->
    val finalName = if (name.isBlank() || name == "EMPTY_PARAMS") "World" else name
    println("👋 Hello $finalName! Welcome to the Koupper Octopus Engine.")
}
