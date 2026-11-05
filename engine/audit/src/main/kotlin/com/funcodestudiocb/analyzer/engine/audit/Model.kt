package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.relations.framework.Src
import com.funcodestudiocb.analyzer.engine.security.DetectedSecret
import com.funcodestudiocb.analyzer.engine.security.SecretRedactor
import com.funcodestudiocb.analyzer.engine.security.SecurityRule

/** Hallazgo antes de completarse con los textos de su regla. */
internal class Finding(
    val rule: SecurityRule,
    val path: String?,
    val line: Int?,
    val certainty: Certainty,
    var severity: Severity = rule.severity,
    /** Frase concreta de este caso, que precede a la explicación general de la regla. */
    val detail: String? = null,
    val flow: List<FlowStep> = emptyList(),
    val snippet: String? = null,
    val snippetLine: Int? = null,
    val language: String? = null,
    var secret: DetectedSecret? = null,
    var downgraded: String? = null,
) {
    val cwe: Int? get() = rule.cwe

    fun toRecord(): SecurityFinding = SecurityFinding(
        ruleId = rule.id,
        ruleVersion = rule.version,
        name = rule.name,
        category = rule.category,
        cwe = rule.cwe,
        owasp = rule.owasp,
        path = path,
        line = line,
        severity = severity,
        certainty = certainty,
        explanation = ((detail?.let { "$it " } ?: "") + rule.explanation).take(RecordValidator.MAX_FINDING_TEXT),
        consequences = rule.consequences.take(RecordValidator.MAX_FINDING_TEXT),
        snippet = snippet,
        snippetLine = snippetLine.takeIf { snippet != null },
        recommendation = rule.recommendation.take(RecordValidator.MAX_FINDING_TEXT),
        fixedExample = rule.example(language)?.take(RecordValidator.MAX_FIXED_EXAMPLE),
        references = rule.references.take(RecordValidator.MAX_REFERENCES),
        flow = flow.take(RecordValidator.MAX_FLOW_STEPS).map { FlowStep(it.line, it.description.take(RecordValidator.MAX_EVIDENCE_DETAIL)) },
        downgraded = downgraded,
        secretType = secret?.type,
        masked = secret?.masked,
        fingerprint = secret?.fingerprint,
    )
}

/** Fragmentos de código para la ficha: siempre de la copia redactada, con lo indicado además enmascarado. */
internal object Snippets {
    private const val MAX_LINE = 200

    /**
     * Líneas alrededor de [line] (la anterior y la siguiente). [mask] son rangos del texto que se ocultan aunque el
     * redactor no los haya considerado secretos (por ejemplo, la clave literal de jwt.sign).
     */
    fun around(src: Src, line: Int, mask: List<IntRange> = emptyList(), before: Int = 1, after: Int = 1): Pair<String, Int> {
        val first = (line - before).coerceAtLeast(1)
        val last = (line + after).coerceAtMost(src.lineCount)
        val out = StringBuilder()
        for (n in first..last) {
            val start = src.lineStart(n)
            var text = src.lineText(n)
            if (mask.isNotEmpty()) {
                val chars = text.toCharArray()
                for (r in mask) for (i in r) {
                    val k = i - start
                    if (k in chars.indices) chars[k] = SecretRedactor.MASK
                }
                text = String(chars)
            }
            if (text.length > MAX_LINE) text = text.take(MAX_LINE - 1) + "…"
            if (out.isNotEmpty()) out.append('\n')
            out.append(text.filter { it == '\t' || !it.isISOControl() })
        }
        return out.toString().take(RecordValidator.MAX_SNIPPET_CHARS) to first
    }
}

/** Contexto que rebaja la severidad sin ocultar el hallazgo (§8.6). */
internal object Context {
    private val TEST_DIRS = setOf(
        "test", "tests", "__tests__", "spec", "specs", "androidtest", "testing", "e2e", "cypress", "fixtures", "fixture", "mocks", "__mocks__",
        "testdata", "test-data",
    )
    private val EXAMPLE_DIRS = setOf("example", "examples", "sample", "samples", "demo", "demos", "docs", "doc", "tutorial", "tutorials")
    private val DEV_WORDS = listOf("dev", "local", "development", "test", "testing", "debug", "example", "sample")

    /** Motivo de rebaja por la ubicación del archivo, o `null`. */
    fun location(path: String?): String? {
        if (path == null) return null
        val parts = path.lowercase().split('/')
        val name = parts.last()
        val dirs = parts.dropLast(1)
        return when {
            dirs.any { it in TEST_DIRS } || name.contains(".test.") || name.contains(".spec.") || name.startsWith("test_") ||
                name.substringBeforeLast('.').endsWith("_test") || name.substringBeforeLast('.').endsWith("test") && name.endsWith(".kt") ||
                name.substringBeforeLast('.').endsWith("test") && name.endsWith(".java") || name.substringBeforeLast('.').endsWith("tests") -> "archivo de pruebas"
            // Solo cerca de la raíz: `com/demo` o `com/example` son paquetes de código, no ejemplos.
            dirs.take(3).any { it in EXAMPLE_DIRS } -> "código de ejemplo o documentación"
            else -> null
        }
    }

    /** Archivo de configuración de desarrollo (settings_dev.py, .env.development, application-dev.yml…). */
    fun devConfig(path: String): String? {
        val name = path.substringAfterLast('/').lowercase()
        val stem = name.removePrefix(".env").removePrefix(".")
        val words = stem.split('.', '-', '_')
        return if (words.any { it in DEV_WORDS }) "configuración de desarrollo o de ejemplo" else null
    }

    fun lower(severity: Severity): Severity = when (severity) {
        Severity.CRITICAL -> Severity.HIGH
        Severity.HIGH -> Severity.MEDIUM
        Severity.MEDIUM -> Severity.LOW
        Severity.LOW, Severity.INFO -> Severity.INFO
    }
}
