package com.funcodestudiocb.analyzer.dashboard

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.LanguageCategory
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.MaintenanceIndex
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.Technology
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome

/** Lo que el Panel necesita de un análisis guardado: sus datos generales y sus registros de resumen. */
data class AnalysisDigest(
    val id: String,
    val archiveName: String,
    val startedAtEpochMs: Long,
    val outcome: AnalysisOutcome?,
    val fileCount: Int,
    val findingCount: Int,
    val security: SecuritySummary? = null,
    val dependencies: DependencySummary? = null,
    val technologies: List<Technology> = emptyList(),
    val languages: List<LanguageStats> = emptyList(),
    val maintenance: MaintenanceIndex? = null,
) {
    /** Terminó y tiene resultados (completo o parcial). */
    val usable: Boolean get() = outcome == AnalysisOutcome.COMPLETED || outcome == AnalysisOutcome.PARTIAL

    /** Se pudieron comprobar las vulnerabilidades: había dependencias y base descargada para alguno de sus ecosistemas. */
    val vulnerabilitiesChecked: Boolean
        get() = dependencies != null && dependencies.direct + dependencies.indirect > 0 && dependencies.databases.any { it.date != null }
}

/** Un proyecto (análisis agrupados por el nombre del comprimido) con su último análisis y su evolución. */
data class ProjectOverview(
    val key: String,
    val name: String,
    val latest: AnalysisDigest,
    /** Todos sus análisis, del más antiguo al más reciente. */
    val history: List<AnalysisDigest>,
) {
    /** Índice de mantenimiento de cada análisis que lo tiene, en orden. */
    val indexTrend: List<Pair<Long, Int>> = history.mapNotNull { a -> a.maintenance?.score?.let { a.startedAtEpochMs to it } }

    /** Hallazgos de seguridad de cada análisis terminado, en orden. */
    val findingsTrend: List<Pair<Long, Int>> = history.filter { it.usable && it.security != null }.map { it.startedAtEpochMs to it.security!!.findings }
}

/** Una tecnología (con su categoría) o un lenguaje (con sus líneas de código) y en cuántos proyectos aparece. */
data class Usage(val name: String, val projects: Int, val category: String? = null, val lines: Long? = null)

data class Dashboard(
    val analyses: Int,
    val projects: List<ProjectOverview>,
    val recent: List<AnalysisDigest>,
    /** Hallazgos de seguridad del último análisis terminado de cada proyecto. */
    val findingsBySeverity: Map<Severity, Int>,
    val findingsProjects: Int,
    /** Vulnerabilidades de dependencias del último análisis terminado de cada proyecto, si se pudieron comprobar. */
    val vulnerabilitiesBySeverity: Map<Severity, Int>,
    val vulnerabilityProjects: Int,
    val vulnerabilitiesNotChecked: Int,
    val technologies: List<Usage>,
    val languages: List<Usage>,
    /** Proyectos con al menos dos índices: los que tienen tendencia. */
    val trends: List<ProjectOverview>,
) {
    val empty: Boolean get() = analyses == 0
}

object DashboardBuilder {

    const val RECENT = 5
    const val TOP_TECHNOLOGIES = 8
    const val TOP_LANGUAGES = 6
    const val TRENDS = 3

    /** [digests] en cualquier orden. */
    fun build(digests: List<AnalysisDigest>): Dashboard {
        val ordered = digests.sortedBy { it.startedAtEpochMs }
        val projects = ordered.groupBy { ProjectKey.of(it.archiveName) }
            .map { (key, history) -> ProjectOverview(key, ProjectKey.displayName(history.last().archiveName), history.last(), history) }
            .sortedByDescending { it.latest.startedAtEpochMs }
        // Las cifras globales salen del último análisis terminado de cada proyecto: sumar todos contaría dos veces lo mismo.
        val latestUsable = projects.mapNotNull { p -> p.history.lastOrNull { it.usable } }

        val findings = latestUsable.mapNotNull { it.security }
        val vulnerable = latestUsable.filter { it.vulnerabilitiesChecked }
        val withDependencies = latestUsable.filter { it.dependencies != null && it.dependencies.direct + it.dependencies.indirect > 0 }

        val technologies = latestUsable
            .flatMap { a -> a.technologies.filter { it.confidence != Confidence.POSSIBLE }.distinctBy { it.id }.map { it to a } }
            .groupBy { it.first.id }
            .map { (_, uses) -> Usage(uses.first().first.name, uses.size, category = category(uses.first().first.category)) }
            .sortedWith(compareByDescending<Usage> { it.projects }.thenBy { it.name.lowercase() })
            .take(TOP_TECHNOLOGIES)

        val languages = latestUsable
            .flatMap { a -> a.languages.filter { it.category == LanguageCategory.PROGRAMMING && it.code > 0 }.map { it to a } }
            .groupBy { it.first.id }
            .map { (_, uses) -> Triple(uses.first().first.name, uses.size, uses.sumOf { it.first.code }) }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.third }.thenBy { it.first })
            .take(TOP_LANGUAGES)
            .map { (name, count, lines) -> Usage(name, count, lines = lines) }

        return Dashboard(
            analyses = digests.size,
            projects = projects,
            recent = ordered.asReversed().take(RECENT),
            findingsBySeverity = sum(findings.map { it.bySeverity }),
            findingsProjects = findings.size,
            vulnerabilitiesBySeverity = sum(vulnerable.map { it.dependencies!!.bySeverity }),
            vulnerabilityProjects = vulnerable.size,
            vulnerabilitiesNotChecked = withDependencies.size - vulnerable.size,
            technologies = technologies,
            languages = languages,
            trends = projects.filter { it.indexTrend.size >= 2 }.sortedByDescending { it.history.size }.take(TRENDS),
        )
    }

    private fun sum(maps: List<Map<Severity, Int>>): Map<Severity, Int> =
        Severity.entries.associateWith { s -> maps.sumOf { it[s] ?: 0 } }

    private fun category(c: TechnologyCategory) = com.funcodestudiocb.analyzer.engine.report.Labels.technologyCategory(c)
}

/**
 * Identifica el proyecto de un análisis por el nombre de su comprimido, sin la extensión ni los sufijos que suelen
 * cambiar entre versiones del mismo proyecto: «tienda-v2.zip», «tienda (1).zip», «tienda_2026-11-08.rar» y
 * «tienda-main.zip» son el mismo proyecto «tienda». No mira el contenido: dos proyectos distintos con el mismo nombre
 * se agrupan juntos (la comparación entre análisis sí lo detecta y lo advierte).
 */
object ProjectKey {

    private val EXTENSION = Regex("""\.(zip|rar)$""", RegexOption.IGNORE_CASE)
    private val SUFFIX = Regex(
        """(\s*\(\d+\)|[\s._-]+(v?\d+(?:[._-]\d+)*|(?:19|20)\d{2}[._-]?\d{2}[._-]?\d{2}|main|master|copia|copy|final|backup|release))$""",
        RegexOption.IGNORE_CASE,
    )

    fun displayName(archiveName: String): String {
        var name = archiveName.trim().replace(EXTENSION, "")
        while (true) {
            val stripped = name.replace(SUFFIX, "")
            if (stripped == name || stripped.isBlank()) break
            name = stripped
        }
        return name.ifBlank { archiveName.trim() }
    }

    fun of(archiveName: String): String = displayName(archiveName).lowercase().replace(Regex("[\\s._-]+"), "-")
}
