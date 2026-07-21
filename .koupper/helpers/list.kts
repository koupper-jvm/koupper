import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.shared.annotations.Export

@Export
val setup: (ModuleAnalyzer) -> Unit = { analyzer ->
    analyzer.target("C:\\Users\\dosek\\develop\\igly-comms").run()
}
