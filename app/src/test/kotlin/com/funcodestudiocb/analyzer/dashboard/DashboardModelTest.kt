package com.funcodestudiocb.analyzer.dashboard

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.LanguageCategory
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.MaintenanceIndex
import com.funcodestudiocb.analyzer.engine.api.MaintenanceLevel
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.Technology
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.api.VulnDatabaseState
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardModelTest {

    private fun security(vararg counts: Pair<Severity, Int>) = SecuritySummary(
        findings = counts.sumOf { it.second },
        bySeverity = counts.toMap(),
        byCertainty = mapOf(Certainty.PROBABLE to counts.sumOf { it.second }),
        byCategory = emptyMap(),
        rules = 65,
        filesExamined = 10,
        downgraded = 0,
        suppressions = 0,
        dynamicChecks = emptyList(),
        notEvaluated = emptyList(),
    )

    private fun dependencies(withDatabase: Boolean, vararg counts: Pair<Severity, Int>) = DependencySummary(
        direct = 3, indirect = 10, lockfiles = 1, exactVersions = 13,
        vulnerablePackages = counts.size, vulnerabilities = counts.sumOf { it.second }, bySeverity = counts.toMap(),
        malicious = 0, notSeen = 0, endOfLife = 0, endingSoon = 0,
        databases = listOf(VulnDatabaseState("npm", date = if (withDatabase) 1_700_000_000_000 else null)),
        notEvaluated = emptyList(),
    )

    private fun technology(id: String, confidence: Confidence = Confidence.CONFIRMED) =
        Technology(id, id.replaceFirstChar { it.uppercase() }, TechnologyCategory.FRAMEWORK, confidence, evidence = emptyList(), evidenceCount = 1)

    private fun language(id: String, code: Long, category: LanguageCategory = LanguageCategory.PROGRAMMING) =
        LanguageStats(id, id.uppercase(), 2, category, files = 3, code = code, comment = 0, blank = 0, syntaxFiles = 3, lexicalFiles = 0, filesWithErrors = 0)

    private fun index(score: Int) = MaintenanceIndex(score, MaintenanceLevel.MEDIUM, parts = emptyList(), formula = "")

    private fun digest(
        id: String,
        name: String,
        day: Int,
        outcome: AnalysisOutcome = AnalysisOutcome.COMPLETED,
        security: SecuritySummary? = null,
        dependencies: DependencySummary? = null,
        technologies: List<Technology> = emptyList(),
        languages: List<LanguageStats> = emptyList(),
        maintenance: MaintenanceIndex? = null,
    ) = AnalysisDigest(id, name, day * 86_400_000L, outcome, 10, security?.findings ?: 0, security, dependencies, technologies, languages, maintenance)

    @Test
    fun `nombre del proyecto sin extension ni sufijos de version, fecha o copia`() {
        listOf("tienda.zip", "Tienda-v2.zip", "tienda (1).zip", "tienda_2026-11-08.rar", "tienda-main.zip", "tienda-1.4.2.zip", "TIENDA copia.zip")
            .forEach { assertEquals("tienda", ProjectKey.of(it), it) }
        assertEquals("Tienda", ProjectKey.displayName("Tienda-v2.zip"))
        assertEquals("web3", ProjectKey.of("web3.zip"), "Un número pegado al nombre es parte del nombre")
        assertEquals("mi-app", ProjectKey.of("Mi App.zip"))
        assertEquals("2024", ProjectKey.of("2024.zip"), "Si todo el nombre es un sufijo, se conserva")
    }

    @Test
    fun `totales del ultimo analisis terminado de cada proyecto sin contar dos veces`() {
        val d = DashboardBuilder.build(
            listOf(
                digest("t1", "tienda.zip", 1, security = security(Severity.HIGH to 2, Severity.LOW to 1), maintenance = index(60)),
                digest("t2", "tienda-v2.zip", 3, security = security(Severity.HIGH to 1), maintenance = index(68)),
                digest("t3", "tienda-v3.zip", 4, outcome = AnalysisOutcome.REJECTED), // no cuenta para los totales
                digest("b1", "blog.zip", 2, security = security(Severity.CRITICAL to 1), dependencies = dependencies(true, Severity.MEDIUM to 2)),
                digest("c1", "crm.rar", 5, security = security(), dependencies = dependencies(false, Severity.HIGH to 9)),
            ),
        )
        assertEquals(5, d.analyses)
        assertEquals(listOf("crm", "tienda", "blog"), d.projects.map { it.key }, "Por fecha del último análisis")
        val tienda = d.projects.first { it.key == "tienda" }
        assertEquals("t3", tienda.latest.id)
        assertEquals(3, tienda.history.size)
        // Tienda aporta su último análisis terminado (t2), no el rechazado ni el primero.
        assertEquals(mapOf(Severity.CRITICAL to 1, Severity.HIGH to 1, Severity.MEDIUM to 0, Severity.LOW to 0, Severity.INFO to 0), d.findingsBySeverity)
        assertEquals(3, d.findingsProjects)
        // Solo el blog tenía base de vulnerabilidades: las 9 del CRM no se comprobaron y no se suman.
        assertEquals(2, d.vulnerabilitiesBySeverity[Severity.MEDIUM])
        assertEquals(0, d.vulnerabilitiesBySeverity[Severity.HIGH])
        assertEquals(1, d.vulnerabilityProjects)
        assertEquals(1, d.vulnerabilitiesNotChecked)
        assertEquals(listOf("c1", "t3", "t2", "b1", "t1"), d.recent.map { it.id })
        assertEquals(listOf(60, 68), tienda.indexTrend.map { it.second })
        assertEquals(listOf(tienda), d.trends)
    }

    @Test
    fun `tecnologias por proyectos sin las posibles y lenguajes por lineas`() {
        val d = DashboardBuilder.build(
            listOf(
                digest("a", "a.zip", 1, technologies = listOf(technology("react"), technology("express"), technology("django", Confidence.POSSIBLE)),
                    languages = listOf(language("js", 1000), language("md", 500, LanguageCategory.PROSE))),
                digest("b", "b.zip", 2, technologies = listOf(technology("react", Confidence.PROBABLE)), languages = listOf(language("js", 200), language("py", 3000))),
            ),
        )
        assertEquals(listOf("React" to 2, "Express" to 1), d.technologies.map { it.name to it.projects })
        assertTrue(d.technologies.none { it.name == "Django" })
        assertEquals(listOf("PY" to 3000L, "JS" to 1200L), d.languages.map { it.name to it.lines })
        assertEquals(2, d.languages.first { it.name == "JS" }.projects)
        assertTrue(d.trends.isEmpty(), "Sin dos índices no hay tendencia")
    }

    @Test
    fun `sin analisis el panel esta vacio`() {
        val d = DashboardBuilder.build(emptyList())
        assertTrue(d.empty)
        assertTrue(d.projects.isEmpty() && d.recent.isEmpty())
        assertEquals(0, d.findingsBySeverity.values.sum())
    }
}
