package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.DebtKind
import com.funcodestudiocb.analyzer.engine.api.FileMetrics
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.MaintenanceIndex
import com.funcodestudiocb.analyzer.engine.api.MetricsSummary
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.audit.deps.DependencyAuditStage
import com.funcodestudiocb.analyzer.engine.audit.deps.RuntimeCollector
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyCollector
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyStage
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.inventory.ContentCollector
import com.funcodestudiocb.analyzer.engine.inventory.InventoryPipeline
import com.funcodestudiocb.analyzer.engine.relations.RelationCollector
import com.funcodestudiocb.analyzer.engine.relations.RelationStage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MetricsTest {

    @TempDir
    lateinit var dir: File

    /** Análisis con el pipeline real (ingesta, inventario, lenguajes, dependencias, relaciones y métricas). */
    private fun analyze(withRelations: Boolean, vararg entries: Pair<String, String>): List<ResultRecord> {
        val file = File(dir, "proyecto-${entries.size}-$withRelations.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
        val dependencies = DependencyCollector()
        val relations = RelationCollector()
        val runtimes = RuntimeCollector()
        val metrics = MetricsCollector()
        val listeners = if (withRelations) listOf(dependencies, relations, runtimes, metrics) else listOf(dependencies, runtimes, metrics)
        var registry = InventoryPipeline.registry(ContentCollector(listeners)).register(DependencyStage(dependencies))
        if (withRelations) registry = registry.register(RelationStage(relations))
        val stages = registry.register(DependencyAuditStage(runtimes)).register(MetricsStage(metrics)).plan()
        FileInputStream(file).use { stream ->
            val attributes = Attributes()
            attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
            attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
            val bytes = ByteArrayOutputStream()
            val writer = FrameWriter(bytes)
            runBlocking { PipelineRunner("test").run(AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name)), stages, writer::write, attributes) }
            writer.close()
            val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
            if (session.violation != null) fail("Flujo inválido: ${session.violation}")
            session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: ${RecordValidator.validate(it)} en $it") }
            val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
            assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
            return session.records
        }
    }

    private val repeated = """
        const total = pedido.lineas.reduce((s, l) => s + l.precio * l.cantidad, 0);
        const impuestos = total * configuracion.iva;
        const envio = total > 50 ? 0 : tarifas.envioBasico;
        registro.info('calculado', { total, impuestos, envio });
        const resultado = { total, impuestos, envio, moneda: 'EUR' };
        cache.guardar(pedido.id, resultado);
        return resultado;
    """.trimIndent()

    private val project = arrayOf(
        "tienda/package.json" to """{"name": "tienda", "dependencies": {"express": "4.19.2"}, "devDependencies": {"prettier": "3.3.0"}}""",
        "tienda/README.md" to "# Tienda\n",
        "tienda/.eslintrc.json" to """{"extends": "eslint:recommended"}""",
        "tienda/.github/workflows/ci.yml" to "name: CI\non: push\njobs: {}\n",
        "tienda/src/pedidos.js" to """
            // TODO: separar la validación del cálculo
            export function total(pedido) {
              let t = 0;
              for (const l of pedido.lineas) {
                if (l.cantidad > 0 && l.precio > 0) t += l.cantidad * l.precio;
                else if (l.regalo) t += 0;
              }
              return pedido.descuento ? t * 0.9 : t;
            }
        """,
        "tienda/src/docs.js" to """
            /**
             * Las marcas TODO, FIXME o HACK solo se mencionan aquí: no son deuda.
             */
            export const nada = () => null;
        """,
        "tienda/src/a.js" to "import { b } from './b';\nexport function calcularA(pedido) {\n${"$"}{REPEATED}\n}\n",
        "tienda/src/b.js" to "import { a } from './a';\nexport function calcularB(pedido) {\n${"$"}{REPEATED}\n}\n",
        "tienda/test/pedidos.test.js" to """
            import { total } from '../src/pedidos';
            test('suma', () => { expect(total({ lineas: [] })).toBe(0); });
        """,
    )

    private fun entries() = project.map { (name, text) -> name to text.replace("${"$"}{REPEATED}", repeated) }.toTypedArray()

    @Test
    fun `metricas del proyecto, deuda, duplicacion, herramientas, ciclos e indice`() {
        val records = analyze(true, *entries())
        val files = records.filterIsInstance<FileMetrics>().associateBy { it.path }
        val pedidos = files.getValue("tienda/src/pedidos.js")
        assertTrue(pedidos.measured)
        assertEquals(1, pedidos.functions)
        assertEquals(6, pedidos.maxComplexity, "1 + for + if + && + else if + ternario")
        assertEquals(1, pedidos.debtMarkers)
        assertTrue(files.getValue("tienda/test/pedidos.test.js").test)
        assertEquals(7, files.getValue("tienda/src/a.js").duplicatedLines)
        assertEquals(7, files.getValue("tienda/src/b.js").duplicatedLines)

        val s = records.filterIsInstance<MetricsSummary>().single()
        assertEquals(1, s.testFiles)
        assertEquals(mapOf(DebtKind.TODO to 1), s.debtByKind, "La documentación que menciona las marcas no cuenta")
        assertEquals("tienda/src/pedidos.js", s.debtSamples.single().path)
        assertTrue(s.debtSamples.single().text.startsWith("TODO: separar"))
        assertEquals("total", s.complexFunctionList.first().name)
        assertEquals(6, s.complexFunctionList.first().complexity)
        val block = s.duplicates.single()
        assertEquals(7, block.lines)
        assertEquals(setOf("tienda/src/a.js:3", "tienda/src/b.js:3"), block.occurrences.toSet())
        assertEquals(setOf("ESLint", "GitHub Actions", "Prettier"), s.qualityTools.map { it.name }.toSet())
        assertEquals("tienda/README.md", s.readme)
        assertEquals(2, s.filesInCycles, "a.js y b.js se importan entre sí")
        assertEquals(1, s.cycles)

        val index = records.filterIsInstance<MaintenanceIndex>().single()
        val parts = index.parts.associateBy { it.id }
        assertEquals(100, parts.getValue("tools").score, "Analizador, integración continua y formateador")
        assertNull(parts.getValue("dependencies").score, "Sin base de vulnerabilidades no se evalúa")
        assertTrue(parts.getValue("structure").score!! < 100)
        assertNotNull(index.score)
        assertEquals(MaintenanceCalculator.level(index.score!!), index.level)
        assertTrue(index.notes.any { it.startsWith("Salud de dependencias: no evaluado") })
        assertTrue(s.qualityTools.any { it.kind == QualityToolKind.CI && it.path == "tienda/.github/workflows/ci.yml" })
    }

    @Test
    fun `sin relaciones la estructura no se evalua y lo dice`() {
        val records = analyze(false, *entries())
        val index = records.filterIsInstance<MaintenanceIndex>().single()
        assertNull(index.parts.first { it.id == "structure" }.score)
        val s = records.filterIsInstance<MetricsSummary>().single()
        assertTrue(s.notes.any { "relaciones" in it }, s.notes.toString())
        assertEquals(0, s.cycles)
    }
}
