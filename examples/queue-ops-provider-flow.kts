import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.queueops.QueueOpsProvider

data class Input(
    val queue: String = "demo-queue",
    val payload: String = "{\"hello\":true}"
)

@Export
val queueOpsDemo: (Input) -> Map<String, Any?> = { input ->
    val queueOps = app.getInstance(QueueOpsProvider::class)
    val created = queueOps.enqueue(input.queue, input.payload)
    val pending = queueOps.listPending(input.queue)
    val moved = queueOps.deadLetter(created.id)
    val restored = queueOps.requeue(created.id)
    mapOf(
        "ok" to true,
        "created" to created,
        "pendingCount" to pending.size,
        "deadLetterState" to moved.state,
        "restoredState" to restored.state
    )
}
