package com.funcodestudiocb.analyzer.engine.api

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private val valid = AnalysisConfig(
        analysisId = "diag-1",
        mode = AnalysisMode.DIAGNOSTIC,
        diagnostic = DiagnosticOptions(canaryPath = "/data/user/0/app/files/canario.txt", appDataDir = "/data/user/0/app"),
    )

    @Test
    fun `configuracion valida`() {
        assertTrue(ConfigValidator.validate(valid).isEmpty())
    }

    @Test
    fun `identificadores y rutas peligrosas se rechazan`() {
        assertTrue(ConfigValidator.validate(valid.copy(analysisId = "a/b")).isNotEmpty())
        assertTrue(ConfigValidator.validate(valid.copy(analysisId = "")).isNotEmpty())
        listOf("relativa/ruta", "/data/../etc/passwd", "/con\u0000nulo", "/").forEach { path ->
            val config = valid.copy(diagnostic = valid.diagnostic!!.copy(canaryPath = path))
            assertTrue(ConfigValidator.validate(config).isNotEmpty(), path)
        }
    }

    @Test
    fun `limites fuera de rango se rechazan`() {
        assertTrue(ConfigValidator.validate(valid.copy(limits = AnalysisLimits(maxTotalMillis = 1))).isNotEmpty())
        assertTrue(
            ConfigValidator.validate(valid.copy(limits = AnalysisLimits(maxTotalMillis = 1_000, maxStageMillis = 5_000)))
                .isNotEmpty(),
        )
        val tooMany = valid.copy(diagnostic = valid.diagnostic!!.copy(sampleRecords = DiagnosticOptions.MAX_SAMPLE_RECORDS + 1))
        assertTrue(ConfigValidator.validate(tooMany).isNotEmpty())
    }

    @Test
    fun `el bloqueo simulado esta acotado a dos veces el limite total`() {
        val limits = AnalysisLimits(maxTotalMillis = 3_000, maxStageMillis = 3_000)
        val ok = valid.copy(limits = limits, diagnostic = valid.diagnostic!!.copy(simulateHangMillis = 6_000))
        assertTrue(ConfigValidator.validate(ok).isEmpty())
        val tooLong = ok.copy(diagnostic = ok.diagnostic!!.copy(simulateHangMillis = 6_001))
        assertTrue(ConfigValidator.validate(tooLong).isNotEmpty())
    }

    @Test
    fun `el modo diagnostico exige opciones y el completo aun no esta disponible`() {
        assertTrue(ConfigValidator.validate(valid.copy(diagnostic = null)).isNotEmpty())
        assertTrue(ConfigValidator.validate(valid.copy(mode = AnalysisMode.FULL)).isNotEmpty())
    }
}
