package com.funcodestudiocb.analyzer.analysis

import android.content.Context
import android.os.ParcelFileDescriptor
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import com.funcodestudiocb.analyzer.service.client.AnalyzerClient
import com.funcodestudiocb.analyzer.service.client.RunStatus
import java.io.File

/** Cómo terminó una ejecución del motor vista por la app. */
data class EngineRun(
    val sessionOutcome: SessionOutcome?,
    /** Descripción del problema si el motor no terminó con normalidad (resultados parciales). */
    val problem: String?,
)

/** Ejecuta el motor. En la app, siempre en el proceso aislado; en las pruebas puede sustituirse. */
interface AnalysisEngine {
    /**
     * Analiza [archive]. Cada registro ya validado llega por [onRecord] en orden y a medida que se produce
     * (no se acumulan aquí: la copia del código puede ocupar decenas de MB).
     * [vulnDb] es el índice de vulnerabilidades descargado, o `null` si no hay ninguno.
     */
    suspend fun analyze(
        config: AnalysisConfig,
        archive: File,
        password: CharArray?,
        vulnDb: File?,
        onProgress: (ProgressUpdate) -> Unit,
        onRecord: (ResultRecord) -> Unit,
    ): EngineRun
}

/** Motor en el proceso aislado: el comprimido y la base de vulnerabilidades viajan como descriptores de solo lectura. */
class IsolatedAnalysisEngine(context: Context) : AnalysisEngine {
    private val client = AnalyzerClient(context)

    override suspend fun analyze(
        config: AnalysisConfig,
        archive: File,
        password: CharArray?,
        vulnDb: File?,
        onProgress: (ProgressUpdate) -> Unit,
        onRecord: (ResultRecord) -> Unit,
    ): EngineRun {
        val descriptor = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        // Si la base desapareció entre medias (se borró), el análisis sigue y el informe dice que no hubo base.
        val database = vulnDb?.let { runCatching { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
        val run = client.run(config, descriptor, password, database, onProgress, bufferRecords = false, onRecord = onRecord)
        val problem = when (run.status) {
            RunStatus.COMPLETED -> null
            RunStatus.INCOMPLETE -> "El proceso de análisis terminó de forma inesperada (posible falta de memoria). Los resultados son parciales."
            RunStatus.PROTOCOL_VIOLATION -> "El motor envió datos que no superan la validación y se descartaron: ${run.message.orEmpty()}"
            RunStatus.WATCHDOG_TIMEOUT -> "Se superó el tiempo máximo del análisis y se detuvo el proceso aislado. Los resultados son parciales."
            RunStatus.BIND_FAILED -> "No se pudo iniciar el proceso de análisis aislado."
            RunStatus.REJECTED -> "El proceso de análisis rechazó la petición: ${run.message.orEmpty()}"
        }
        return EngineRun(run.session?.end?.outcome, problem)
    }
}
