import com.koupper.shared.annotations.Export

/**
 * Example demonstrating Koupper v7's Process Sandbox isolation.
 * If you run this script with Koupper Sandbox enabled (koupper.sandbox.enabled=true),
 * the `System.exit(1)` call will not crash the Octopus daemon. The Sandbox will catch
 * the termination and report it cleanly back to the client.
 */
@Export
fun runSandboxTest(): String {
    println("[Sandbox Example] Iniciando ejecución de rutina crítica...")
    
    for (i in 1..3) {
        println("[Sandbox Example] Procesando paso $i...")
        Thread.sleep(500)
    }

    println("[Sandbox Example] Simulando fallo crítico con System.exit(1)...")
    
    // Este comando tumbaría el demonio en la v6.
    // En la v7, el ProcessSandbox lo aísla y simplemente falla el trabajo de este script.
    System.exit(1)
    
    return "Este punto nunca se alcanzará"
}
