package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.CollectingSink
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.pipeline.FullPipeline
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Informes de análisis reales: secciones, trazabilidad, HTML seguro, JSON completo y comparación entre versiones. */
class ReportTest {

    @TempDir
    lateinit var dir: File

    /** Contraseña escrita en el código del proyecto de prueba: no puede aparecer completa en ningún informe. */
    private val password = "Pedro" + "-Clave-2024!"

    private fun project(version: Int): Map<String, String> = buildMap {
        put("tienda/package.json", """{"name": "tienda", "dependencies": {"express": "${if (version == 1) "4.18.0" else "4.19.2"}"${if (version == 2) ", \"helmet\": \"7.1.0\"" else ""}}}""")
        put("tienda/README.md", "# Tienda\n")
        put(
            "tienda/server/app.js",
            """
            const express = require('express');
            const users = require('./routes/users');
            const app = express();
            app.use('/api/users', users);
            app.listen(3000);
            """.trimIndent(),
        )
        put(
            "tienda/server/routes/users.js",
            """
            const router = require('express').Router();
            const ctrl = require('../controllers/users');
            router.get('/', ctrl.list);
            module.exports = router;
            """.trimIndent(),
        )
        put(
            "tienda/server/controllers/users.js",
            """
            // TODO: quitar <script>alert('x')</script> del ejemplo
            exports.list = async (req, res) => {
              if (req.query.a && req.query.b) { return res.json([]); }
              return res.json(req.query.c ? [1] : [2]);
            };
            """.trimIndent(),
        )
        if (version == 1) put("tienda/server/config.js", "const password = \"$password\";\nmodule.exports = { password };\n")
        if (version == 1) put("tienda/server/viejo.js", "module.exports = 1;\n")
        if (version == 2) put("tienda/server/nuevo.js", "module.exports = 2;\n")
    }

    private class Analysis(val records: List<ResultRecord>, val report: AnalysisReport, val meta: ReportMeta)

    private fun analyze(version: Int): Analysis {
        val file = File(dir, "tienda.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            project(version).forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        val sink = CollectingSink()
        FileInputStream(file).use { stream ->
            val attributes = Attributes()
            attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
            attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
            runBlocking { PipelineRunner("test").run(AnalysisConfig("v$version", AnalysisMode.FULL, ingest = IngestOptions(file.name)), FullPipeline.stages(), sink, attributes) }
        }
        sink.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: $it") }
        val report = AnalysisReport.from(sink.records, sink.records.filterIsInstance<SessionEnd>().lastOrNull()?.outcome, null)
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val meta = ReportMeta("analisis-$version", "tienda.zip", file.length(), sha, 1_762_560_000_000L + version * 86_400_000L, 1_762_700_000_000L, "0.9.0", "0.3.0", ZoneId.of("Europe/Madrid"))
        return Analysis(sink.records, report, meta)
    }

    @Test
    fun `quince secciones y dos anexos, con el resumen trazable a cada seccion`() {
        val a = analyze(1)
        val doc = ReportBuilder.build(a.report, a.meta)
        assertEquals(
            listOf(
                "Resumen ejecutivo", "Descripción general", "Tecnologías", "Arquitectura", "Estructura de carpetas", "Archivos más importantes",
                "Relaciones entre archivos", "Bases de datos y conexiones", "APIs y servicios externos", "Dependencias", "Auditoría de seguridad",
                "Problemas encontrados", "Recomendaciones", "Nivel general de mantenimiento", "Conclusiones",
                "Cobertura y limitaciones del análisis", "Metadatos",
            ),
            doc.sections.map { it.title },
        )
        assertEquals((1..15).map { "$it" } + listOf("A", "B"), doc.sections.map { it.number })
        val refs = doc.sections.map { it.ref }.toSet()
        val statements = doc.sections.flatMap { s -> s.blocks.filterIsInstance<Block.Statements>().flatMap { it.items } }
        assertTrue(statements.size >= 10, "Resumen, recomendaciones y conclusiones")
        statements.forEach { assertTrue(it.ref in refs, "Referencia a una sección que no existe: $it") }
        val summary = doc.sections.first().blocks.filterIsInstance<Block.Statements>().single().items
        assertTrue(summary.any { it.ref == "§11" && "hallazgo" in it.text }, summary.toString())
        assertTrue(summary.any { it.ref == "§14" && "índice de mantenimiento" in it.text }, summary.toString())
        // El índice y la arquitectura aparecen con sus datos.
        val maintenance = doc.sections.first { it.number == "14" }.blocks
        assertNotNull(maintenance.filterIsInstance<Block.Gauge>().single().score)
        assertTrue(doc.sections.first { it.number == "4" }.blocks.any { it is Block.Figure })
        // La contraseña sale como problema, pero nunca completa.
        val problems = ReportBuilder.problems(a.report)
        assertTrue(problems.isNotEmpty())
        val metadata = doc.sections.last().blocks.filterIsInstance<Block.Facts>().single().items.toMap()
        assertEquals(a.meta.archiveSha256, metadata["Huella SHA-256 del comprimido"])
        assertEquals("v10", metadata["Esquema de resultados"])
    }

    @Test
    fun `HTML autocontenido, sin JavaScript, con CSP por huella y el texto del proyecto escapado`() {
        val a = analyze(1)
        val html = HtmlRenderer.render(ReportBuilder.build(a.report, a.meta))
        assertFalse(Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(html), "Ninguna etiqueta script, ni siquiera la del proyecto")
        assertTrue("TODO: quitar &lt;script&gt;alert(&#39;x&#39;)&lt;/" in html, "El texto del TODO aparece escapado")
        assertFalse(Regex("""\sstyle\s*=""").containsMatchIn(html), "Sin atributos style: la CSP los bloquearía")
        assertFalse(Regex("""(src|href)\s*=\s*"(https?:)?//""").containsMatchIn(html), "Sin recursos externos")
        assertFalse(password in html, "La contraseña no aparece completa")
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(css.toByteArray()))
        assertTrue("style-src 'sha256-$hash'" in html, "La CSP autoriza exactamente la hoja de estilos del documento")
        assertTrue("default-src 'none'" in html)
        val ids = Regex("""id="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
        val links = Regex("""href="#([^"]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue(links.isNotEmpty())
        assertTrue(ids.containsAll(links), "Enlaces rotos: ${links - ids}")
        assertTrue("<details open>" in html && "<svg class=\"diagram\"" in html)
    }

    @Test
    fun `JSON con esquema versionado, todos los registros por tipo y sin secretos completos`() {
        val a = analyze(1)
        val doc = ReportBuilder.build(a.report, a.meta)
        val text = JsonExporter.export(doc, a.records)
        assertFalse(password in text, "La contraseña no aparece completa")
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonExporter.SCHEMA, root["schema_version"]!!.jsonPrimitive.content)
        assertEquals(10, root["result_schema"]!!.jsonPrimitive.int)
        assertEquals(a.meta.archiveSha256, root["metadata"]!!.jsonObject["archive_sha256"]!!.jsonPrimitive.content)
        assertEquals(17, root["sections"]!!.jsonArray.size)
        assertTrue(root["executive_summary"]!!.jsonArray.isNotEmpty())
        val data = root["data"]!!.jsonObject
        listOf("file", "file_metrics", "metrics_summary", "maintenance_index", "finding", "diagram", "technology").forEach {
            assertTrue((data[it] as? JsonArray)?.isNotEmpty() == true, "Faltan registros de tipo $it: ${data.keys}")
        }
        assertFalse(data.values.flatMap { it.jsonArray }.any { (it as JsonObject).containsKey("t") }, "El tipo va en el grupo, no en cada registro")
        assertFalse("session_start" in data || "content" in data)
    }

    @Test
    fun `comparacion entre dos versiones del mismo proyecto`() {
        val v1 = analyze(1)
        val v2 = analyze(2)
        val c = Comparison.of(v1.report, v1.meta, v2.report, v2.meta)
        assertTrue(c.sameProject)
        assertTrue(c.resolvedFindings.any { it.path == "tienda/server/config.js" }, "La contraseña se quitó: hallazgo resuelto")
        assertTrue(c.newFindings.none { it.path == "tienda/server/config.js" })
        assertEquals(listOf("tienda/server/nuevo.js"), c.addedFiles)
        assertEquals(listOf("tienda/server/config.js", "tienda/server/viejo.js"), c.removedFiles)
        assertEquals(listOf("tienda/package.json"), c.modifiedFiles)
        assertEquals(listOf("helmet"), c.addedDependencies.map { it.name })
        assertEquals(listOf("express" to ("4.18.0" to "4.19.2")), c.updatedDependencies.map { (o, n) -> n.name to (o.version to n.version) })
        val findings = c.metrics.first { it.name == "Hallazgos de seguridad" }
        assertEquals(Trend.BETTER, findings.trend)
        val doc = c.toDocument(1_762_800_000_000L)
        assertEquals(listOf("Resumen de cambios", "Evolución de las métricas", "Seguridad", "Dependencias", "Tecnologías", "Archivos"), doc.sections.map { it.title })
        val html = HtmlRenderer.render(doc)
        assertTrue("4.18.0" in html && "4.19.2" in html)
        assertFalse(Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }
}
