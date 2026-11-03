package com.funcodestudiocb.analyzer.engine.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {

    private val start = SessionStart(RESULT_SCHEMA_VERSION, "0.1.0", "a1", AnalysisMode.DIAGNOSTIC, listOf("diagnostic.channel"))

    private fun encode(vararg records: ResultRecord): ByteArray {
        val bytes = ByteArrayOutputStream()
        FrameWriter(bytes).use { writer -> records.forEach(writer::write) }
        return bytes.toByteArray()
    }

    private fun rawFrame(json: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            val payload = json.toByteArray()
            it.writeInt(payload.size)
            it.write(payload)
        }
        return bytes.toByteArray()
    }

    @Test
    fun `ida y vuelta conserva todos los tipos de registro`() {
        val records = listOf(
            start,
            StageEvent("diagnostic.channel", StageStatus.COMPLETED, 12),
            CoverageNote("diagnostic.channel", CoverageReason.UNSUPPORTED, "detalle", "ruta/archivo.kt"),
            IsolationReport(
                99001, 1234, "app:analyzer", true,
                ProbeResult(ProbeOutcome.BLOCKED, "EACCES"),
                ProbeResult(ProbeOutcome.BLOCKED, "EACCES"),
                ProbeResult(ProbeOutcome.INCONCLUSIVE, "no existe"),
            ),
            DiagnosticSample(0, "abc"),
            SessionEnd(SessionOutcome.COMPLETED, 5),
        )
        val reader = FrameReader(ByteArrayInputStream(encode(*records.toTypedArray())))
        val decoded = generateSequence { reader.next() }.toList()
        assertEquals(records, decoded)
    }

    @Test
    fun `flujo vacio termina limpiamente`() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).next())
    }

    @Test
    fun `cabecera truncada se rechaza`() {
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0, 0)))
        assertFailsWith<ProtocolViolationException> { reader.next() }
    }

    @Test
    fun `trama truncada se rechaza`() {
        val full = encode(start)
        val reader = FrameReader(ByteArrayInputStream(full.copyOf(full.size - 3)))
        assertFailsWith<ProtocolViolationException> { reader.next() }
    }

    @Test
    fun `longitud cero o negativa se rechaza`() {
        assertFailsWith<ProtocolViolationException> {
            FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))).next()
        }
        assertFailsWith<ProtocolViolationException> {
            FrameReader(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1))).next()
        }
    }

    @Test
    fun `trama mayor que el limite se rechaza sin reservar memoria`() {
        // Declara 2 GiB - 1 de longitud: debe rechazarse por la cabecera, sin intentar leerla.
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0x7f, -1, -1, -1)), FrameLimits(maxFrameBytes = 1024))
        val e = assertFailsWith<ProtocolViolationException> { reader.next() }
        assertTrue(e.message!!.contains("supera el máximo"))
    }

    @Test
    fun `se aplica el limite de tramas y de bytes totales`() {
        val data = encode(start, DiagnosticSample(0, "x"), DiagnosticSample(1, "y"))
        val byFrames = FrameReader(ByteArrayInputStream(data), FrameLimits(maxFrames = 2))
        byFrames.next(); byFrames.next()
        assertFailsWith<ProtocolViolationException> { byFrames.next() }

        val byBytes = FrameReader(ByteArrayInputStream(data), FrameLimits(maxTotalBytes = 50))
        assertFailsWith<ProtocolViolationException> {
            @Suppress("ControlFlowWithEmptyBody")
            while (byBytes.next() != null) {
            }
        }
    }

    @Test
    fun `json mal formado, tipo desconocido o claves extra se rechazan`() {
        listOf(
            "{no es json",
            """{"t":"desconocido"}""",
            """{"t":"sample","index":1,"payload":"x","extra":true}""",
            """{"t":"sample","index":"uno","payload":"x"}""",
        ).forEach { json ->
            assertFailsWith<ProtocolViolationException>(json) {
                FrameReader(ByteArrayInputStream(rawFrame(json))).next()
            }
        }
    }

    @Test
    fun `utf8 invalido se rechaza`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(3)
            it.write(byteArrayOf(0xC3.toByte(), 0x28, 0x41))
        }
        assertFailsWith<ProtocolViolationException> { FrameReader(ByteArrayInputStream(bytes.toByteArray())).next() }
    }

    @Test
    fun `el escritor no produce tramas mayores que su limite`() {
        val writer = FrameWriter(ByteArrayOutputStream(), maxFrameBytes = 64)
        assertFailsWith<IllegalArgumentException> { writer.write(DiagnosticSample(0, "x".repeat(200))) }
    }
}
