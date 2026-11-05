package com.funcodestudiocb.analyzer.engine.vulndb

import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.Severity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** Aviso de OSV tal como se publica (https://ossf.github.io/osv-schema/), solo con los campos que se usan. */
@Serializable
data class OsvRecord(
    val id: String,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val details: String? = null,
    val modified: String? = null,
    val published: String? = null,
    val withdrawn: String? = null,
    val severity: List<OsvSeverity> = emptyList(),
    val affected: List<OsvAffected> = emptyList(),
    @SerialName("database_specific") val databaseSpecific: JsonElement? = null,
)

@Serializable
data class OsvSeverity(val type: String, val score: String)

@Serializable
data class OsvAffected(
    @SerialName("package") val pkg: OsvPackage? = null,
    val ranges: List<OsvRange> = emptyList(),
    val versions: List<String> = emptyList(),
    val severity: List<OsvSeverity> = emptyList(),
    @SerialName("database_specific") val databaseSpecific: JsonElement? = null,
)

@Serializable
data class OsvPackage(val ecosystem: String, val name: String)

@Serializable
data class OsvRange(val type: String, val events: List<JsonObject> = emptyList())

/** Ecosistemas de OSV que la app sabe usar y su relación con los de los manifiestos. */
object OsvEcosystems {
    /** Nombres exactos de los volcados (`https://osv-vulnerabilities.storage.googleapis.com/<nombre>/all.zip`). */
    val SUPPORTED = listOf("npm", "PyPI", "Maven", "Packagist", "NuGet", "Pub", "Go", "crates.io", "RubyGems")

    fun of(ecosystem: Ecosystem): String? = when (ecosystem) {
        Ecosystem.NPM -> "npm"
        Ecosystem.PYTHON -> "PyPI"
        Ecosystem.MAVEN, Ecosystem.GRADLE, Ecosystem.ANDROID -> "Maven"
        Ecosystem.PHP_COMPOSER -> "Packagist"
        Ecosystem.DOTNET -> "NuGet"
        Ecosystem.DART_PUB -> "Pub"
        Ecosystem.GO -> "Go"
        Ecosystem.RUST_CARGO -> "crates.io"
        Ecosystem.RUBY -> "RubyGems"
        else -> null
    }

    /** Nombre normalizado para buscar: PEP 503 en PyPI, `_` = `-` en crates.io, sin mayúsculas salvo en Go. */
    fun normalize(osvEcosystem: String, name: String): String = when (osvEcosystem) {
        "PyPI" -> buildString {
            var dash = false
            for (c in name.trim().lowercase()) {
                if (c == '-' || c == '_' || c == '.') {
                    if (!dash) append('-')
                    dash = true
                } else {
                    append(c)
                    dash = false
                }
            }
        }
        "crates.io" -> name.trim().lowercase().replace('_', '-')
        "Go" -> name.trim()
        else -> name.trim().lowercase()
    }

    fun key(osvEcosystem: String, name: String): String = osvEcosystem + "\u0000" + normalize(osvEcosystem, name)
}

/** Severidad de un aviso: la etiqueta de la base de datos o la que resulta de su vector CVSS 3.x. */
object OsvSeverityRating {
    /** (severidad, puntuación CVSS o `null`, vector o `null`). */
    fun of(record: OsvRecord, affected: OsvAffected?): Triple<Severity?, Double?, String?> {
        val vectors = (affected?.severity.orEmpty() + record.severity)
        val v3 = vectors.firstOrNull { it.type == "CVSS_V3" }?.score
        val score = v3?.let { Cvss3.baseScore(it) }
        val label = label(record.databaseSpecific) ?: label(affected?.databaseSpecific)
        val severity = label ?: score?.let { Cvss3.rating(it) }
        return Triple(severity, score, v3 ?: vectors.firstOrNull()?.score)
    }

    private fun label(element: JsonElement?): Severity? {
        val text = ((element as? JsonObject)?.get("severity") as? JsonPrimitive)?.content?.uppercase() ?: return null
        return when (text) {
            "CRITICAL" -> Severity.CRITICAL
            "HIGH" -> Severity.HIGH
            "MODERATE", "MEDIUM" -> Severity.MEDIUM
            "LOW" -> Severity.LOW
            else -> null
        }
    }

    /** CWE indicados por la base de datos (`cwe_ids`). */
    fun cwes(record: OsvRecord): List<String> =
        ((record.databaseSpecific as? JsonObject)?.get("cwe_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}

/** Puntuación base de CVSS 3.0/3.1 a partir del vector (especificación de FIRST, sección 7). */
object Cvss3 {
    fun baseScore(vector: String): Double? {
        if (!vector.startsWith("CVSS:3.")) return null
        val m = vector.split('/').drop(1).mapNotNull { part -> part.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val scope = m["S"] ?: return null
        val changed = scope == "C"
        val av = when (m["AV"]) { "N" -> 0.85; "A" -> 0.62; "L" -> 0.55; "P" -> 0.2; else -> return null }
        val ac = when (m["AC"]) { "L" -> 0.77; "H" -> 0.44; else -> return null }
        val pr = when (m["PR"]) { "N" -> 0.85; "L" -> if (changed) 0.68 else 0.62; "H" -> if (changed) 0.5 else 0.27; else -> return null }
        val ui = when (m["UI"]) { "N" -> 0.85; "R" -> 0.62; else -> return null }
        fun cia(k: String): Double? = when (m[k]) { "H" -> 0.56; "L" -> 0.22; "N" -> 0.0; else -> null }
        val c = cia("C") ?: return null
        val i = cia("I") ?: return null
        val a = cia("A") ?: return null
        val iss = 1 - (1 - c) * (1 - i) * (1 - a)
        val impact = if (changed) 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02).pow(15) else 6.42 * iss
        if (impact <= 0) return 0.0
        val exploitability = 8.22 * av * ac * pr * ui
        return roundUp(min(if (changed) 1.08 * (impact + exploitability) else impact + exploitability, 10.0))
    }

    /** Redondeo hacia arriba a un decimal de CVSS 3.1 (evita errores de coma flotante). */
    private fun roundUp(x: Double): Double {
        val i = (x * 100_000).roundToLong()
        return if (i % 10_000 == 0L) i / 100_000.0 else (floor(i / 10_000.0) + 1) / 10.0
    }

    fun rating(score: Double): Severity = when {
        score >= 9.0 -> Severity.CRITICAL
        score >= 7.0 -> Severity.HIGH
        score >= 4.0 -> Severity.MEDIUM
        score > 0.0 -> Severity.LOW
        else -> Severity.INFO
    }
}
