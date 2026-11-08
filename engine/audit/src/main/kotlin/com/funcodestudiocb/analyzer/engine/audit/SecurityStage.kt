package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyKeys
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.relations.RelationKeys
import com.funcodestudiocb.analyzer.engine.security.SecurityRules

/**
 * Fase 6: auditoría de seguridad estática. Reúne los hallazgos de cada archivo (recogidos en el mismo recorrido del
 * comprimido), añade los que dependen del proyecto entero (archivos sensibles, CSRF, validación, permisos de Android,
 * control de acceso de las rutas), elimina duplicados y emite cada ficha y el resumen. Nada se ejecuta: lo que solo
 * una prueba dinámica puede comprobar se dice en el resumen.
 */
class SecurityStage(private val collector: AuditCollector) : Stage {
    override val id = "security"
    override val title = "Auditoría de seguridad"

    /**
     * Se ejecuta tras las relaciones y la auditoría de dependencias (orden de registro) pero no depende de que terminen
     * bien: sin rutas o sin dependencias, se dice.
     */
    override val dependsOn = setOf("dependencies")

    override suspend fun run(context: StageContext) {
        val c = collector
        val files: List<FileEntry> = context.attributes[IngestKeys.FILES].orEmpty()
        context.progress(0.2f, "Archivos sensibles")
        fileFindings(files, c)
        context.checkActive()
        context.progress(0.4f, "Configuración del proyecto")
        projectFindings(context, c)
        // Dependencias vulnerables y versiones sin soporte (Fase 7), de la auditoría de dependencias.
        context.attributes[com.funcodestudiocb.analyzer.engine.audit.deps.AuditKeys.DEPENDENCY_FINDINGS]?.forEach(c::addDirect)
        context.progress(0.6f, "Control de acceso de las rutas")
        val endpoints = context.attributes[RelationKeys.ENDPOINTS]
        if (endpoints == null) {
            context.coverage(CoverageReason.NOT_ANALYZED, "Control de acceso: no hay rutas del análisis de relaciones, así que no se comprobó la protección de las rutas.")
        } else {
            AccessControl.evaluate(endpoints, c.access, c.signals, c::add)
        }
        context.checkActive()

        context.progress(0.8f, "Ordenando hallazgos")
        val csrfApi = c.signals.bearerTokens && c.signals.sessionCookies == null
        val findings = dedupe(c.findings).onEach { f ->
            if (f.rule.id == "FC-CSRF-01" && csrfApi && f.downgraded == null) {
                f.severity = Context.lower(f.severity)
                f.downgraded = "La API usa tokens (Bearer/JWT) y no sesiones por cookies: desactivar CSRF puede ser correcto"
            }
        }.sortedWith(compareBy<Finding>({ it.severity.ordinal }, { it.certainty.ordinal }, { it.path ?: "" }, { it.line ?: 0 }, { it.rule.id }))

        var invalid = 0
        val emitted = ArrayList<Finding>()
        for (f in findings) {
            val record = f.toRecord()
            if (RecordValidator.validate(record).isNotEmpty()) {
                invalid++
                continue
            }
            context.emit(record)
            emitted += f
        }
        coverage(context, c, invalid)
        dependencyNotes = context.attributes[com.funcodestudiocb.analyzer.engine.audit.deps.AuditKeys.DEPENDENCY_NOTES]
        context.emit(summary(emitted, c))
    }

    // ------------------------------------------------------------------ archivos sensibles

    private fun fileFindings(files: List<FileEntry>, c: AuditCollector) {
        val counts = HashMap<String, Int>()
        val extraByRule = HashMap<String, Int>()
        fun add(rule: String, path: String, certainty: Certainty, detail: String, severity: Severity? = null, downgraded: String? = null) {
            val n = counts.merge(rule, 1, Int::plus)!!
            if (n > MAX_FILES_PER_RULE) {
                extraByRule.merge(rule, 1, Int::plus)
                return
            }
            c.add(Finding(SecurityRules.get(rule), path, null, certainty, severity ?: SecurityRules.get(rule).severity, detail, downgraded = downgraded))
        }
        for (f in files) {
            val path = f.path
            val name = path.substringAfterLast('/').lowercase()
            val ext = name.substringAfterLast('.', "")
            when {
                (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) && !ConfigChecks.isEnvTemplate(name) -> {
                    val (values, secrets) = c.envFiles[path] ?: continue
                    val dev = name.contains("development") || name.endsWith(".dev") || name.contains(".dev.") || name.contains("test")
                    add(
                        "FC-FILE-01", path, Certainty.CONFIRMED, "$values variables con valor.",
                        severity = when {
                            dev -> Severity.LOW
                            !secrets -> Severity.MEDIUM
                            else -> null
                        },
                        downgraded = when {
                            dev -> "Archivo de entorno de desarrollo o pruebas"
                            !secrets -> "No se reconocieron secretos en sus valores"
                            else -> null
                        },
                    )
                }
                ext in KEYSTORES || name in SSH_KEYS || (ext == "key" || ext == "pem") && f.kind != FileKind.TEXT -> {
                    if (path in c.privateKeyFiles) continue
                    val debug = name.startsWith("debug.")
                    add(
                        "FC-FILE-02", path, Certainty.CONFIRMED, "Archivo «$name».",
                        severity = if (debug) Severity.INFO else null,
                        downgraded = if (debug) "Almacén de depuración de Android (clave pública y conocida)" else null,
                    )
                }
                ext in DATABASES -> add("FC-FILE-03", path, Certainty.CONFIRMED, "Base de datos «$name».")
                path in c.sqlDumps -> add("FC-FILE-03", path, Certainty.PROBABLE, "Volcado SQL con datos (muchas sentencias INSERT o nombre de copia).")
                name.endsWith(".js.map") || name.endsWith(".css.map") || name.endsWith(".mjs.map") -> add("FC-FILE-04", path, Certainty.CONFIRMED, "Mapa de fuente «$name».")
                ext in BACKUPS || name.endsWith("~") -> add("FC-FILE-05", path, Certainty.CONFIRMED, "Archivo «$name».")
                name in SUPPRESSION_FILES -> c.add(
                    Finding(SecurityRules.get("FC-SUPPRESS-02"), path, null, Certainty.CONFIRMED, detail = "Archivo «$name».", downgraded = null),
                )
            }
        }
        extra = extraByRule
    }

    private var extra: Map<String, Int> = emptyMap()
    private var dependencyNotes: List<String>? = null

    // ------------------------------------------------------------------ señales del proyecto

    private fun projectFindings(context: StageContext, c: AuditCollector) {
        val s = c.signals
        // CSRF: sesiones por cookies en Express sin ninguna protección visible.
        s.sessionCookies?.let { p ->
            if (!s.csrfProtection) {
                c.add(
                    finding(
                        "FC-CSRF-02", p, Certainty.POSSIBLE,
                        "Se usan sesiones con cookies (express-session o cookie-session) y no se encontró csurf, csrf-csrf, lusca ni cookies SameSite.",
                        flow = listOf(FlowStep(p.line, "Sesión por cookies sin token CSRF ni SameSite visibles")),
                    ),
                )
            }
        }
        // Validación: datos de la petición sin ninguna biblioteca de validación (dependencias declaradas o uso).
        val declared = context.attributes[DependencyKeys.DECLARED].orEmpty().map { it.name.lowercase() }.toSet()
        val validationDeclared = declared.any { d -> VALIDATION_PACKAGES.any { d == it || d.endsWith("/$it") } }
        s.requestData?.let { (p, family) ->
            if (!s.validation && !validationDeclared) {
                c.add(
                    finding(
                        "FC-VALID-01", p, Certainty.POSSIBLE,
                        when (family) {
                            "js" -> "El servidor lee req.body o req.query y el proyecto no declara joi, zod, yup, express-validator, ajv ni otra biblioteca de validación."
                            "python" -> "El servidor lee datos de la petición y el proyecto no usa pydantic, marshmallow, WTForms ni otra biblioteca de validación."
                            else -> "El código lee \$_GET o \$_POST y no se ve validación (filter_input, Validator de Laravel…)."
                        },
                    ),
                )
            }
        }
        // Permisos peligrosos de Android y su uso en el código.
        for ((perm, p) in s.androidPermissions) {
            c.add(finding("FC-ANDROID-03", p, Certainty.CONFIRMED, "Permiso android.permission.$perm."))
            val words = PlatformChecks.PERMISSION_USAGE[perm] ?: continue
            if (words.none { it in s.permissionUsage } && "perm:$perm" !in s.permissionUsage) {
                c.add(finding("FC-ANDROID-04", p, Certainty.POSSIBLE, "No se encontró en el código ninguna API de $perm (${words.take(3).joinToString()}…). Puede usarlo una biblioteca."))
            }
        }
        // Supabase: tablas creadas sin activar la seguridad por filas.
        for ((table, p) in s.supabaseTables) {
            if (table in s.supabaseRls) continue
            c.add(
                finding(
                    "FC-SUPABASE-01", p, Certainty.POSSIBLE,
                    "La tabla «${table.take(80)}» se crea en las migraciones de Supabase y no se encontró «enable row level security» para ella.",
                    severity = Severity.MEDIUM,
                ),
            )
        }
    }

    private fun finding(rule: String, p: Place, certainty: Certainty, detail: String, flow: List<FlowStep> = emptyList(), severity: Severity? = null): Finding {
        val r = SecurityRules.get(rule)
        return Finding(r, p.path, p.line, certainty, severity ?: r.severity, detail, flow, p.snippet, p.snippetLine)
    }

    // ------------------------------------------------------------------ duplicados y resumen

    /** Un hallazgo por archivo, línea y CWE: el más grave; a igualdad, el de regla más concreta. Conserva el secreto. */
    private fun dedupe(all: List<Finding>): List<Finding> {
        val best = LinkedHashMap<String, Finding>()
        for (f in all) {
            val key = "${f.path}|${f.line}|${f.cwe ?: f.rule.id}"
            val old = best[key]
            if (old == null) {
                best[key] = f
                continue
            }
            val better = compareValuesBy(f, old, { it.severity.ordinal }, { it.certainty.ordinal }, { if (it.rule.id.startsWith("FC-SECRET")) 1 else 0 }) < 0
            val (winner, loser) = if (better) f to old else old to f
            if (winner.secret == null && loser.secret != null) winner.secret = loser.secret
            best[key] = winner
        }
        return best.values.toList()
    }

    private fun summary(findings: List<Finding>, c: AuditCollector): SecuritySummary {
        val dynamic = DYNAMIC_CHECKS.toMutableList()
        val dynamicAccess = findings.count { it.certainty == Certainty.DYNAMIC }
        if (dynamicAccess > 0) dynamic += "Protección real de $dynamicAccess rutas de administración que dependen de la configuración global de autenticación."
        val notEvaluated = (dependencyNotes ?: listOf("Dependencias vulnerables y versiones sin soporte: la auditoría de dependencias no se ejecutó."))
            .toMutableList().also { it += NOT_EVALUATED }
        if (c.withoutCodeRules.isNotEmpty()) {
            notEvaluated += "Inyecciones y flujo de datos en ${c.withoutCodeRules.entries.sortedByDescending { it.value }.joinToString { "${it.key} (${it.value})" }.take(300)}: " +
                "solo se buscan secretos, configuración y supresiones."
        }
        return SecuritySummary(
            findings = findings.size,
            bySeverity = Severity.entries.associateWith { s -> findings.count { it.severity == s } }.filterValues { it > 0 },
            byCertainty = Certainty.entries.associateWith { s -> findings.count { it.certainty == s } }.filterValues { it > 0 },
            byCategory = SecurityCategory.entries.associateWith { s -> findings.count { it.rule.category == s } }.filterValues { it > 0 },
            rules = SecurityRules.ALL.size,
            filesExamined = c.filesExamined,
            downgraded = findings.count { it.downgraded != null },
            suppressions = findings.count { it.rule.category == SecurityCategory.SUPPRESSION },
            dynamicChecks = dynamic.map { it.take(RecordValidator.MAX_EVIDENCE_DETAIL) }.take(RecordValidator.MAX_DYNAMIC_CHECKS),
            notEvaluated = notEvaluated.map { it.take(RecordValidator.MAX_EVIDENCE_DETAIL) }.take(RecordValidator.MAX_DYNAMIC_CHECKS),
        )
    }

    private fun coverage(context: StageContext, c: AuditCollector, invalid: Int) {
        for ((detector, files) in c.failures) {
            context.coverage(CoverageReason.NOT_ANALYZED, "El detector de $detector falló en $files archivos; esos archivos no se revisaron con él.")
        }
        if (c.limitReached) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se alcanzó el máximo de ${AuditCollector.MAX_FINDINGS} hallazgos; los siguientes no se registraron.")
        }
        if (c.cappedPerFile > 0) {
            context.coverage(
                CoverageReason.LIMIT_REACHED,
                "En ${c.cappedPerFile} casos una misma regla dio más de ${AuditCollector.MAX_PER_RULE_FILE} hallazgos en un archivo; solo se registran los primeros.",
            )
        }
        for ((rule, n) in extra) {
            context.coverage(CoverageReason.LIMIT_REACHED, "$rule: $n archivos más del mismo tipo no se listan uno a uno.")
        }
        if (invalid > 0) context.coverage(CoverageReason.NOT_ANALYZED, "$invalid hallazgos no superaron la validación de registros y se descartaron.")
    }

    private companion object {
        const val MAX_FILES_PER_RULE = 10
        val KEYSTORES = setOf("jks", "keystore", "p12", "pfx", "ppk", "bks", "p8")
        val SSH_KEYS = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
        val DATABASES = setOf("sqlite", "sqlite3", "db", "mdb", "accdb", "realm", "dump")
        val BACKUPS = setOf("log", "bak", "old", "backup", "orig")
        val SUPPRESSION_FILES = setOf(
            ".semgrepignore", ".gitleaksignore", ".trivyignore", ".snyk", ".bandit", ".secrets.baseline", ".gitleaks.toml", ".checkov.yml",
            ".checkov.yaml", ".tfsec.yml", "tfsec.yml", ".grype.yaml", ".nsprc",
        )
        val VALIDATION_PACKAGES = setOf(
            "joi", "zod", "yup", "express-validator", "class-validator", "ajv", "celebrate", "superstruct", "valibot", "pydantic", "marshmallow",
            "cerberus", "wtforms", "flask-wtf", "jsonschema", "voluptuous", "@hapi/joi", "vine", "@vinejs/vine", "io-ts", "runtypes",
            "@sinclair/typebox", "fastify", "@nestjs/common", "django", "djangorestframework", "laravel/framework", "symfony/validator",
        )
        val DYNAMIC_CHECKS = listOf(
            "Que las rutas protegidas rechacen de verdad las peticiones sin sesión o de otro usuario (control de acceso real).",
            "Validez de las claves y tokens encontrados: no se prueban contra los servicios.",
            "Explotabilidad real de las inyecciones marcadas como probables o posibles.",
            "Cabeceras de seguridad de las respuestas (CSP, HSTS, X-Frame-Options) del servidor desplegado.",
            "Configuración del servidor, el proxy, la nube y las variables de entorno en producción.",
            "Lógica de negocio, límites de peticiones y protección contra fuerza bruta.",
            "Comportamiento de la app en el dispositivo (almacenamiento, intents recibidos, WebView con contenido real).",
        )
        val NOT_EVALUATED = listOf(
            "Librerías desactualizadas sin fecha de fin de soporte publicada: solo se evalúan los entornos y frameworks de endoflife.date.",
            "Binarios y archivos compilados: no se analizan por dentro.",
        )
    }
}
