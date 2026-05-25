import com.koupper.shared.annotations.Export
import com.koupper.octopus.process.ModuleAnalyzer

@Export
val setup: (ModuleAnalyzer) -> Unit = { analyzer ->
    analyzer.target("C:\\Users\\dosek\\develop\\igly-comms").run()
}
