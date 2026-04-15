import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.vectordb.VectorDbProvider
import com.koupper.providers.vectordb.VectorRecord

@Export
val vectorDbDemo: () -> Map<String, Any?> = {
    val vectorDb = app.getInstance(VectorDbProvider::class)
    vectorDb.upsert(
        "demo",
        listOf(
            VectorRecord(id = "a", vector = listOf(1.0, 0.0), metadata = mapOf("topic" to "alpha")),
            VectorRecord(id = "b", vector = listOf(0.7, 0.3), metadata = mapOf("topic" to "beta")),
            VectorRecord(id = "c", vector = listOf(0.0, 1.0), metadata = mapOf("topic" to "gamma"))
        )
    )

    val results = vectorDb.query("demo", vector = listOf(0.9, 0.1), topK = 2)
    mapOf(
        "ok" to true,
        "count" to results.size,
        "top" to results.firstOrNull()
    )
}
