import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import com.koupper.octopus.annotations.Export

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
val testDeserialization: () -> Unit = {
    val mapper = jacksonObjectMapper()
    val jsonString = "{\"reportName\": \"Q3_Earnings\", \"region\": \"Americas\", \"items\": [{\"name\": \"Licencia\", \"value\": 99.0}]}"
    
    try {
        val obj = mapper.readValue(jsonString, SalesReportCommand::class.java)
        println("✅ SUCCESS: " + obj.reportName + " - " + obj.items.size + " items")
    } catch (e: Exception) {
        println("❌ FAILED: " + e.message)
        e.printStackTrace()
    }
}
