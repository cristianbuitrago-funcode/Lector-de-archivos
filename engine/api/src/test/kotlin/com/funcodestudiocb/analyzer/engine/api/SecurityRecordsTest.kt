package com.funcodestudiocb.analyzer.engine.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityRecordsTest {

    private fun finding() = SecurityFinding(
        ruleId = "FC-SQLI-01", ruleVersion = 1, name = "Consulta SQL construida con datos variables", category = SecurityCategory.SQL_INJECTION,
        cwe = 89, owasp = "A03:2021 Inyección", path = "src/db.js", line = 8, severity = Severity.HIGH, certainty = Certainty.PROBABLE,
        explanation = "La consulta se construye concatenando.", consequences = "Se puede leer la base de datos.", snippet = "db.query(sql)", snippetLine = 8,
        recommendation = "Usa parámetros.", fixedExample = "db.query('… ?', [id])", references = listOf("https://cwe.mitre.org/data/definitions/89.html"),
        flow = listOf(FlowStep(7, "Se construye con req.params"), FlowStep(8, "Llega a la consulta")),
    )

    private val mask = RecordValidator.MASK_CHAR.toString()

    @Test
    fun `los hallazgos y el resumen van y vuelven sin cambios y son validos`() {
        val secret = finding().copy(
            ruleId = "FC-SECRET-02", category = SecurityCategory.EXPOSED_SECRET, cwe = 798, secretType = SecretType.STRIPE_KEY,
            masked = "sk_l" + mask.repeat(12) + "wx", fingerprint = "0123456789abcdef0123456789abcdef", downgraded = "archivo de pruebas",
        )
        val records = listOf(
            finding(),
            secret,
            finding().copy(path = null, line = null, snippet = null, snippetLine = null, certainty = Certainty.DYNAMIC),
            SecuritySummary(
                2, mapOf(Severity.HIGH to 2), mapOf(Certainty.PROBABLE to 1, Certainty.CONFIRMED to 1), mapOf(SecurityCategory.SQL_INJECTION to 2),
                rules = 61, filesExamined = 10, downgraded = 1, suppressions = 0, dynamicChecks = listOf("Control de acceso real"),
                notEvaluated = listOf("Dependencias vulnerables (Fase 7)"),
            ),
        )
        records.forEach {
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), it)
            assertEquals(it, ProtocolJson.decodeFromString(ResultRecord.serializer(), json))
            assertEquals(emptyList(), RecordValidator.validate(it), "$it")
        }
    }

    @Test
    fun `se rechazan fichas incompletas o que muestran demasiado de un secreto`() {
        val bad = listOf(
            finding().copy(ruleId = "SQLI"),
            finding().copy(explanation = " "),
            finding().copy(recommendation = ""),
            finding().copy(snippetLine = null),
            finding().copy(snippet = (1..20).joinToString("\n") { "línea $it" }),
            finding().copy(masked = "sk_live_51Habcd" + mask.repeat(4)),
            finding().copy(fingerprint = "no-es-una-huella"),
            finding().copy(flow = List(RecordValidator.MAX_FLOW_STEPS + 1) { FlowStep(1, "paso") }),
            finding().copy(cwe = 0),
            finding().copy(line = 0),
        )
        bad.forEach { assertTrue(RecordValidator.validate(it).isNotEmpty(), "Debería rechazarse: $it") }
        val summary = SecuritySummary(3, mapOf(Severity.HIGH to 2), emptyMap(), emptyMap(), 61, 1, 0, 0, emptyList(), emptyList())
        assertTrue(RecordValidator.validate(summary).isNotEmpty(), "Totales por severidad incoherentes")
    }

    @Test
    fun `la clave de huellas se valida y no aparece al imprimir la configuracion`() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val ok = AnalysisConfig("a1", AnalysisMode.FULL, ingest = IngestOptions("p.zip"), security = SecurityOptions(key))
        assertEquals(emptyList(), ConfigValidator.validate(ok))
        assertTrue(!ok.toString().contains(key))
        assertTrue(ConfigValidator.validate(ok.copy(security = SecurityOptions("corta"))).isNotEmpty())
        assertTrue(ConfigValidator.validate(ok.copy(security = SecurityOptions("x".repeat(43) + "\n"))).isNotEmpty())
    }
}
