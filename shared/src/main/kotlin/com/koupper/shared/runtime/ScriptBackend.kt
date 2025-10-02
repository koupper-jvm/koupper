package com.koupper.shared.runtime

interface ScriptBackend {
    /**
     * Evalúa un script (código completo en formato .kts).
     * Devuelve el resultado bruto de la evaluación.
     */
    fun eval(code: String): Any?

    /**
     * Obtiene un símbolo (propiedad/lambda exportada) del último script evaluado.
     * Devuelve null si no existe.
     */
    fun getSymbol(symbol: String): Any?

    /**
     * ClassLoader asociado al backend de scripting.
     */
    val classLoader: ClassLoader
}
