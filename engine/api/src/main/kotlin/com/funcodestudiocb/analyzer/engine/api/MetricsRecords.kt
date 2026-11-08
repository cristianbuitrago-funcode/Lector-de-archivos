package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------------------------
// Fase 9: métricas de mantenimiento. Complejidad ciclomática medida en el árbol sintáctico, tamaño, duplicación,
// pruebas, documentación, deuda señalada y herramientas de calidad, y el índice de mantenimiento (orientativo).
// ---------------------------------------------------------------------------------------------------------------

/** Métricas de un archivo de código (no generado). */
@Serializable
@SerialName("file_metrics")
data class FileMetrics(
    val path: String,
    /** Identificador del lenguaje (`kotlin`, `python`…). */
    val language: String,
    val codeLines: Int,
    val commentLines: Int,
    /** Archivo de pruebas. */
    val test: Boolean,
    /** La complejidad se midió en el árbol sintáctico; si no, las cifras de funciones son 0. */
    val measured: Boolean,
    val functions: Int = 0,
    /** Suma de la complejidad de sus funciones más las decisiones de nivel superior. */
    val complexity: Int = 0,
    val maxComplexity: Int = 0,
    /** Líneas de su función más larga. */
    val longestFunction: Int = 0,
    /** Líneas de código que forman parte de un bloque repetido (aquí o en otro archivo). */
    val duplicatedLines: Int = 0,
    /** Marcas TODO, FIXME, HACK o XXX en sus comentarios. */
    val debtMarkers: Int = 0,
) : ResultRecord

/** Una función señalada (la más compleja o la más larga). */
@Serializable
data class FunctionRef(
    val path: String,
    val name: String,
    val line: Int,
    val lines: Int,
    val complexity: Int,
)

/** Bloque de código repetido: sus líneas y dónde aparece (`ruta:línea`). */
@Serializable
data class DuplicateBlock(
    val lines: Int,
    val occurrences: List<String>,
    /** Veces que aparece en total (puede ser más que las mostradas). */
    val count: Int,
)

@Serializable
enum class DebtKind { TODO, FIXME, HACK, XXX }

/** Marca de deuda en un comentario, con su texto (ya redactado) recortado. */
@Serializable
data class DebtMarker(
    val kind: DebtKind,
    val path: String,
    val line: Int,
    val text: String,
)

@Serializable
enum class QualityToolKind {
    /** Analizador de código (ESLint, detekt, Ruff, Android Lint…). */
    LINTER,

    /** Formateador (Prettier, ktlint, Black, clang-format, .editorconfig…). */
    FORMATTER,

    /** Integración continua (GitHub Actions, GitLab CI, Jenkins…). */
    CI,

    /** Comprobación de tipos (mypy, PHPStan…). */
    TYPE_CHECKER,
}

/** Herramienta de calidad configurada en el proyecto y el archivo donde se configura. */
@Serializable
data class QualityTool(
    val kind: QualityToolKind,
    val name: String,
    val path: String,
)

/** Tamaño de un archivo grande. */
@Serializable
data class FileSize(val path: String, val lines: Int)

/** Métricas del proyecto: las cifras en las que se basa el índice de mantenimiento. */
@Serializable
@SerialName("metrics_summary")
data class MetricsSummary(
    val codeFiles: Int,
    val codeLines: Long,
    val commentLines: Long,
    val testFiles: Int,
    val testLines: Long,
    /** Archivos generados (bloqueo, minificados…) que no cuentan. */
    val generatedFiles: Int = 0,
    /** Archivos con complejidad medida y sus funciones. */
    val measuredFiles: Int,
    val functions: Int,
    val averageComplexity: Double? = null,
    val medianComplexity: Int? = null,
    val p90Complexity: Int? = null,
    val maxComplexity: Int? = null,
    /** Funciones con complejidad mayor que 10 (moderada) y que 20 (alta). */
    val functionsOver10: Int = 0,
    val functionsOver20: Int = 0,
    /** Funciones de más de 60 líneas y archivos de más de 500 líneas de código. */
    val longFunctions: Int = 0,
    val largeFiles: Int = 0,
    val complexFunctionList: List<FunctionRef> = emptyList(),
    val longFunctionList: List<FunctionRef> = emptyList(),
    val largeFileList: List<FileSize> = emptyList(),
    /** Líneas de código repetidas y líneas examinadas para buscar repeticiones. */
    val duplicatedLines: Long = 0,
    val duplicationBase: Long = 0,
    val duplicates: List<DuplicateBlock> = emptyList(),
    val debtByKind: Map<DebtKind, Int> = emptyMap(),
    val debtSamples: List<DebtMarker> = emptyList(),
    val qualityTools: List<QualityTool> = emptyList(),
    /** README principal, si lo hay. */
    val readme: String? = null,
    /** Archivos de código en ciclos de dependencias y número de ciclos. */
    val filesInCycles: Int = 0,
    val cycles: Int = 0,
    /** Archivos con acoplamiento extremo (dependen de muchos otros) y ejemplos. */
    val highCoupling: Int = 0,
    val highCouplingSamples: List<String> = emptyList(),
    /** Lenguajes cuyo código no se pudo medir (sin gramática): «Go (12 archivos)». */
    val notMeasured: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) : ResultRecord

@Serializable
enum class MaintenanceLevel { HIGH, GOOD, MEDIUM, LOW, VERY_LOW }

/** Sub-índice del índice de mantenimiento, con su peso, su valor y cómo se calcula. */
@Serializable
data class MaintenancePart(
    /** `complexity`, `size`, `duplication`, `tests`, `documentation`, `dependencies`, `tools`, `debt`, `structure`. */
    val id: String,
    val name: String,
    /** Peso en porcentaje (los de todos los sub-índices suman 100). */
    val weight: Int,
    /** 0–100, o `null` si no se pudo evaluar (no cuenta en el total y se dice por qué). */
    val score: Int? = null,
    /** Lo medido, en palabras: «8 % de las funciones con complejidad mayor que 10». */
    val measure: String,
    /** Cómo se convierte la medida en el valor. */
    val explanation: String,
)

/**
 * Índice de mantenimiento (0–100): indicador orientativo, no un estándar. Media ponderada de los sub-índices que se
 * pudieron evaluar; la fórmula y los pesos se muestran con el resultado.
 */
@Serializable
@SerialName("maintenance_index")
data class MaintenanceIndex(
    val score: Int? = null,
    val level: MaintenanceLevel? = null,
    val parts: List<MaintenancePart>,
    val formula: String,
    val notes: List<String> = emptyList(),
) : ResultRecord
