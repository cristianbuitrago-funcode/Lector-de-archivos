package com.funcodestudiocb.analyzer.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.engine.api.AnalysisLimits
import com.funcodestudiocb.analyzer.engine.api.ProbeOutcome
import com.funcodestudiocb.analyzer.service.client.AnalyzerClient
import com.funcodestudiocb.analyzer.service.client.IsolationDiagnostics
import com.funcodestudiocb.analyzer.service.client.RunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class IsolationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun elMotorCorreEnUnProcesoAisladoSinRedNiArchivos() = runBlocking {
        val report = IsolationDiagnostics(context).run()
        val isolation = assertNotNull(report.isolation, report.run.message)

        assertTrue(isolation.isolatedBySystem, "El servicio debe ejecutarse en un proceso aislado")
        assertNotEquals(android.os.Process.myUid(), isolation.uid, "El UID del proceso aislado debe ser distinto")
        assertNotEquals(ProbeOutcome.ALLOWED, isolation.network.outcome, isolation.network.detail)
        assertEquals(ProbeOutcome.BLOCKED, isolation.appFiles.outcome, isolation.appFiles.detail)
        assertNotEquals(ProbeOutcome.ALLOWED, isolation.sharedStorage.outcome, isolation.sharedStorage.detail)
        assertEquals(RunStatus.COMPLETED, report.run.status)
        assertTrue(report.allPassed, report.checks.joinToString("\n"))
    }

    @Test
    fun elVigilanteDetieneUnAnalisisBloqueado() = runBlocking {
        val client = AnalyzerClient(context, watchdogGraceMillis = 1_000, shutdownWaitMillis = 15_000)
        val started = System.nanoTime()
        val report = IsolationDiagnostics(context, client).run(
            sampleRecords = 10,
            simulateHangMillis = 120_000,
            limits = AnalysisLimits(maxTotalMillis = 3_000, maxStageMillis = 3_000),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(RunStatus.WATCHDOG_TIMEOUT, report.run.status)
        assertTrue(elapsedMs < 60_000, "El vigilante tardó demasiado: $elapsedMs ms")
        // Lo recibido antes del bloqueo se conserva (resultados parciales).
        assertNotNull(report.run.session?.start)
    }

    @Test
    fun cadaAnalisisUsaUnProcesoNuevo() = runBlocking {
        val first = assertNotNull(IsolationDiagnostics(context).run(sampleRecords = 1).isolation)
        val second = assertNotNull(IsolationDiagnostics(context).run(sampleRecords = 1).isolation)
        assertNotEquals(first.pid, second.pid)
    }
}
