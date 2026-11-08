package com.funcodestudiocb.analyzer.engine.core

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisLimits
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DiagnosticOptions
import com.funcodestudiocb.analyzer.engine.api.DiagnosticSample
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.SessionStart
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PipelineTest {

    private val config = AnalysisConfig(
        analysisId = "t1",
        mode = AnalysisMode.DIAGNOSTIC,
        limits = AnalysisLimits(maxTotalMillis = 60_000, maxStageMillis = 10_000),
        diagnostic = DiagnosticOptions("/a/b", "/a", sampleRecords = 3),
    )

    private class TestStage(
        override val id: String,
        override val dependsOn: Set<String> = emptySet(),
        private val body: suspend (StageContext) -> Unit = {},
    ) : Stage {
        override val title: String = "Etapa $id"
        override suspend fun run(context: StageContext) = body(context)
    }

    private fun stageEvents(sink: CollectingSink) = sink.records.filterIsInstance<StageEvent>().associate { it.stageId to it.status }

    @Test
    fun `el registro ordena por dependencias y detecta errores de definicion`() {
        val order = StageRegistry()
            .register(TestStage("c", setOf("b")))
            .register(TestStage("a"))
            .register(TestStage("b", setOf("a")))
            .plan()
            .map { it.id }
        assertEquals(listOf("a", "b", "c"), order)

        assertFailsWith<PipelineDefinitionException> { StageRegistry().register(TestStage("a")).register(TestStage("a")) }
        assertFailsWith<PipelineDefinitionException> { StageRegistry().register(TestStage("a", setOf("x"))).plan() }
        assertFailsWith<PipelineDefinitionException> {
            StageRegistry().register(TestStage("a", setOf("b"))).register(TestStage("b", setOf("a"))).plan()
        }
        assertFailsWith<PipelineDefinitionException> { StageRegistry().register(TestStage("../x")) }
    }

    @Test
    fun `sesion completa con inicio, final y recuento correcto`() = runTest {
        val sink = CollectingSink()
        val result = PipelineRunner("test").run(config, listOf(SyntheticRecordsStage()), sink)

        assertEquals(SessionOutcome.COMPLETED, result.outcome)
        val records = sink.records
        assertIs<SessionStart>(records.first())
        val end = assertIs<SessionEnd>(records.last())
        assertEquals(records.size - 1L, end.recordCount)
        assertEquals(3, records.filterIsInstance<DiagnosticSample>().size)
        assertEquals(SyntheticRecordsStage.expectedPayload(2), records.filterIsInstance<DiagnosticSample>()[2].payload)
    }

    @Test
    fun `un fallo se aisla, omite dependientes y no expone el mensaje de la excepcion`() = runTest {
        val sink = CollectingSink()
        val stages = listOf(
            TestStage("ok1"),
            TestStage("boom") { error("secreto AKIAABCDEFGHIJKLMNOP en el mensaje") },
            TestStage("dependiente", setOf("boom")),
            TestStage("ok2"),
        )
        val result = PipelineRunner("test").run(config, stages, sink)

        assertEquals(SessionOutcome.PARTIAL, result.outcome)
        val statuses = stageEvents(sink)
        assertEquals(StageStatus.COMPLETED, statuses["ok1"])
        assertEquals(StageStatus.FAILED, statuses["boom"])
        assertEquals(StageStatus.SKIPPED, statuses["dependiente"])
        assertEquals(StageStatus.COMPLETED, statuses["ok2"])
        val coverage = sink.records.filterIsInstance<CoverageNote>().map { it.reason }
        assertTrue(CoverageReason.STAGE_FAILED in coverage && CoverageReason.STAGE_SKIPPED in coverage)
        assertTrue(sink.records.none { it.toString().contains("AKIA") }, "El mensaje de la excepción no debe salir del motor")
    }

    @Test
    fun `una cancelacion interna de la etapa cuenta como fallo, no como cancelacion del analisis`() = runTest {
        val sink = CollectingSink()
        val stages = listOf(
            TestStage("interna") { withTimeout(10) { delay(1_000) } },
            TestStage("siguiente"),
        )
        val result = PipelineRunner("test").run(config, stages, sink)
        assertEquals(StageStatus.FAILED, result.statuses["interna"])
        assertEquals(StageStatus.COMPLETED, result.statuses["siguiente"])
    }

    @Test
    fun `tiempo maximo por etapa`() = runTest {
        val sink = CollectingSink()
        val limited = config.copy(limits = AnalysisLimits(maxTotalMillis = 60_000, maxStageMillis = 1_000))
        val result = PipelineRunner("test").run(
            limited,
            listOf(TestStage("lenta") { delay(5_000) }, TestStage("siguiente")),
            sink,
        )
        assertEquals(StageStatus.TIMED_OUT, result.statuses["lenta"])
        assertEquals(StageStatus.COMPLETED, result.statuses["siguiente"])
        assertEquals(SessionOutcome.PARTIAL, result.outcome)
    }

    @Test
    fun `tiempo maximo total marca las etapas pendientes`() = runTest {
        val sink = CollectingSink()
        val limited = config.copy(limits = AnalysisLimits(maxTotalMillis = 2_000, maxStageMillis = 2_000))
        val result = PipelineRunner("test").run(
            limited,
            listOf(TestStage("a") { delay(1_500) }, TestStage("b") { delay(1_500) }, TestStage("c")),
            sink,
        )
        assertEquals(SessionOutcome.TIMED_OUT, result.outcome)
        assertEquals(StageStatus.COMPLETED, result.statuses["a"])
        assertEquals(StageStatus.TIMED_OUT, result.statuses["b"])
        assertEquals(StageStatus.TIMED_OUT, result.statuses["c"])
        assertIs<SessionEnd>(sink.records.last())
    }

    @Test
    fun `la cancelacion externa cierra la sesion`() = runTest {
        val sink = CollectingSink()
        val started = CompletableDeferred<Unit>()
        val job = async {
            PipelineRunner("test").run(
                config,
                listOf(TestStage("espera") { started.complete(Unit); awaitCancellation() }, TestStage("b")),
                sink,
            )
        }
        started.await()
        job.cancelAndJoin()
        val end = assertIs<SessionEnd>(sink.records.last())
        assertEquals(SessionOutcome.CANCELLED, end.outcome)
        assertEquals(StageStatus.CANCELLED, stageEvents(sink)["b"])
    }

    @Test
    fun `el progreso global es monotono y llega a 1`() = runTest {
        val updates = mutableListOf<ProgressUpdate>()
        PipelineRunner("test").run(
            config.copy(diagnostic = config.diagnostic!!.copy(sampleRecords = 500)),
            listOf(TestStage("a"), SyntheticRecordsStage()),
            CollectingSink(),
        ) { updates += it }
        val overall = updates.map { it.overallFraction }
        assertEquals(overall.sorted(), overall)
        assertEquals(1f, overall.last())
    }

    @Test
    fun `la salida del pipeline supera el lector de sesiones del proceso principal`() = runTest {
        val bytes = ByteArrayOutputStream()
        val writer = FrameWriter(bytes)
        PipelineRunner("0.1.0").run(config, listOf(SyntheticRecordsStage()), writer::write)
        writer.close()
        val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray()))).readAll()
        assertTrue(session.isComplete, session.violation ?: "")
        assertEquals(SessionOutcome.COMPLETED, session.end!!.outcome)
    }
}

class AttributesTest {
    private val key = AttributeKey<String>("dato")

    @Test
    fun `las etapas comparten datos y los recursos se cierran al final aunque haya fallos`() = runTest {
        val attributes = Attributes()
        var closed = 0
        val stages = listOf(
            object : Stage {
                override val id = "a"
                override val title = "A"
                override suspend fun run(context: StageContext) {
                    context.attributes[key] = "hola"
                    context.attributes.closeLater(AutoCloseable { closed++ })
                }
            },
            object : Stage {
                override val id = "b"
                override val title = "B"
                override suspend fun run(context: StageContext) {
                    assertEquals("hola", context.attributes.require(key))
                    error("fallo")
                }
            },
        )
        val config = AnalysisConfig("t", AnalysisMode.DIAGNOSTIC, diagnostic = DiagnosticOptions("/a/b", "/a"))
        PipelineRunner("t").run(config, stages, CollectingSink(), attributes = attributes)
        assertEquals(1, closed)
        assertFailsWith<IllegalStateException> { Attributes().require(key) }
    }
}
