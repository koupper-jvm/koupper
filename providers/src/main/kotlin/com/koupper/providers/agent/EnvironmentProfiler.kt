package com.koupper.providers.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

interface EnvironmentProfiler {
    suspend fun audit(): AgentBudget
}

class GenericEnvironmentProfiler : EnvironmentProfiler {
    override suspend fun audit(): AgentBudget = withContext(Dispatchers.IO) {
        val logicalProcessors = Runtime.getRuntime().availableProcessors()
        val physicalCores = (logicalProcessors / 2).coerceAtLeast(1)
        
        // Basic JVM telemetry as fallback
        val totalRamGb = (Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0 / 1024.0).coerceAtLeast(4.0)
        val freeRamGb = (Runtime.getRuntime().freeMemory() / 1024.0 / 1024.0 / 1024.0).coerceAtLeast(1.0)

        val telemetry = HardwareTelemetry(
            physicalCores = physicalCores,
            logicalProcessors = logicalProcessors,
            totalRamGb = totalRamGb,
            freeRamGb = freeRamGb,
            hasAvx512Vnni = false,
            hasAvx2 = true, // Heuristic default for modern generic envs
            isNvme = true,
            hasGpu = false
        )

        AgentBudget(HardwareTier.CPU_OPTIMIZED, 1, telemetry)
    }
}

class LinuxEnvironmentProfiler : EnvironmentProfiler {

    override suspend fun audit(): AgentBudget = withContext(Dispatchers.IO) {
        val cpuInfoText = File("/proc/cpuinfo").let { if (it.exists()) it.readText() else "" }
        val memInfoText = File("/proc/meminfo").let { if (it.exists()) it.readText() else "" }
        val sysBlock = File("/sys/block")

        // 1. Precise CPU Detection
        val physicalCores = cpuInfoText.lines()
            .filter { it.startsWith("core id") }
            .toSet()
            .size.coerceAtLeast(1)
        
        val logicalProcessors = cpuInfoText.lines()
            .filter { it.startsWith("processor") }
            .size.coerceAtLeast(1)

        val hasAvx512Vnni = cpuInfoText.contains("avx512_vnni")
        val hasAvx2 = cpuInfoText.contains("avx2")

        // 2. Dynamic RAM Telemetry
        val totalRamGb = parseMem(memInfoText, "MemTotal")
        val freeRamGb = parseMem(memInfoText, "MemAvailable").let { if (it == 0.0) parseMem(memInfoText, "MemFree") else it }

        // 3. Storage Performance
        val isNvme = if (sysBlock.exists() && sysBlock.isDirectory) {
            sysBlock.listFiles()?.any { it.name.startsWith("nvme") } ?: false
        } else false

        // 4. Heuristic GPU Detection (Linux-specific basics)
        val hasNvidia = File("/proc/driver/nvidia/version").exists() || 
                        runCatching { 
                            val process = Runtime.getRuntime().exec("nvidia-smi")
                            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 
                        }.getOrDefault(false)
        
        val telemetry = HardwareTelemetry(
            physicalCores = physicalCores,
            logicalProcessors = logicalProcessors,
            totalRamGb = totalRamGb,
            freeRamGb = freeRamGb,
            hasAvx512Vnni = hasAvx512Vnni,
            hasAvx2 = hasAvx2,
            isNvme = isNvme,
            hasGpu = hasNvidia,
            gpuType = if (hasNvidia) "NVIDIA" else null
        )

        calculateBudget(telemetry)
    }

    private fun parseMem(text: String, key: String): Double {
        return text.lines().find { it.startsWith("$key:") }?.let {
            val kb = it.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
            kb / 1024.0 / 1024.0
        } ?: 0.0
    }

    private fun calculateBudget(telemetry: HardwareTelemetry): AgentBudget {
        // Warn when below recommended specs; degrade to LOW_END instead of hard-failing.
        // llama.cpp works on CPUs without AVX2 (slower), and small models run on 4GB+.
        val belowRecommended = telemetry.totalRamGb < 4.0 || (!telemetry.hasAvx2 && !telemetry.hasGpu)
        if (belowRecommended) {
            println("[KOUPPER AGENTIC WARN]: Below recommended specs (4GB RAM + AVX2 or GPU). " +
                    "Running in LOW_END mode — inference will be slow. " +
                    "Detected: ${telemetry.totalRamGb}GB RAM, AVX2=${telemetry.hasAvx2}, GPU=${telemetry.hasGpu}")
        }

        // Tier selection — LOW_END is always a valid fallback
        val tier = when {
            telemetry.hasGpu -> HardwareTier.GPU_ACCELERATED
            telemetry.hasAvx512Vnni -> HardwareTier.CPU_OPTIMIZED // Alto performance CPU
            telemetry.hasAvx2 -> HardwareTier.CPU_OPTIMIZED      // Performance estándar
            else -> HardwareTier.LOW_END
        }

        // 3. Concurrencia Elástica (Sin techos arbitrarios)
        // Usamos el 70% de la RAM disponible para agentes, asumiendo 3GB por instancia 3B
        val maxConcurrentAgents = ((telemetry.freeRamGb * 0.7) / 3.0).toInt().coerceAtLeast(1)

        return AgentBudget(tier, maxConcurrentAgents, telemetry)
    }
}
