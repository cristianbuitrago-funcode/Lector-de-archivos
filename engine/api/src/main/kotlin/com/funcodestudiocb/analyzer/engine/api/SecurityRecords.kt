package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Certeza de un hallazgo (§6.1), independiente de su [Severity]:
 * - [CONFIRMED]: la condición es observable en el código o la configuración;
 * - [PROBABLE]: patrón peligroso y un flujo de datos desde una entrada del usuario;
 * - [POSSIBLE]: patrón peligroso sin flujo demostrable;
 * - [DYNAMIC]: no se puede decidir sin ejecutar la aplicación (requiere prueba dinámica).
 */
@Serializable
enum class Certainty { CONFIRMED, PROBABLE, POSSIBLE, DYNAMIC }

/** Categorías de la auditoría (§8.2), más las supresiones de herramientas (§8.5). */
@Serializable
enum class SecurityCategory {
    SQL_INJECTION,
    XSS,
    CSRF,
    PATH_TRAVERSAL,
    COMMAND_EXECUTION,
    HARDCODED_PASSWORD,
    EXPOSED_SECRET,
    INSECURE_CONFIGURATION,
    EXCESSIVE_PERMISSIONS,
    WEAK_AUTHENTICATION,
    INSUFFICIENT_VALIDATION,
    VULNERABLE_DEPENDENCY,
    OUTDATED_LIBRARY,
    CORS,
    SENSITIVE_DATA_EXPOSURE,
    SESSION,
    ACCESS_CONTROL,

    /** Comentarios o archivos que silencian herramientas de seguridad: el motor no los respeta y los señala. */
    SUPPRESSION,
}

/** Paso del recorrido de un dato desde la entrada del usuario hasta el punto peligroso. */
@Serializable
data class FlowStep(val line: Int, val description: String)

/** Hallazgo de seguridad con su ficha completa (§8.3). Nunca contiene un secreto completo. */
@Serializable
@SerialName("finding")
data class SecurityFinding(
    /** Regla que lo produjo (`FC-SQLI-01`) y su versión. */
    val ruleId: String,
    val ruleVersion: Int,
    val name: String,
    val category: SecurityCategory,
    val cwe: Int? = null,
    /** Categoría del OWASP Top 10 (2021): `A03:2021 Inyección`. */
    val owasp: String? = null,
    /** Archivo y líneas; `null` si el hallazgo es del proyecto entero. */
    val path: String? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val severity: Severity,
    val certainty: Certainty,
    val explanation: String,
    val consequences: String,
    /** Fragmento del código, de la copia ya redactada, a partir de [snippetLine]. */
    val snippet: String? = null,
    val snippetLine: Int? = null,
    val recommendation: String,
    val fixedExample: String? = null,
    val references: List<String> = emptyList(),
    val flow: List<FlowStep> = emptyList(),
    /** Motivo por el que se rebajó la severidad (archivo de pruebas, valor de ejemplo…); el hallazgo no se oculta. */
    val downgraded: String? = null,
    /** Secretos: tipo, máscara (4 + 2 caracteres como máximo) y huella HMAC-SHA256 con la clave local. */
    val secretType: SecretType? = null,
    val masked: String? = null,
    val fingerprint: String? = null,
) : ResultRecord

/** Totales de la auditoría. */
@Serializable
@SerialName("security_summary")
data class SecuritySummary(
    val findings: Int,
    val bySeverity: Map<Severity, Int>,
    val byCertainty: Map<Certainty, Int>,
    val byCategory: Map<SecurityCategory, Int>,
    /** Reglas que se aplicaron y archivos de texto examinados. */
    val rules: Int,
    val filesExamined: Int,
    val downgraded: Int,
    val suppressions: Int,
    /** Comprobaciones que solo una prueba dinámica puede hacer (DAST, pruebas de penetración…). */
    val dynamicChecks: List<String>,
    /** Categorías que esta versión no evalúa todavía, con el motivo. */
    val notEvaluated: List<String>,
) : ResultRecord
