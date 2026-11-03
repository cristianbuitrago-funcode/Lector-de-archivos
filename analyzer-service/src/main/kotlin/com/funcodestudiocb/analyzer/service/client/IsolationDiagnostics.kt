package com.funcodestudiocb.analyzer.service.client

import android.content.Context
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisLimits
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.DiagnosticOptions
import com.funcodestudiocb.analyzer.engine.api.DiagnosticSample
import com.funcodestudiocb.analyzer.engine.api.IsolationReport
import com.funcodestudiocb.analyzer.engine.api.ProbeOutcome
import com.funcodestudiocb.analyzer.engine.api.ProbeResult
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import com.funcodestudiocb.analyzer.engine.core.SyntheticRecordsStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** Resultado de una comprobación individual del diagnóstico. */
data class DiagnosticCheck(
    val title: String,
    val passed: Boolean,
    /** `true` si el resultado no demuestra nada (ni éxito ni fallo). */
    val inconclusive: Boolean,
    val detail: String,
    /** Comprobación imprescindible: un resultado no concluyente no basta para dar el aislamiento por verificado. */
    val required: Boolean = true,
)

data class DiagnosticsReport(
    val checks: List<DiagnosticCheck>,
    val run: AnalyzerRun,
    val isolation: IsolationReport?,
) {
    /** Todas las comprobaciones imprescindibles pasaron y ninguna opcional falló de forma concluyente. */
    val allPassed: Boolean get() = checks.all { if (it.required) it.passed else it.passed || it.inconclusive }
}

/**
 * Autodiagnóstico del aislamiento del motor, ejecutable desde Ajustes y desde las pruebas instrumentadas.
 *
 * Comprueba: que el análisis corre en un proceso aislado del sistema, que ese proceso no puede usar la
 * red ni leer los archivos de la app ni el almacenamiento compartido, y que el canal de resultados
 * entrega todos los registros íntegros y en orden.
 */
class IsolationDiagnostics(
    private val context: Context,
    private val client: AnalyzerClient = AnalyzerClient(context),
) {

    suspend fun run(
        sampleRecords: Int = DEFAULT_SAMPLES,
        simulateHangMillis: Long = 0,
        limits: AnalysisLimits = AnalysisLimits(maxTotalMillis = 60_000, maxStageMillis = 60_000),
        onProgress: (ProgressUpdate) -> Unit = {},
    ): DiagnosticsReport {
        val canary = withContext(Dispatchers.IO) { writeCanary() }
        try {
            val config = AnalysisConfig(
                analysisId = "diag-" + UUID.randomUUID().toString().take(8),
                mode = AnalysisMode.DIAGNOSTIC,
                limits = limits,
                diagnostic = DiagnosticOptions(
                    canaryPath = canary.absolutePath,
                    appDataDir = context.applicationInfo.dataDir,
                    sampleRecords = sampleRecords,
                    simulateHangMillis = simulateHangMillis,
                ),
            )
            val run = client.run(config, onProgress)
            return evaluate(run, sampleRecords)
        } finally {
            withContext(Dispatchers.IO) { canary.delete() }
        }
    }

    private fun writeCanary(): File {
        val dir = File(context.filesDir, "diagnostico").apply { mkdirs() }
        val file = File(dir, "canario-${UUID.randomUUID()}.bin")
        file.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return file
    }

    private fun evaluate(run: AnalyzerRun, expectedSamples: Int): DiagnosticsReport {
        val records = run.session?.records.orEmpty()
        val isolation = records.filterIsInstance<IsolationReport>().firstOrNull()
        val checks = mutableListOf<DiagnosticCheck>()

        checks += DiagnosticCheck(
            title = "Proceso aislado por el sistema",
            passed = isolation?.isolatedBySystem == true,
            inconclusive = false,
            detail = isolation?.let { "UID ${it.uid}, proceso ${it.processName}" } ?: "No se recibió el informe de aislamiento",
        )
        checks += probeCheck("Sin acceso a la red", isolation?.network)
        checks += probeCheck("Sin acceso a los archivos de la app", isolation?.appFiles)
        // Puede no ser concluyente (p. ej. sin almacenamiento compartido montado); el resto sí es imprescindible.
        checks += probeCheck("Sin acceso al almacenamiento compartido", isolation?.sharedStorage).copy(required = false)

        val samples = records.filterIsInstance<DiagnosticSample>()
        val intact = samples.size == expectedSamples &&
            samples.withIndex().all { (i, s) -> s.index == i && s.payload == SyntheticRecordsStage.expectedPayload(i) }
        checks += DiagnosticCheck(
            title = "Canal de resultados íntegro",
            passed = intact && run.status == RunStatus.COMPLETED && run.session?.end?.outcome == SessionOutcome.COMPLETED,
            inconclusive = false,
            detail = "${samples.size} de $expectedSamples registros verificados; estado: ${run.status}" +
                (run.message?.let { ". $it" } ?: ""),
        )
        return DiagnosticsReport(checks, run, isolation)
    }

    private fun probeCheck(title: String, probe: ProbeResult?): DiagnosticCheck = when (probe?.outcome) {
        ProbeOutcome.BLOCKED -> DiagnosticCheck(title, passed = true, inconclusive = false, detail = probe.detail)
        ProbeOutcome.ALLOWED -> DiagnosticCheck(title, passed = false, inconclusive = false, detail = probe.detail)
        ProbeOutcome.INCONCLUSIVE -> DiagnosticCheck(title, passed = false, inconclusive = true, detail = probe.detail)
        null -> DiagnosticCheck(title, passed = false, inconclusive = false, detail = "Sin datos")
    }

    companion object {
        const val DEFAULT_SAMPLES = 500
    }
}
