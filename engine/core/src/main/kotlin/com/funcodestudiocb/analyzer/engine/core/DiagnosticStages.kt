package com.funcodestudiocb.analyzer.engine.core

import com.funcodestudiocb.analyzer.engine.api.DiagnosticSample
import java.security.MessageDigest

/**
 * Etapa de diagnóstico: emite registros sintéticos y verificables para comprobar el canal de
 * resultados entre procesos (orden, integridad y recuento).
 */
class SyntheticRecordsStage : Stage {
    override val id: String = "diagnostic.channel"
    override val title: String = "Prueba del canal de resultados"

    override suspend fun run(context: StageContext) {
        val count = context.config.diagnostic?.sampleRecords ?: 0
        for (i in 0 until count) {
            context.emit(DiagnosticSample(i, expectedPayload(i)))
            if (i % 50 == 0) {
                context.checkActive()
                context.progress(i.toFloat() / count, "Registro ${i + 1} de $count")
            }
        }
    }

    companion object {
        /** Contenido esperado del registro [index]; el receptor lo recalcula para verificar la integridad. */
        fun expectedPayload(index: Int): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("funcode-diagnostico-$index".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Etapa de diagnóstico que se bloquea SIN cooperar con la cancelación (solo pruebas).
 * Sirve para comprobar que el vigilante del proceso principal destruye el proceso aislado.
 */
class SimulatedHangStage : Stage {
    override val id: String = "diagnostic.hang"
    override val title: String = "Bloqueo simulado"

    override suspend fun run(context: StageContext) {
        val millis = context.config.diagnostic?.simulateHangMillis ?: 0
        if (millis > 0) {
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(millis)
        }
    }
}
