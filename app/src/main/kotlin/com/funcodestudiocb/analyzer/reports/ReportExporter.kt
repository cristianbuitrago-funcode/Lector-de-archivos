package com.funcodestudiocb.analyzer.reports

import android.content.Context
import android.net.Uri
import com.funcodestudiocb.analyzer.BuildConfig
import com.funcodestudiocb.analyzer.analysis.AnalysisRepository
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.report.Comparison
import com.funcodestudiocb.analyzer.engine.report.HtmlRenderer
import com.funcodestudiocb.analyzer.engine.report.JsonExporter
import com.funcodestudiocb.analyzer.engine.report.ReportBuilder
import com.funcodestudiocb.analyzer.engine.report.ReportDocument
import com.funcodestudiocb.analyzer.engine.report.ReportMeta
import com.funcodestudiocb.analyzer.service.AnalyzerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formatos de exportación del informe (§10.3 de la arquitectura). */
enum class ReportFormat(val extension: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    HTML("html", "text/html", "HTML"),
    JSON("json", "application/json", "JSON"),
}

/** Informe listo para guardar o compartir. */
class ExportedReport(val fileName: String, val format: ReportFormat, val bytes: ByteArray)

/**
 * Genera los informes de los análisis guardados (PDF, HTML y JSON) y la comparación entre dos de ellos. Se generan en
 * memoria a partir de los registros ya validados y se escriben solo donde el usuario elige (selector de documentos) o en
 * una copia temporal para compartir por acción explícita, que se borra antes de cada nueva y al arrancar o borrar los
 * datos. Fuera del almacenamiento cifrado de la app el informe ya no está cifrado: la pantalla lo advierte.
 */
class ReportExporter(private val context: Context, private val repository: AnalysisRepository) {

    private val shareDir = File(context.cacheDir, SHARE_DIR)

    init {
        clearShared()
    }

    fun meta(entity: AnalysisEntity, now: Long = System.currentTimeMillis()) = ReportMeta(
        analysisId = entity.id,
        archiveName = entity.archiveName,
        archiveSize = entity.archiveSize,
        archiveSha256 = entity.archiveSha256.takeIf { it.isNotBlank() },
        analyzedAtEpochMs = entity.startedAtEpochMs,
        generatedAtEpochMs = now,
        appVersion = BuildConfig.VERSION_NAME,
        engineVersion = AnalyzerService.ENGINE_VERSION,
        zone = ZoneId.systemDefault(),
    )

    /** Informe de un análisis en el modelo común (para la vista previa en pantalla). */
    suspend fun document(id: String): ReportDocument? = withContext(Dispatchers.Default) {
        val stored = repository.loadWithRecords(id) ?: return@withContext null
        ReportBuilder.build(stored.report, meta(stored.entity))
    }

    suspend fun export(id: String, format: ReportFormat, now: Long = System.currentTimeMillis()): ExportedReport? = withContext(Dispatchers.Default) {
        val stored = repository.loadWithRecords(id) ?: return@withContext null
        val doc = ReportBuilder.build(stored.report, meta(stored.entity, now))
        val bytes = when (format) {
            ReportFormat.HTML -> HtmlRenderer.render(doc).toByteArray(Charsets.UTF_8)
            ReportFormat.JSON -> JsonExporter.export(doc, stored.records).toByteArray(Charsets.UTF_8)
            ReportFormat.PDF -> ByteArrayOutputStream().also { PdfReportRenderer.render(doc, it) }.toByteArray()
        }
        ExportedReport(fileName("informe", stored.entity.archiveName, now, format), format, bytes)
    }

    /** Comparación entre dos análisis guardados (del anterior al posterior). */
    suspend fun comparison(beforeId: String, afterId: String, now: Long = System.currentTimeMillis()): Comparison? = withContext(Dispatchers.Default) {
        val before = repository.loadWithRecords(beforeId) ?: return@withContext null
        val after = repository.loadWithRecords(afterId) ?: return@withContext null
        Comparison.of(before.report, meta(before.entity, now), after.report, meta(after.entity, now))
    }

    /** La comparación se exporta en PDF o HTML (sus datos ya están en el JSON de cada análisis). */
    suspend fun exportComparison(comparison: Comparison, format: ReportFormat, now: Long = System.currentTimeMillis()): ExportedReport = withContext(Dispatchers.Default) {
        require(format != ReportFormat.JSON) { "La comparación se exporta en PDF o HTML" }
        val doc = comparison.toDocument(now)
        val bytes = if (format == ReportFormat.HTML) {
            HtmlRenderer.render(doc).toByteArray(Charsets.UTF_8)
        } else {
            ByteArrayOutputStream().also { PdfReportRenderer.render(doc, it) }.toByteArray()
        }
        ExportedReport(fileName("comparacion", comparison.after.archiveName, now, format), format, bytes)
    }

    /** Escribe el informe en el documento que eligió el usuario. */
    suspend fun write(report: ExportedReport, uri: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("No se pudo abrir el destino")
        out.use { it.write(report.bytes) }
    }

    /** Copia temporal para compartir (la única que se escribe fuera del destino que elige el usuario). */
    suspend fun shareCopy(report: ExportedReport): File = withContext(Dispatchers.IO) {
        clearShared()
        shareDir.mkdirs()
        File(shareDir, report.fileName).also { it.writeBytes(report.bytes) }
    }

    fun clearShared() {
        shareDir.deleteRecursively()
    }

    companion object {
        const val SHARE_DIR = "informes-compartidos"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        /** `informe-tienda-20261108-0013.pdf`: sin caracteres que den problemas en otros sistemas. */
        fun fileName(prefix: String, archiveName: String, now: Long, format: ReportFormat): String {
            val base = archiveName.substringBeforeLast('.').lowercase()
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(40).ifEmpty { "proyecto" }
            val stamp = STAMP.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
            return "$prefix-$base-$stamp.${format.extension}"
        }
    }
}
