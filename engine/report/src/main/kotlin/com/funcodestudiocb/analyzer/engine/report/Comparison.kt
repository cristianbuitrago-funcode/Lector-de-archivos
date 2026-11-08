package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.report.Labels.count
import com.funcodestudiocb.analyzer.engine.report.Labels.number

enum class Trend { BETTER, WORSE, SAME, UNKNOWN }

/** Evolución de una cifra entre dos análisis. */
data class MetricChange(val name: String, val before: String, val after: String, val trend: Trend)

/**
 * Diferencias entre dos análisis (normalmente del mismo proyecto): hallazgos nuevos y resueltos, dependencias añadidas,
 * eliminadas y actualizadas, tecnologías, archivos añadidos, eliminados y modificados (por su huella SHA-256) y la
 * evolución de las métricas. Los hallazgos se comparan por regla, archivo y huella (la línea puede moverse).
 */
data class Comparison(
    val before: ReportMeta,
    val after: ReportMeta,
    val sameProject: Boolean,
    val newFindings: List<SecurityFinding>,
    val resolvedFindings: List<SecurityFinding>,
    val addedDependencies: List<ResolvedDependency>,
    val removedDependencies: List<ResolvedDependency>,
    val updatedDependencies: List<Pair<ResolvedDependency, ResolvedDependency>>,
    val addedTechnologies: List<String>,
    val removedTechnologies: List<String>,
    val addedFiles: List<String>,
    val removedFiles: List<String>,
    val modifiedFiles: List<String>,
    /** Archivos presentes en los dos sin huella en alguno: no se puede saber si cambiaron. */
    val unknownFiles: Int,
    val metrics: List<MetricChange>,
) {
    val hasChanges: Boolean
        get() = listOf(newFindings, resolvedFindings, addedDependencies, removedDependencies, updatedDependencies, addedTechnologies, removedTechnologies, addedFiles, removedFiles, modifiedFiles)
            .any { it.isNotEmpty() }

    companion object {
        private val TREND = mapOf(Trend.BETTER to "Mejora", Trend.WORSE to "Empeora", Trend.SAME to "Igual", Trend.UNKNOWN to "—")

        fun of(old: AnalysisReport, oldMeta: ReportMeta, new: AnalysisReport, newMeta: ReportMeta): Comparison {
            val (added, resolved) = diffFindings(old.security, new.security)
            fun depKey(d: ResolvedDependency) = "${d.ecosystem}|${d.name.lowercase()}|${d.projectRoot.orEmpty()}"
            val oldDeps = old.resolvedDependencies.groupBy(::depKey)
            val newDeps = new.resolvedDependencies.groupBy(::depKey)
            val updated = newDeps.keys.intersect(oldDeps.keys).mapNotNull { k ->
                val o = oldDeps.getValue(k).first()
                val n = newDeps.getValue(k).first()
                if (o.version != n.version) o to n else null
            }
            val oldTech = old.technologies.map { it.name }.toSet()
            val newTech = new.technologies.map { it.name }.toSet()
            val oldFiles = old.files.filter { it.status == FileStatus.ANALYZED }.associate { it.path to it.sha256 }
            val newFiles = new.files.filter { it.status == FileStatus.ANALYZED }.associate { it.path to it.sha256 }
            val common = oldFiles.keys.intersect(newFiles.keys)
            val modified = common.filter { oldFiles[it] != null && newFiles[it] != null && oldFiles[it] != newFiles[it] }.sorted()
            val unknown = common.count { oldFiles[it] == null || newFiles[it] == null }
            val overlap = if (oldFiles.isEmpty() || newFiles.isEmpty()) 0.0 else common.size.toDouble() / minOf(oldFiles.size, newFiles.size)
            val sameProject = oldMeta.archiveName.equals(newMeta.archiveName, ignoreCase = true) || overlap >= 0.5
            return Comparison(
                oldMeta, newMeta, sameProject, added, resolved,
                (newDeps.keys - oldDeps.keys).map { newDeps.getValue(it).first() }.sortedBy { it.name },
                (oldDeps.keys - newDeps.keys).map { oldDeps.getValue(it).first() }.sortedBy { it.name },
                updated.sortedBy { it.second.name },
                (newTech - oldTech).sorted(), (oldTech - newTech).sorted(),
                (newFiles.keys - oldFiles.keys).sorted(), (oldFiles.keys - newFiles.keys).sorted(), modified, unknown,
                metrics(old, new),
            )
        }

        /** Hallazgos nuevos y resueltos, por regla, archivo y huella, contando repeticiones. */
        private fun diffFindings(old: List<SecurityFinding>, new: List<SecurityFinding>): Pair<List<SecurityFinding>, List<SecurityFinding>> {
            fun key(f: SecurityFinding) = "${f.ruleId}|${f.path.orEmpty()}|${f.fingerprint ?: f.masked ?: f.name}"
            val oldByKey = old.groupBy(::key)
            val newByKey = new.groupBy(::key)
            val added = newByKey.flatMap { (k, list) -> list.drop(oldByKey[k]?.size ?: 0) }
            val resolved = oldByKey.flatMap { (k, list) -> list.drop(newByKey[k]?.size ?: 0) }
            val order = compareBy<SecurityFinding>({ it.severity.ordinal }, { it.path ?: "" }, { it.line ?: 0 })
            return added.sortedWith(order) to resolved.sortedWith(order)
        }

        private fun metrics(old: AnalysisReport, new: AnalysisReport): List<MetricChange> {
            val out = mutableListOf<MetricChange>()
            fun higherBetter(name: String, a: Number?, b: Number?, show: (Number) -> String = { it.toString() }) {
                out += MetricChange(name, a?.let(show) ?: "—", b?.let(show) ?: "—", trend(a?.toDouble(), b?.toDouble(), higherIsBetter = true))
            }
            fun lowerBetter(name: String, a: Number?, b: Number?, show: (Number) -> String = { number(it.toLong()) }) {
                out += MetricChange(name, a?.let(show) ?: "—", b?.let(show) ?: "—", trend(a?.toDouble(), b?.toDouble(), higherIsBetter = false))
            }
            higherBetter("Índice de mantenimiento", old.maintenance?.score, new.maintenance?.score)
            val oldParts = old.maintenance?.parts.orEmpty().associateBy { it.id }
            new.maintenance?.parts.orEmpty().forEach { p -> higherBetter("· ${p.name}", oldParts[p.id]?.score, p.score) }
            fun severe(r: AnalysisReport) = r.securitySummary?.let { (it.bySeverity[Severity.CRITICAL] ?: 0) + (it.bySeverity[Severity.HIGH] ?: 0) }
            lowerBetter("Hallazgos críticos y altos", severe(old), severe(new))
            lowerBetter("Hallazgos de seguridad", old.securitySummary?.findings, new.securitySummary?.findings)
            lowerBetter("Vulnerabilidades en dependencias", old.dependencySummary?.vulnerabilities, new.dependencySummary?.vulnerabilities)
            lowerBetter("Funciones con complejidad > 20", old.metrics?.functionsOver20, new.metrics?.functionsOver20)
            fun dup(r: AnalysisReport) = r.metrics?.takeIf { it.duplicationBase > 0 }?.let { it.duplicatedLines.toDouble() / it.duplicationBase }
            lowerBetter("Código repetido", dup(old), dup(new)) { Labels.percent(it.toDouble()) }
            out += MetricChange("Líneas de código", old.metrics?.codeLines?.let(::number) ?: "—", new.metrics?.codeLines?.let(::number) ?: "—", Trend.UNKNOWN)
            return out
        }

        private fun trend(a: Double?, b: Double?, higherIsBetter: Boolean): Trend = when {
            a == null || b == null -> Trend.UNKNOWN
            a == b -> Trend.SAME
            (b > a) == higherIsBetter -> Trend.BETTER
            else -> Trend.WORSE
        }
    }

    // ------------------------------------------------------------------ documento

    fun toDocument(generatedAtEpochMs: Long): ReportDocument {
        val when1 = before.analyzedAtEpochMs?.let { Labels.dateTime(it, after.zone) } ?: before.analysisId
        val when2 = after.analyzedAtEpochMs?.let { Labels.dateTime(it, after.zone) } ?: after.analysisId
        val sections = mutableListOf<ReportSection>()
        val summary = mutableListOf<Block>(
            Block.Facts(listOf("Análisis anterior" to "${before.archiveName} · $when1", "Análisis posterior" to "${after.archiveName} · $when2")),
        )
        if (!sameProject) summary += Block.Callout(Tone.WARNING, "Los dos análisis parecen de proyectos distintos (otro nombre y pocos archivos en común): las diferencias pueden no tener sentido.")
        summary += Block.Bullets(
            listOf(
                "Seguridad: ${count(newFindings.size, "hallazgo nuevo", "hallazgos nuevos")} y ${count(resolvedFindings.size, "resuelto", "resueltos")}.",
                "Dependencias: ${number(addedDependencies.size)} añadidas, ${number(removedDependencies.size)} eliminadas y ${number(updatedDependencies.size)} actualizadas.",
                "Tecnologías: ${number(addedTechnologies.size)} nuevas y ${number(removedTechnologies.size)} que ya no aparecen.",
                "Archivos: ${number(addedFiles.size)} añadidos, ${number(removedFiles.size)} eliminados y ${number(modifiedFiles.size)} modificados" +
                    if (unknownFiles > 0) " (${count(unknownFiles, "archivo", "archivos")} sin huella para comparar)." else ".",
            ),
        )
        if (!hasChanges) summary += Block.Callout(Tone.INFO, "No hay diferencias en hallazgos, dependencias, tecnologías ni archivos.")
        sections += ReportSection("s1", "1", "Resumen de cambios", summary)

        sections += ReportSection(
            "s2", "2", "Evolución de las métricas",
            listOf(
                Block.Table(
                    listOf("Cifra", "Antes", "Después", "Tendencia"),
                    metrics.map { listOf(it.name, it.before, it.after, TREND.getValue(it.trend)) },
                    tones = metrics.map { if (it.trend == Trend.BETTER) Tone.GOOD else if (it.trend == Trend.WORSE) Tone.DANGER else Tone.NEUTRAL },
                ),
            ),
        )
        fun findingRows(list: List<SecurityFinding>) = list.take(50).map { listOf(Labels.severity(it.severity), it.name, it.path?.let { p -> it.line?.let { l -> "$p:$l" } ?: p } ?: "—", it.ruleId) }
        val security = mutableListOf<Block>()
        security += Block.Heading("Nuevos")
        security += if (newFindings.isEmpty()) Block.Paragraph("Ninguno.") else Block.Table(listOf("Severidad", "Hallazgo", "Ubicación", "Regla"), findingRows(newFindings), newFindings.take(50).map { Labels.severityTone(it.severity) })
        security += Block.Heading("Resueltos")
        security += if (resolvedFindings.isEmpty()) Block.Paragraph("Ninguno.") else Block.Table(listOf("Severidad", "Hallazgo", "Ubicación", "Regla"), findingRows(resolvedFindings), resolvedFindings.take(50).map { Tone.GOOD })
        sections += ReportSection("s3", "3", "Seguridad", security)

        val deps = mutableListOf<Block>()
        if (updatedDependencies.isNotEmpty()) {
            deps += Block.Heading("Actualizadas")
            deps += Block.Table(listOf("Paquete", "Antes", "Después"), updatedDependencies.map { (o, n) -> listOf(n.name, o.version ?: "—", n.version ?: "—") })
        }
        if (addedDependencies.isNotEmpty()) {
            deps += Block.Heading("Añadidas")
            deps += Block.Table(listOf("Paquete", "Versión", "Directa"), addedDependencies.take(100).map { listOf(it.name, it.version ?: "—", if (it.direct) "Sí" else "No") })
        }
        if (removedDependencies.isNotEmpty()) {
            deps += Block.Heading("Eliminadas")
            deps += Block.Table(listOf("Paquete", "Versión", "Directa"), removedDependencies.take(100).map { listOf(it.name, it.version ?: "—", if (it.direct) "Sí" else "No") })
        }
        if (deps.isEmpty()) deps += Block.Paragraph("Sin cambios en las dependencias.")
        sections += ReportSection("s4", "4", "Dependencias", deps)

        val tech = mutableListOf<Block>()
        if (addedTechnologies.isNotEmpty()) tech += Block.Facts(listOf("Nuevas" to addedTechnologies.joinToString()))
        if (removedTechnologies.isNotEmpty()) tech += Block.Facts(listOf("Ya no aparecen" to removedTechnologies.joinToString()))
        if (tech.isEmpty()) tech += Block.Paragraph("Sin cambios en las tecnologías.")
        sections += ReportSection("s5", "5", "Tecnologías", tech)

        val files = mutableListOf<Block>()
        listOf("Modificados" to modifiedFiles, "Añadidos" to addedFiles, "Eliminados" to removedFiles).forEach { (title, list) ->
            if (list.isNotEmpty()) {
                files += Block.Heading("$title (${number(list.size)})")
                files += Block.Bullets(list.take(200))
                if (list.size > 200) files += Block.Paragraph("Se muestran 200 de ${number(list.size)}.")
            }
        }
        if (files.isEmpty()) files += Block.Paragraph("Los archivos analizados son idénticos.")
        sections += ReportSection("s6", "6", "Archivos", files)

        return ReportDocument("Comparación de análisis", "${before.archiveName} → ${after.archiveName}", after.copy(generatedAtEpochMs = generatedAtEpochMs), sections)
    }

}
