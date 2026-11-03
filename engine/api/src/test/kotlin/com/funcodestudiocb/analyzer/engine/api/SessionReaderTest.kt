package com.funcodestudiocb.analyzer.engine.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReaderTest {

    private val start = SessionStart(RESULT_SCHEMA_VERSION, "0.1.0", "a1", AnalysisMode.DIAGNOSTIC, listOf("s1"))
    private val sample = DiagnosticSample(0, "x")

    private fun read(vararg records: ResultRecord): SessionReadResult {
        val bytes = ByteArrayOutputStream()
        FrameWriter(bytes).use { w -> records.forEach(w::write) }
        return SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray()))).readAll()
    }

    @Test
    fun `sesion correcta`() {
        val result = read(start, sample, SessionEnd(SessionOutcome.COMPLETED, 2))
        assertTrue(result.isComplete)
        assertEquals(listOf<ResultRecord>(sample), result.records)
    }

    @Test
    fun `sesion sin final se considera incompleta pero conserva lo recibido`() {
        val result = read(start, sample)
        assertFalse(result.isComplete)
        assertNull(result.violation)
        assertNull(result.end)
        assertEquals(1, result.records.size)
    }

    @Test
    fun `datos antes del inicio se rechazan`() {
        assertNotNull(read(sample).violation)
    }

    @Test
    fun `inicio duplicado se rechaza`() {
        assertNotNull(read(start, start).violation)
    }

    @Test
    fun `datos despues del final se rechazan`() {
        val result = read(start, SessionEnd(SessionOutcome.COMPLETED, 1), sample)
        assertNotNull(result.violation)
    }

    @Test
    fun `recuento inconsistente revela registros perdidos o inyectados`() {
        assertNotNull(read(start, sample, SessionEnd(SessionOutcome.COMPLETED, 5)).violation)
    }

    @Test
    fun `version de esquema desconocida se rechaza`() {
        assertNotNull(read(start.copy(schemaVersion = 999)).violation)
    }

    @Test
    fun `campos invalidos se rechazan`() {
        assertNotNull(read(start.copy(analysisId = "../etc")).violation)
        assertNotNull(read(start, DiagnosticSample(-1, "x")).violation)
        assertNotNull(read(start, StageEvent("s1", StageStatus.COMPLETED, -5)).violation)
        assertNotNull(read(start, StageEvent("s1", StageStatus.COMPLETED, 1, "a\u0000b")).violation)
        assertNotNull(read(start, DiagnosticSample(0, "x".repeat(RecordValidator.MAX_PAYLOAD_LENGTH + 1))).violation)
    }
}
