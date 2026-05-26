package com.koupper.providers.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface EnvironmentProfiler {
    suspend fun audit(): AgentBudget
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
                        runCatching { Runtime.getRuntime().exec("nvidia-smi").waitFor() == 0 }.getOrDefault(false)
        
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
        // 1. Verificación de Viabilidad (The "Kill Switch")
        val isUnviable = telemetry.totalRamGb < 4.0 || (!telemetry.hasAvx2 && !telemetry.hasGpu)
        
        if (isUnviable) {
            // Notificamos explícitamente la imposibilidad de ejecución
            throw IllegalStateException("""
                [KOUPPER AGENTIC ERROR]: Hardware insuficiente para inicializar el núcleo.
                Mínimo requerido: 4GB RAM + (AVX2 o GPU).
                Detectado: ${telemetry.totalRamGb}GB RAM, AVX2: ${telemetry.hasAvx2}, GPU: ${telemetry.hasGpu}
            """.trimIndent())
        }

        // 2. Escalabilidad Proporcional Pura
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
