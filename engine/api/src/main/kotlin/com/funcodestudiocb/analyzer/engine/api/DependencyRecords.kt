package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** De dónde sale la versión de una dependencia (Fase 7). */
@Serializable
enum class VersionSource {
    /** Versión exacta de un archivo de bloqueo: es la que se instala. */
    LOCKFILE,

    /** Versión exacta escrita en el manifiesto (`"1.2.3"`, `==1.2.3`, Maven, Gradle). */
    EXACT_IN_MANIFEST,

    /** Rango (`^1.2.0`, `>=2`): la instalada depende de cuándo se instale; se evalúa la mínima que permite. */
    RANGE_IN_MANIFEST,

    /** Sin versión (catálogo, BOM, variable): no se puede comparar. */
    UNKNOWN,
}

/** Uso real de una dependencia en el código del proyecto. */
@Serializable
enum class UsageStatus {
    /** Se importa en el código (con los archivos que la usan). */
    USED,

    /** Dependencia directa de ejecución que no se importa en ningún archivo analizado (puede cargarse por configuración). */
    NOT_SEEN,

    /** Herramienta de desarrollo, pruebas o compilación: no se espera que el código la importe. */
    DEVELOPMENT_TOOL,

    /** Dependencia indirecta: la usa otra dependencia, no el proyecto. */
    INDIRECT,

    /** No se pudo saber: el lenguaje no tiene análisis de importaciones o este no terminó. */
    NOT_ANALYZED,
}

@Serializable
enum class Criticality { HIGH, MEDIUM, LOW }

/** Vulnerabilidad conocida de una dependencia, con su certeza para la versión del proyecto. */
@Serializable
data class VulnerabilityRef(
    /** Identificador de OSV (`GHSA-…`, `PYSEC-…`, `MAL-…`) y sus alias (`CVE-…`). */
    val id: String,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val severity: Severity,
    /** Puntuación CVSS 3.x o `null` si la base no la da. */
    val score: Double? = null,
    /** Versiones que la corrigen (vacío: sin corrección publicada). */
    val fixedIn: List<String> = emptyList(),
    val malicious: Boolean = false,
    /** CONFIRMED con versión exacta; POSSIBLE si se evaluó la mínima de un rango. */
    val certainty: Certainty,
    val published: String? = null,
)

/** Dependencia del proyecto, directa o indirecta, con su versión, su uso, su criticidad y sus vulnerabilidades. */
@Serializable
@SerialName("resolved_dependency")
data class ResolvedDependency(
    val ecosystem: Ecosystem,
    val name: String,
    /** Versión evaluada (exacta o la mínima del rango) o `null`. */
    val version: String? = null,
    /** Lo que declara el manifiesto, si lo declara (`^4.17.0`). */
    val declared: String? = null,
    val versionSource: VersionSource,
    val direct: Boolean,
    val scope: DependencyScope,
    val projectRoot: String? = null,
    /** Dónde se declara (manifiesto y línea) y de qué archivo de bloqueo sale la versión. */
    val manifest: String? = null,
    val manifestLine: Int? = null,
    val lockfile: String? = null,
    val lockfileLine: Int? = null,
    /** Otros manifiestos o módulos que declaran la misma dependencia con la misma versión ("archivo:línea"), como mucho 5. */
    val alsoIn: List<String> = emptyList(),
    val alsoInCount: Int = 0,
    /** Cadenas de dependencias que la introducen (`express › body-parser › qs`), como mucho 3, y cuántas hay. */
    val introducedBy: List<String> = emptyList(),
    val introducedByCount: Int = 0,
    val usage: UsageStatus,
    /** Archivos que la importan ("archivo:línea"), como mucho 5, y cuántos son. */
    val usedIn: List<String> = emptyList(),
    val usedCount: Int = 0,
    val criticality: Criticality,
    val criticalityReasons: List<String> = emptyList(),
    val vulnerabilities: List<VulnerabilityRef> = emptyList(),
    val vulnerabilityCount: Int = 0,
) : ResultRecord

@Serializable
enum class EolStatus {
    /** El ciclo ya no recibe correcciones de seguridad. */
    UNSUPPORTED,

    /** El soporte termina en los próximos 6 meses. */
    ENDING_SOON,
    SUPPORTED,

    /** La versión no corresponde a ningún ciclo conocido de los datos descargados. */
    UNKNOWN_CYCLE,
}

/** Versión de un entorno de ejecución, framework, base de datos o herramienta y su fin de soporte (endoflife.date). */
@Serializable
@SerialName("end_of_life")
data class EndOfLife(
    /** Producto de endoflife.date (`nodejs`, `django`…) y su nombre. */
    val product: String,
    val label: String,
    /** Versión detectada y de dónde sale (`.nvmrc`, `Dockerfile`, una dependencia…). */
    val version: String,
    val source: String,
    val path: String? = null,
    val line: Int? = null,
    /** Otros archivos donde aparece la misma versión, como mucho 5, y cuántos son. */
    val alsoIn: List<String> = emptyList(),
    val alsoInCount: Int = 0,
    /** Ciclo de soporte al que pertenece (`18`, `3.2`). */
    val cycle: String? = null,
    val status: EolStatus,
    /** Fin del soporte de seguridad (AAAA-MM-DD) o `null`. */
    val eolDate: String? = null,
    /** Última versión publicada de ese ciclo. */
    val latestInCycle: String? = null,
    /** La versión es la mínima de un rango (`engines: >=14`): el proyecto admite versiones sin soporte. */
    val minimumOfRange: Boolean = false,
) : ResultRecord

/** Estado de la base de vulnerabilidades de un ecosistema en este análisis. */
@Serializable
data class VulnDatabaseState(
    val ecosystem: String,
    /** Fecha de los datos (milisegundos) o `null` si este ecosistema no está descargado. */
    val date: Long? = null,
    val advisories: Int = 0,
)

/** Totales de las dependencias. */
@Serializable
@SerialName("dependency_summary")
data class DependencySummary(
    val direct: Int,
    val indirect: Int,
    val lockfiles: Int,
    /** Dependencias con versión exacta (de un archivo de bloqueo o del manifiesto). */
    val exactVersions: Int,
    val vulnerablePackages: Int,
    val vulnerabilities: Int,
    val bySeverity: Map<Severity, Int>,
    val malicious: Int,
    val notSeen: Int,
    val endOfLife: Int,
    val endingSoon: Int,
    /** Bases usadas por ecosistema del proyecto (con su fecha) y antigüedad de la más antigua, en días. */
    val databases: List<VulnDatabaseState>,
    val databaseAgeDays: Int? = null,
    val eolDataDate: Long? = null,
    /** Lo que no se pudo evaluar y por qué. */
    val notEvaluated: List<String>,
) : ResultRecord
