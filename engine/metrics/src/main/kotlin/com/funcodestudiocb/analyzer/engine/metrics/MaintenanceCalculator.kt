package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.DebtKind
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.MaintenanceIndex
import com.funcodestudiocb.analyzer.engine.api.MaintenanceLevel
import com.funcodestudiocb.analyzer.engine.api.MaintenancePart
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind
import com.funcodestudiocb.analyzer.engine.api.Severity
import java.util.Locale
import kotlin.math.roundToInt

/** Las cifras del proyecto con las que se calcula el índice de mantenimiento (código de producción, sin pruebas). */
data class MaintenanceInputs(
    val functions: Int,
    val functionsOver10: Int,
    val functionsOver20: Int,
    val longFunctions: Int,
    val codeFiles: Int,
    val largeFiles: Int,
    val duplicationBase: Long,
    val duplicatedLines: Long,
    val productionLines: Long,
    val commentLines: Long,
    val testFiles: Int,
    val testLines: Long,
    val readme: Boolean,
    /** `null` si no se analizaron las dependencias. */
    val dependencies: DependencySummary?,
    val tools: Set<QualityToolKind>,
    val debt: Map<DebtKind, Int>,
    /** `false` si no se analizaron las relaciones entre archivos. */
    val relationsAnalyzed: Boolean,
    val filesInCycles: Int,
    val highCoupling: Int,
)

/**
 * Índice de mantenimiento (0–100), **orientativo**: media ponderada de nueve sub-índices. Un sub-índice que no se
 * puede evaluar (por ejemplo, la complejidad sin ninguna función medida) no cuenta y se dice por qué; los pesos de los
 * demás se reparten en proporción. Cada sub-índice lleva lo medido y la regla que lo convierte en un valor.
 */
object MaintenanceCalculator {

    /** Pesos en porcentaje (suman 100). */
    val WEIGHTS = linkedMapOf(
        "complexity" to 20,
        "size" to 10,
        "duplication" to 15,
        "tests" to 15,
        "documentation" to 10,
        "dependencies" to 10,
        "tools" to 5,
        "debt" to 5,
        "structure" to 10,
    )

    const val FORMULA = "Índice = suma de (peso × valor) de los sub-índices evaluados ÷ suma de sus pesos. " +
        "Cada valor va de 0 a 100. Es un indicador orientativo para comparar y priorizar, no un estándar."

    fun compute(input: MaintenanceInputs): MaintenanceIndex {
        val parts = listOf(
            complexity(input), size(input), duplication(input), tests(input), documentation(input),
            dependencies(input), tools(input), debt(input), structure(input),
        )
        val evaluated = parts.filter { it.score != null }
        val notes = mutableListOf<String>()
        val score = if (evaluated.isEmpty()) {
            notes += "No se pudo evaluar ningún sub-índice: no hay código que medir."
            null
        } else {
            val weight = evaluated.sumOf { it.weight }
            if (weight < 50) notes += "Solo se pudieron evaluar sub-índices que suman un $weight % del peso: el índice es poco representativo."
            (evaluated.sumOf { it.weight * it.score!! }.toDouble() / weight).roundToInt().coerceIn(0, 100)
        }
        parts.filter { it.score == null }.forEach { notes += "${it.name}: no evaluado (${it.measure.replaceFirstChar { c -> c.lowercase() }})." }
        return MaintenanceIndex(score, score?.let(::level), parts, FORMULA, notes.take(20))
    }

    fun level(score: Int): MaintenanceLevel = when {
        score >= 85 -> MaintenanceLevel.HIGH
        score >= 70 -> MaintenanceLevel.GOOD
        score >= 50 -> MaintenanceLevel.MEDIUM
        score >= 30 -> MaintenanceLevel.LOW
        else -> MaintenanceLevel.VERY_LOW
    }

    // ------------------------------------------------------------------ sub-índices

    private fun part(id: String, name: String, score: Double?, measure: String, explanation: String) =
        MaintenancePart(id, name, WEIGHTS.getValue(id), score?.let { (it * 100).roundToInt().coerceIn(0, 100) }, measure, explanation)

    private fun complexity(i: MaintenanceInputs): MaintenancePart {
        if (i.functions == 0) {
            return part(
                "complexity", "Complejidad", null, "No se midió ninguna función (lenguajes sin gramática o código sin funciones)",
                "100 − (2 × % de funciones con complejidad > 10 + 3 × % con complejidad > 20); mínimo 0.",
            )
        }
        val a = i.functionsOver10.toDouble() / i.functions
        val b = i.functionsOver20.toDouble() / i.functions
        return part(
            "complexity", "Complejidad", 1 - minOf(1.0, 2 * a + 3 * b),
            "${pct(a)} de las ${i.functions} funciones medidas tiene complejidad mayor que 10, y ${pct(b)} mayor que 20",
            "100 − (2 × % de funciones con complejidad > 10 + 3 × % con complejidad > 20); mínimo 0.",
        )
    }

    private fun size(i: MaintenanceInputs): MaintenancePart {
        val rule = "100 − (1,5 × % de archivos con más de 500 líneas de código + 2 × % de funciones con más de 60 líneas); mínimo 0."
        if (i.codeFiles == 0) return part("size", "Tamaño", null, "No hay archivos de código", rule)
        val f = i.largeFiles.toDouble() / i.codeFiles
        val g = if (i.functions == 0) 0.0 else i.longFunctions.toDouble() / i.functions
        val functions = if (i.functions == 0) "funciones sin medir" else "${pct(g)} de las funciones con más de 60 líneas"
        return part("size", "Tamaño", 1 - minOf(1.0, 1.5 * f + 2 * g), "${pct(f)} de los archivos con más de 500 líneas de código; $functions", rule)
    }

    private fun duplication(i: MaintenanceInputs): MaintenancePart {
        val rule = "100 − 5 × % de líneas repetidas (un 20 % o más da 0)."
        if (i.duplicationBase == 0L) return part("duplication", "Duplicación", null, "No hay líneas de código que comparar", rule)
        val d = i.duplicatedLines.toDouble() / i.duplicationBase
        return part("duplication", "Duplicación", 1 - minOf(1.0, d / 0.20), "${pct(d)} de las líneas de código están repetidas en bloques de 6 o más líneas", rule)
    }

    private fun tests(i: MaintenanceInputs): MaintenancePart {
        val rule = "Líneas de pruebas respecto a las de código: un 50 % o más da 100; sin pruebas, 0."
        if (i.productionLines == 0L) return part("tests", "Pruebas", null, "No hay código de producción", rule)
        if (i.testFiles == 0) return part("tests", "Pruebas", 0.0, "No se encontraron archivos de pruebas", rule)
        val r = i.testLines.toDouble() / i.productionLines
        return part("tests", "Pruebas", minOf(1.0, r / 0.5), (if (i.testFiles == 1) "1 archivo de pruebas" else "${i.testFiles} archivos de pruebas") + ": ${pct(r)} de líneas de pruebas respecto a las de código", rule)
    }

    private fun documentation(i: MaintenanceInputs): MaintenancePart {
        val rule = "Comentarios: hasta 80 puntos (un 20 % de líneas de comentario los da todos); README: 20 puntos."
        if (i.productionLines == 0L) return part("documentation", "Documentación", null, "No hay código de producción", rule)
        val c = i.commentLines.toDouble() / (i.productionLines + i.commentLines)
        val readme = if (i.readme) "con README" else "sin README"
        return part("documentation", "Documentación", (minOf(0.8, c / 0.20 * 0.8) + if (i.readme) 0.2 else 0.0), "${pct(c)} de líneas de comentario, $readme", rule)
    }

    private fun dependencies(i: MaintenanceInputs): MaintenancePart {
        val rule = "100 − 15 por vulnerabilidad crítica, 10 por alta, 4 por media, 1 por baja, 40 por paquete malicioso, " +
            "15 por versión sin soporte y 5 por versión que lo pierde pronto; mínimo 0."
        val d = i.dependencies ?: return part("dependencies", "Salud de dependencias", null, "No se analizaron las dependencias", rule)
        if (d.direct + d.indirect == 0 && d.endOfLife == 0 && d.endingSoon == 0) {
            return part("dependencies", "Salud de dependencias", null, "El proyecto no declara dependencias", rule)
        }
        // Hay ecosistemas con base en OSV pero ninguno descargado: sin datos no se puede decir que estén sanas.
        if (d.databases.isNotEmpty() && d.databases.none { it.date != null }) {
            return part("dependencies", "Salud de dependencias", null, "Falta la base de vulnerabilidades: se descarga en Ajustes", rule)
        }
        val s = d.bySeverity
        fun n(sev: Severity) = s[sev] ?: 0
        val penalty = 15 * n(Severity.CRITICAL) + 10 * n(Severity.HIGH) + 4 * n(Severity.MEDIUM) + n(Severity.LOW) +
            40 * d.malicious + 15 * d.endOfLife + 5 * d.endingSoon
        val measure = "${d.vulnerabilities} vulnerabilidades conocidas en ${d.vulnerablePackages} paquetes" +
            (if (d.malicious > 0) ", ${d.malicious} malicioso(s)" else "") +
            ", ${d.endOfLife} versiones sin soporte y ${d.endingSoon} que lo pierden pronto" +
            (if (d.databases.isEmpty()) " (sus ecosistemas no tienen base de vulnerabilidades)" else "")
        return part("dependencies", "Salud de dependencias", (100 - penalty).coerceAtLeast(0) / 100.0, measure, rule)
    }

    private fun tools(i: MaintenanceInputs): MaintenancePart {
        val rule = "Analizador de código o comprobación de tipos: 40; integración continua: 40; formateador: 20."
        if (i.codeFiles == 0) return part("tools", "Herramientas de calidad", null, "No hay archivos de código", rule)
        val linter = QualityToolKind.LINTER in i.tools || QualityToolKind.TYPE_CHECKER in i.tools
        val ci = QualityToolKind.CI in i.tools
        val formatter = QualityToolKind.FORMATTER in i.tools
        val found = listOfNotNull("analizador de código".takeIf { linter }, "integración continua".takeIf { ci }, "formateador".takeIf { formatter })
        val measure = if (found.isEmpty()) "No se encontró configuración de analizadores, formateadores ni integración continua" else "Configurado: ${found.joinToString()}"
        return part("tools", "Herramientas de calidad", ((if (linter) 40 else 0) + (if (ci) 40 else 0) + (if (formatter) 20 else 0)) / 100.0, measure, rule)
    }

    private fun debt(i: MaintenanceInputs): MaintenancePart {
        val rule = "Marcas por cada 1000 líneas de código (FIXME y HACK cuentan doble): 0 da 100 y 10 o más dan 0."
        if (i.productionLines == 0L) return part("debt", "Deuda señalada", null, "No hay código de producción", rule)
        val todo = (i.debt[DebtKind.TODO] ?: 0) + (i.debt[DebtKind.XXX] ?: 0)
        val severe = (i.debt[DebtKind.FIXME] ?: 0) + (i.debt[DebtKind.HACK] ?: 0)
        val density = (todo + 2.0 * severe) * 1000.0 / i.productionLines
        return part("debt", "Deuda señalada", 1 - minOf(1.0, density / 10), "$todo TODO/XXX y $severe FIXME/HACK (${num(density)} por cada 1000 líneas)", rule)
    }

    private fun structure(i: MaintenanceInputs): MaintenancePart {
        val rule = "100 − (2 × % de archivos en ciclos de dependencias + 3 × % de archivos que dependen de 20 o más); mínimo 0."
        if (!i.relationsAnalyzed) return part("structure", "Estructura", null, "No se analizaron las relaciones entre archivos", rule)
        if (i.codeFiles == 0) return part("structure", "Estructura", null, "No hay archivos de código", rule)
        val c = i.filesInCycles.toDouble() / i.codeFiles
        val k = i.highCoupling.toDouble() / i.codeFiles
        return part(
            "structure", "Estructura", 1 - minOf(1.0, 2 * c + 3 * k),
            "${pct(c)} de los archivos en ciclos de dependencias y ${pct(k)} con acoplamiento extremo",
            rule,
        )
    }

    private val SPANISH: Locale = Locale.forLanguageTag("es-ES")

    /** Porcentaje en español: «4,5 %», «12 %». */
    internal fun pct(fraction: Double): String {
        val p = fraction * 100
        return if (p > 0 && p < 10) String.format(SPANISH, "%.1f %%", p) else "${p.roundToInt()} %"
    }

    private fun num(value: Double): String = String.format(SPANISH, "%.1f", value)
}
