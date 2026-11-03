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

/** Resultado de una ejecución del motor visto por la app: registros ya validados y, si lo hubo, el problema. */
data class EngineRun(
    val records: List<ResultRecord>,
    val sessionOutcome: SessionOutcome?,
    /** Descripción del problema si el motor no terminó con normalidad (resultados parciales). */
    val problem: String?,
)

/** Ejecuta el motor. En la app, siempre en el proceso aislado; en las pruebas puede sustituirse. */
interface AnalysisEngine {
    suspend fun analyze(
        config: AnalysisConfig,
        archive: File,
        password: CharArray?,
        onProgress: (ProgressUpdate) -> Unit,
    ): EngineRun
}

/** Motor en el proceso aislado: el comprimido viaja como descriptor de solo lectura. */
class IsolatedAnalysisEngine(context: Context) : AnalysisEngine {
    private val client = AnalyzerClient(context)

    override suspend fun analyze(
        config: AnalysisConfig,
        archive: File,
        password: CharArray?,
        onProgress: (ProgressUpdate) -> Unit,
    ): EngineRun {
        val records = ArrayList<ResultRecord>()
        val descriptor = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val run = client.run(config, descriptor, password, onProgress, bufferRecords = false) { records += it }
        val problem = when (run.status) {
            RunStatus.COMPLETED -> null
            RunStatus.INCOMPLETE -> "El proceso de análisis terminó de forma inesperada (posible falta de memoria). Los resultados son parciales."
            RunStatus.PROTOCOL_VIOLATION -> "El motor envió datos que no superan la validación y se descartaron: ${run.message.orEmpty()}"
            RunStatus.WATCHDOG_TIMEOUT -> "Se superó el tiempo máximo del análisis y se detuvo el proceso aislado. Los resultados son parciales."
            RunStatus.BIND_FAILED -> "No se pudo iniciar el proceso de análisis aislado."
            RunStatus.REJECTED -> "El proceso de análisis rechazó la petición: ${run.message.orEmpty()}"
        }
        return EngineRun(records, run.session?.end?.outcome, problem)
    }
}
