package com.koupper.providers.agent

sealed interface AgentState {
    data object Idle : AgentState
    data class Reasoning(val promptTokens: Int) : AgentState
    data class Executing(val tool: String) : AgentState
    data object AwaitingReview : AgentState
    data class Failed(val error: String) : AgentState
}

sealed class HardwareTier {
    data object LOW_END : HardwareTier()
    data object CPU_OPTIMIZED : HardwareTier()
    data object GPU_ACCELERATED : HardwareTier()
}

data class HardwareTelemetry(
    val physicalCores: Int,
    val logicalProcessors: Int,
    val totalRamGb: Double,
    val freeRamGb: Double,
    val hasAvx512Vnni: Boolean,
    val hasAvx2: Boolean,
    val isNvme: Boolean,
    val hasGpu: Boolean,
    val gpuType: String? = null, // e.g., "NVIDIA", "AMD", "METAL"
    val gpuMemoryGb: Double? = null
)

data class AgentBudget(
    val tier: HardwareTier,
    val maxConcurrentAgents: Int,
    val telemetry: HardwareTelemetry
)
