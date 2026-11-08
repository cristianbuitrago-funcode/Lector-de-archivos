package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty

/**
 * Comentarios que silencian herramientas de seguridad (§8.5). El motor no los respeta: lo que silencian se sigue
 * analizando, y el propio silencio se informa para que alguien lo revise.
 */
internal object SuppressionChecks {

    private class Marker(val needle: String, val tool: String, val inComment: Boolean = true, val nextMustNotBeLetter: Boolean = true)

    private val MARKERS = listOf(
        Marker("nosemgrep", "Semgrep"),
        Marker("nosec", "Bandit/gosec"),
        Marker("NOSONAR", "SonarQube"),
        Marker("noqa: S", "Bandit (flake8)", nextMustNotBeLetter = false),
        Marker("noqa:S", "Bandit (flake8)", nextMustNotBeLetter = false),
        Marker("gitleaks:allow", "Gitleaks"),
        Marker("pragma: allowlist secret", "detect-secrets"),
        Marker("trufflehog:ignore", "TruffleHog"),
        Marker("checkov:skip", "Checkov", nextMustNotBeLetter = false),
        Marker("tfsec:ignore", "tfsec", nextMustNotBeLetter = false),
        Marker("lgtm[", "CodeQL/LGTM", nextMustNotBeLetter = false),
        Marker("codeql[", "CodeQL", nextMustNotBeLetter = false),
        Marker("@SuppressLint(\"SetJavaScriptEnabled\")", "Android Lint (JavaScript en WebView)", inComment = false, nextMustNotBeLetter = false),
        Marker("@SuppressLint(\"TrustAllX509TrustManager\")", "Android Lint (certificados)", inComment = false, nextMustNotBeLetter = false),
        Marker("@SuppressLint(\"BadHostnameVerifier\")", "Android Lint (nombre del servidor)", inComment = false, nextMustNotBeLetter = false),
        Marker("@SuppressLint(\"AddJavascriptInterface\")", "Android Lint (interfaz JavaScript)", inComment = false, nextMustNotBeLetter = false),
        Marker("@SuppressLint(\"HardwareIds\")", "Android Lint (identificadores del dispositivo)", inComment = false, nextMustNotBeLetter = false),
    )

    /** Reglas de seguridad de ESLint que se desactivan con eslint-disable. */
    private val ESLINT_SECURITY = listOf("security/", "no-eval", "no-implied-eval", "react/no-danger", "no-unsanitized", "no-new-func", "xss/")

    fun check(a: FileAudit) {
        val text = a.text
        for (m in MARKERS) {
            if (!text.contains(m.needle)) continue
            var i = text.indexOf(m.needle)
            var n = 0
            while (i >= 0 && n < 100) {
                n++
                val nextChar = text.getOrNull(i + m.needle.length)
                val boundary = !m.nextMustNotBeLetter || nextChar == null || !nextChar.isLetter()
                val place = if (m.inComment) !a.src.isCode(i) || a.language == null || a.language in setOf("yaml", "toml", "properties", "dockerfile", "shell") else a.src.isCode(i)
                if (boundary && place) {
                    a.addAt("FC-SUPPRESS-01", i, Certainty.CONFIRMED, "Silencia ${m.tool} («${m.needle.take(50)}»).")
                }
                i = text.indexOf(m.needle, i + m.needle.length)
            }
        }
        if (text.contains("eslint-disable")) {
            var i = text.indexOf("eslint-disable")
            var n = 0
            while (i >= 0 && n < 100) {
                n++
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                val rest = text.substring(i, minOf(end, i + 300))
                val rule = ESLINT_SECURITY.firstOrNull { rest.contains(it) }
                if (rule != null) a.addAt("FC-SUPPRESS-01", i, Certainty.CONFIRMED, "Desactiva una regla de seguridad de ESLint («${rule.trimEnd('/')}»).")
                i = text.indexOf("eslint-disable", end)
            }
        }
        if (a.language == "csharp" && text.contains("#pragma warning disable")) {
            a.lines { n, line ->
                val t = line.trim()
                if (t.startsWith("#pragma warning disable") && (t.contains("SCS") || t.contains("CA53") || t.contains("CA21") || t.contains("CA30"))) {
                    a.add("FC-SUPPRESS-01", n, Certainty.CONFIRMED, "Desactiva avisos de seguridad del analizador de .NET.")
                }
            }
        }
    }
}
