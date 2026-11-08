package com.funcodestudiocb.analyzer.engine.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsRecordsTest {

    private val file = FileMetrics(
        "src/pedidos.js", "javascript", codeLines = 40, commentLines = 5, test = false, measured = true,
        functions = 3, complexity = 9, maxComplexity = 6, longestFunction = 20, duplicatedLines = 7, debtMarkers = 1,
    )

    private val summary = MetricsSummary(
        codeFiles = 10, codeLines = 1_000, commentLines = 100, testFiles = 2, testLines = 200, measuredFiles = 8,
        functions = 40, averageComplexity = 2.5, medianComplexity = 2, p90Complexity = 5, maxComplexity = 12,
        functionsOver10 = 1, functionsOver20 = 0, longFunctions = 1, largeFiles = 0,
        complexFunctionList = listOf(FunctionRef("src/pedidos.js", "total", 2, 8, 12)),
        longFunctionList = listOf(FunctionRef("src/b.js", "calcular", 10, 70, 3)),
        duplicatedLines = 14, duplicationBase = 800,
        duplicates = listOf(DuplicateBlock(7, listOf("src/a.js:3", "src/b.js:3"), 2)),
        debtByKind = mapOf(DebtKind.TODO to 1),
        debtSamples = listOf(DebtMarker(DebtKind.TODO, "src/pedidos.js", 1, "TODO: separar la validación")),
        qualityTools = listOf(QualityTool(QualityToolKind.LINTER, "ESLint", ".eslintrc.json")),
        readme = "README.md", filesInCycles = 2, cycles = 1, highCoupling = 1, highCouplingSamples = listOf("src/app.js (21 archivos)"),
        notMeasured = listOf("Go (1 archivo)"), notes = listOf("1 archivo generado no cuenta en las métricas."),
    )

    private val index = MaintenanceIndex(
        72, MaintenanceLevel.GOOD,
        listOf(
            MaintenancePart("complexity", "Complejidad", 60, 80, "2,5 % de las funciones con complejidad mayor que 10", "100 − 2 × …"),
            MaintenancePart("dependencies", "Salud de dependencias", 40, null, "Falta la base de vulnerabilidades", "100 − 15 por …"),
        ),
        "Índice = suma de (peso × valor) ÷ suma de pesos",
        listOf("Salud de dependencias: no evaluado."),
    )

    @Test
    fun `los registros de metricas van y vuelven sin cambios y son validos`() {
        listOf(file, summary, index).forEach {
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), it)
            assertEquals(it, ProtocolJson.decodeFromString(ResultRecord.serializer(), json))
            assertEquals(emptyList(), RecordValidator.validate(it), "$it")
        }
    }

    @Test
    fun `se rechazan metricas incoherentes o fuera de limites`() {
        fun invalid(r: ResultRecord) = assertTrue(RecordValidator.validate(r).isNotEmpty(), "Debería rechazarse: $r")
        invalid(file.copy(measured = false))
        invalid(file.copy(maxComplexity = 20))
        invalid(file.copy(duplicatedLines = 41))
        invalid(file.copy(codeLines = -1))
        invalid(summary.copy(functionsOver20 = 2))
        invalid(summary.copy(testLines = 2_000))
        invalid(summary.copy(duplicatedLines = 900))
        invalid(summary.copy(averageComplexity = Double.NaN))
        invalid(summary.copy(functions = 0, functionsOver10 = 0, longFunctions = 0))
        invalid(summary.copy(duplicates = listOf(DuplicateBlock(7, listOf("src/a.js:3"), 1))))
        invalid(summary.copy(debtSamples = listOf(DebtMarker(DebtKind.HACK, "a.js", 1, "HACK\u0007"))))
        invalid(summary.copy(complexFunctionList = List(31) { FunctionRef("a.js", "f$it", 1, 1, 1) }))
        invalid(index.copy(score = 101))
        invalid(index.copy(level = null))
        invalid(index.copy(parts = index.parts.map { it.copy(weight = 10) }))
        invalid(index.copy(parts = index.parts.map { it.copy(score = null) }))
        invalid(index.copy(parts = index.parts + index.parts.first()))
    }
}
