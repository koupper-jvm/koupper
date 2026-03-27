import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper

data class SalesItem(
    val name: String = "",
    val value: Double = 0.0
)

data class SalesReportCommand(
    val reportName: String = "Untitled",
    val region: String = "Global",
    val items: List<SalesItem> = emptyList()
)

fun main() {
    val mapper = jacksonObjectMapper()
    val map = mapOf(
        "reportName" to "Q3_Earnings",
        "region" to "Americas",
        "items" to listOf(
            mapOf("name" to "Licencia", "value" to 99.0)
        )
    )
    
    try {
        val obj = mapper.convertValue(map, SalesReportCommand::class.java)
        println("SUCCESS: " + obj.reportName)
    } catch (e: Exception) {
        println("FAILED: " + e.message)
        e.printStackTrace()
    }
}

main()
