package com.koupper.providers.agent

/**
 * Marks the Koupper Agent DSL.
 */
@DslMarker
annotation class AgentDsl

@AgentDsl
class AgentConfig(
    val name: String,
    val role: RoleConfig,
    val tools: List<String>,
    val task: TaskConfig<*>
)

class RoleConfig(
    val identity: String,
    val goal: String,
    val instructions: String
)

class TaskConfig<T : Any>(
    val outputSchema: Class<T>,
    val prompt: String,
    val onToken: ((String) -> Unit)?,
    val onHallucination: ((Throwable, String) -> Unit)?
)

// --- DSL Builders ---

@AgentDsl
class AgentBuilder {
    var name: String = "anonymous-agent"
    private var roleConfig: RoleConfig? = null
    private val toolsList = mutableListOf<String>()
    var taskConfig: TaskConfig<*>? = null

    fun role(block: RoleBuilder.() -> Unit) {
        roleConfig = RoleBuilder().apply(block).build()
    }

    fun tools(block: ToolsBuilder.() -> Unit) {
        toolsList.addAll(ToolsBuilder().apply(block).build())
    }

    inline fun <reified T : Any> task(block: TaskBuilder<T>.() -> Unit) {
        taskConfig = TaskBuilder(T::class.java).apply(block).build()
    }

    fun build(): AgentConfig {
        requireNotNull(roleConfig) { "Agent role must be defined via role { ... }" }
        requireNotNull(taskConfig) { "Agent task must be defined via task<T> { ... }" }
        return AgentConfig(name, roleConfig!!, toolsList, taskConfig!!)
    }
}

@AgentDsl
class RoleBuilder {
    var identity: String = ""
    var goal: String = ""
    var instructions: String = ""

    fun build() = RoleConfig(identity, goal, instructions)
}

@AgentDsl
class ToolsBuilder {
    private val tools = mutableListOf<String>()
    fun use(providerName: String) { tools.add(providerName) }
    fun build() = tools
}

@AgentDsl
class TaskBuilder<T : Any>(private val schema: Class<T>) {
    var prompt: String = ""
    private var tokenHandler: ((String) -> Unit)? = null
    private var hallucinationHandler: ((Throwable, String) -> Unit)? = null

    fun onToken(handler: (String) -> Unit) { tokenHandler = handler }
    fun onHallucination(handler: (Throwable, String) -> Unit) { hallucinationHandler = handler }

    fun build() = TaskConfig(schema, prompt, tokenHandler, hallucinationHandler)
}

/**
 * Entry point for the Koupper Agentic DSL.
 */
fun agent(block: AgentBuilder.() -> Unit): AgentConfig {
    return AgentBuilder().apply(block).build()
}
