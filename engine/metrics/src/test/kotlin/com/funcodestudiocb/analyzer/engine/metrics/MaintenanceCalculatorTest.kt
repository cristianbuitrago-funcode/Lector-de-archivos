package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.DebtKind
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.MaintenanceLevel
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.VulnDatabaseState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceCalculatorTest {

    private val base = MaintenanceInputs(
        functions = 100, functionsOver10 = 10, functionsOver20 = 2, longFunctions = 5,
        codeFiles = 50, largeFiles = 5,
        duplicationBase = 10_000, duplicatedLines = 500,
        productionLines = 10_000, commentLines = 1_000, testFiles = 20, testLines = 2_500, readme = true,
        dependencies = DependencySummary(
            direct = 10, indirect = 30, lockfiles = 1, exactVersions = 40, vulnerablePackages = 2, vulnerabilities = 3,
            bySeverity = mapOf(Severity.HIGH to 1, Severity.MEDIUM to 2), malicious = 0, notSeen = 0, endOfLife = 1, endingSoon = 0,
            databases = listOf(VulnDatabaseState("npm", 1_700_000_000_000L, 1000)), notEvaluated = emptyList(),
        ),
        tools = setOf(QualityToolKind.LINTER, QualityToolKind.CI),
        debt = mapOf(DebtKind.TODO to 5, DebtKind.FIXME to 1),
        relationsAnalyzed = true, filesInCycles = 5, highCoupling = 1,
    )

    @Test
    fun `cada sub-indice sigue su regla y el total es la media ponderada`() {
        val index = MaintenanceCalculator.compute(base)
        val scores = index.parts.associate { it.id to it.score }
        // Calculados a mano con las reglas de cada sub-índice.
        assertEquals(
            mapOf(
                "complexity" to 74, "size" to 75, "duplication" to 75, "tests" to 50, "documentation" to 56,
                "dependencies" to 67, "tools" to 80, "debt" to 93, "structure" to 74,
            ),
            scores,
        )
        assertEquals(100, index.parts.sumOf { it.weight })
        // (20·74 + 10·75 + 15·75 + 15·50 + 10·56 + 10·67 + 5·80 + 5·93 + 10·74) / 100 = 69,4
        assertEquals(69, index.score)
        assertEquals(MaintenanceLevel.MEDIUM, index.level)
        assertTrue(index.notes.isEmpty())
        assertTrue(RecordValidator.validate(index).isEmpty(), RecordValidator.validate(index).toString())
        val complexity = index.parts.first { it.id == "complexity" }
        assertEquals("10 % de las 100 funciones medidas tiene complejidad mayor que 10, y 2,0 % mayor que 20", complexity.measure)
        assertTrue(index.formula.contains("orientativo"))
    }

    @Test
    fun `lo que no se puede evaluar no cuenta y los pesos se reparten`() {
        val index = MaintenanceCalculator.compute(
            base.copy(
                functions = 0, functionsOver10 = 0, functionsOver20 = 0, longFunctions = 0, codeFiles = 10, largeFiles = 0,
                duplicationBase = 0, duplicatedLines = 0, productionLines = 1_000, commentLines = 0, testFiles = 0, testLines = 0,
                readme = false, dependencies = null, tools = emptySet(), debt = emptyMap(), relationsAnalyzed = false,
                filesInCycles = 0, highCoupling = 0,
            ),
        )
        val scores = index.parts.associate { it.id to it.score }
        assertNull(scores["complexity"])
        assertNull(scores["duplication"])
        assertNull(scores["dependencies"])
        assertNull(scores["structure"])
        assertEquals(0, scores["tests"], "Sin pruebas: 0, no «sin evaluar»")
        // (10·100 + 15·0 + 10·0 + 5·0 + 5·100) / 45 = 33,3
        assertEquals(33, index.score)
        assertEquals(MaintenanceLevel.LOW, index.level)
        assertTrue(index.notes.any { "45 %" in it && "poco representativo" in it }, index.notes.toString())
        assertTrue(index.notes.any { it.startsWith("Complejidad: no evaluado") }, index.notes.toString())
        assertTrue(RecordValidator.validate(index).isEmpty())
    }

    @Test
    fun `sin base de vulnerabilidades no se dan por sanas las dependencias, y sin codigo no hay indice`() {
        val noDb = base.dependencies!!.copy(databases = listOf(VulnDatabaseState("npm")), bySeverity = emptyMap(), vulnerabilities = 0)
        val part = MaintenanceCalculator.compute(base.copy(dependencies = noDb)).parts.first { it.id == "dependencies" }
        assertNull(part.score)
        assertTrue("Ajustes" in part.measure)

        val empty = MaintenanceCalculator.compute(
            MaintenanceInputs(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null, emptySet(), emptyMap(), false, 0, 0),
        )
        assertNull(empty.score)
        assertNull(empty.level)
        assertTrue(RecordValidator.validate(empty).isEmpty())
    }

    @Test
    fun `niveles y porcentajes en espanol`() {
        assertEquals(MaintenanceLevel.HIGH, MaintenanceCalculator.level(85))
        assertEquals(MaintenanceLevel.GOOD, MaintenanceCalculator.level(70))
        assertEquals(MaintenanceLevel.MEDIUM, MaintenanceCalculator.level(50))
        assertEquals(MaintenanceLevel.LOW, MaintenanceCalculator.level(30))
        assertEquals(MaintenanceLevel.VERY_LOW, MaintenanceCalculator.level(29))
        assertEquals("4,5 %", MaintenanceCalculator.pct(0.045))
        assertEquals("12 %", MaintenanceCalculator.pct(0.12))
        assertEquals("0 %", MaintenanceCalculator.pct(0.0))
    }
}
