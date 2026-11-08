package com.funcodestudiocb.analyzer.engine.core

/**
 * Desglose del tiempo de una etapa por partes, para el Anexo A del informe: dice en qué se fue el tiempo en el
 * teléfono, donde no se puede perfilar. Mide **tiempo exclusivo**: lo que tarda una parte anidada (por ejemplo, enviar
 * un registro mientras se examina un archivo) se cuenta en ella y no en la que la contiene. Solo guarda nombres de
 * partes escritos por el motor, nunca datos del proyecto.
 *
 * Cada etapa usa el suyo y las etapas se ejecutan una tras otra; el cerrojo solo protege el recuento si una corrutina
 * se reanuda en otro hilo.
 */
class StageTimings {

    private val lock = Any()
    private val nanos = LinkedHashMap<String, Long>()
    private var current: String? = null
    private var since = 0L

    /** Nombre de lo que no se midió por partes (lo que la etapa hace por sí misma). */
    var restLabel: String = "resto de la etapa"

    fun <T> measure(part: String, block: () -> T): T {
        val outer = synchronized(lock) {
            val outer = current
            val start = System.nanoTime()
            if (outer != null) add(outer, start - since)
            current = part
            since = start
            outer
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                val end = System.nanoTime()
                add(part, end - since)
                current = outer
                since = end
            }
        }
    }

    private fun add(part: String, delta: Long) {
        nanos[part] = (nanos[part] ?: 0L) + delta
    }

    /** Milisegundos por parte, de la que más tardó a la que menos. */
    fun parts(): List<Pair<String, Long>> = synchronized(lock) { nanos.entries.map { it.key to it.value / 1_000_000 } }.sortedByDescending { it.second }

    /**
     * Texto del desglose para una etapa que tardó [elapsedMs], o `null` si no hay nada que desglosar (solo el envío de
     * resultados, o una etapa de menos de un segundo).
     */
    fun describe(elapsedMs: Long): String? {
        val parts = parts()
        if (elapsedMs < MIN_ELAPSED_MS || parts.none { it.first != SEND }) return null
        val rest = (elapsedMs - parts.sumOf { it.second }).coerceAtLeast(0)
        return (parts + (restLabel to rest)).filter { it.second > 0 }.sortedByDescending { it.second }
            .joinToString("; ") { (part, ms) -> "$part ${format(ms)} ms" }
    }

    private fun format(ms: Long): String = String.format(java.util.Locale.ROOT, "%,d", ms).replace(',', '.')

    companion object {
        /** Desglose de la etapa en curso (el ejecutor pone uno nuevo antes de cada etapa). */
        val KEY = AttributeKey<StageTimings>("core.timings")

        /** Tiempo dedicado a entregar registros (en el teléfono, escribirlos en la tubería hacia la app). */
        const val SEND = "envío de resultados a la app"

        private const val MIN_ELAPSED_MS = 1_000L
    }
}
