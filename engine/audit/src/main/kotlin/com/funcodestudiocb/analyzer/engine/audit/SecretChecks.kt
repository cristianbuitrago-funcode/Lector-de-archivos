package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.SecretType
import com.funcodestudiocb.analyzer.engine.api.Severity

/**
 * Secretos (§8.4): convierte los posibles secretos que el redactor ya ocultó en hallazgos con su máscara y su huella.
 * El valor completo nunca llega aquí (el texto ya está redactado y [com.funcodestudiocb.analyzer.engine.security.DetectedSecret]
 * solo lleva tipo, máscara y huella).
 */
internal object SecretChecks {

    private val LABELS = mapOf(
        SecretType.PRIVATE_KEY to "clave privada",
        SecretType.AWS_ACCESS_KEY to "identificador de clave de acceso de AWS",
        SecretType.AWS_SECRET_KEY to "clave secreta de AWS",
        SecretType.GITHUB_TOKEN to "token de GitHub",
        SecretType.GITLAB_TOKEN to "token de GitLab",
        SecretType.GOOGLE_API_KEY to "clave de API de Google",
        SecretType.GOOGLE_OAUTH_SECRET to "secreto de cliente OAuth de Google",
        SecretType.SLACK_TOKEN to "token de Slack",
        SecretType.SLACK_WEBHOOK to "webhook de Slack",
        SecretType.STRIPE_KEY to "clave secreta de Stripe",
        SecretType.SENDGRID_KEY to "clave de SendGrid",
        SecretType.OPENAI_KEY to "clave de OpenAI",
        SecretType.ANTHROPIC_KEY to "clave de Anthropic",
        SecretType.JWT to "token JWT",
        SecretType.FIREBASE_SERVER_KEY to "clave de servidor de Firebase Cloud Messaging",
        SecretType.CONNECTION_STRING_PASSWORD to "contraseña dentro de una cadena de conexión",
        SecretType.PASSWORD to "contraseña",
        SecretType.SECRET to "secreto, token o clave",
    )

    /** Palabras de la línea que indican que el secreto firma tokens o sesiones (FC-AUTH-03). */
    private val SIGNING_WORDS = listOf(
        "jwt", "session", "signing", "hmac", "cookieparser", "secret_key", "app.secret", "app_key", "symmetricsecuritykey", "signwith",
        "setsigningkey", "token_secret", "tokensecret",
    )

    fun check(a: FileAudit) {
        val secrets = a.file.secrets
        if (secrets.isEmpty()) return
        val serviceAccount = a.ext == "json" && a.text.contains("service_account") && a.text.contains("\"private_key\"")
        val clientConfig = a.name == "google-services.json" || a.name == "googleservice-info.plist" ||
            a.text.contains("authDomain") || a.text.contains("firebaseConfig") || a.name == "androidmanifest.xml"
        val devConfig = if (!a.code) Context.devConfig(a.path) else null
        if (secrets.any { it.type == SecretType.PRIVATE_KEY }) a.sink.privateKeyFiles += a.path
        for (s in secrets) {
            val label = LABELS[s.type] ?: "secreto"
            var rule: String
            var severity: Severity? = null
            var certainty = Certainty.CONFIRMED
            var detail = "Tipo: $label (se muestra enmascarado: ${s.masked})."
            var downgraded: String? = null
            when (s.type) {
                SecretType.PRIVATE_KEY -> rule = if (serviceAccount) "FC-FILE-06" else "FC-SECRET-01"
                SecretType.PASSWORD -> {
                    rule = "FC-SECRET-03"
                    certainty = Certainty.PROBABLE
                }
                SecretType.CONNECTION_STRING_PASSWORD -> rule = "FC-SECRET-04"
                SecretType.SECRET -> {
                    certainty = Certainty.PROBABLE
                    val lineText = a.src.lineText(s.line).lowercase()
                    rule = if (a.code && SIGNING_WORDS.any { lineText.contains(it) }) "FC-AUTH-03" else "FC-SECRET-05"
                }
                SecretType.JWT -> {
                    rule = "FC-SECRET-05"
                    severity = Severity.MEDIUM
                    detail += " Puede estar caducado, pero revela su contenido y, si sigue vigente, da acceso."
                }
                SecretType.GOOGLE_API_KEY -> {
                    rule = "FC-SECRET-02"
                    severity = Severity.MEDIUM
                    if (clientConfig) {
                        severity = Severity.INFO
                        downgraded = "Clave de cliente de Firebase o Google pensada para incluirse en la app; conviene restringirla en la consola"
                    }
                }
                SecretType.STRIPE_KEY -> {
                    rule = "FC-SECRET-02"
                    if (s.masked.startsWith("sk_t") || s.masked.startsWith("rk_t")) {
                        severity = Severity.LOW
                        downgraded = "Clave del modo de pruebas de Stripe (no mueve dinero real)"
                    }
                }
                else -> rule = "FC-SECRET-02"
            }
            val base = severity ?: com.funcodestudiocb.analyzer.engine.security.SecurityRules.get(rule).severity
            when {
                s.placeholder -> {
                    severity = Severity.INFO
                    downgraded = "El valor parece de ejemplo o un marcador, no una credencial real"
                }
                downgraded == null && devConfig != null -> {
                    severity = Context.lower(base)
                    downgraded = devConfig
                }
            }
            val flow = listOf(FlowStep(s.line, "Valor de $label escrito tal cual (${s.length} caracteres; se muestra enmascarado)"))
            a.add(rule, s.line, certainty, detail, flow = flow, secret = s, severity = severity ?: base, downgraded = downgraded)
        }
    }
}
