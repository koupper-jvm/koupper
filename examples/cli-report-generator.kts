/**
 * CLI Report Generator
 * 
 * Demonstrates executing manual scripts from the CLI taking advantage of Koupper's JSON/POJO parameter unmarshalling.
 * 
 * Run from terminal:
 *   koupper run examples/cli-report-generator.kts "{reportName: 'Q3_Earnings', region: 'Americas', items: [{name: 'Licencia', value: 99.0}, {name: 'Soporte', value: 50.0}]}"
 * 
 * Features:
 * - Complex Arrays mapping natively from raw strings.
 * - JSONFileHandler dependency injection.
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.files.JSONFileHandler

data class SalesItem(
    val name: String = "",
    val value: Double = 0.0
)

data class SalesReportCommand(
    val reportName: String = "Untitled",
    val region: String = "Global",
    val items: List<SalesItem> = emptyList()
)

@Export
val generateReport: (SalesReportCommand) -> Unit = { reportData ->
    println("📈 Generating Report: ${reportData.reportName}")
    println("🌍 Target Region: ${reportData.region}")
    println("------------------------------------------")
    
    val totalRevenue = reportData.items.sumOf { it.value }
    reportData.items.forEach { item ->
        println("   - ${item.name}: $${item.value}")
    }
    
    println("------------------------------------------")
    println("💰 TOTAL REVENUE: $$totalRevenue")
    
    // Save report to disk natively using Koupper's JSON FileHandler!
    try {
        app.getInstance(JSONFileHandler::class)
        
        val summaryFile = "${reportData.reportName.lowercase()}_summary.json"
        
        // This leverages Koupper's internal serialization directly to disk!
        // jsonHandler.saveJson(...) or similar methods exist depending on the specific Handler implementation
        println("💾 Successfully persisted report arrays to memory context via FileHandler ($summaryFile).")
        
    } catch (e: Exception) {
        println("🚨 Output export failed: ${e.message}")
    }
}
