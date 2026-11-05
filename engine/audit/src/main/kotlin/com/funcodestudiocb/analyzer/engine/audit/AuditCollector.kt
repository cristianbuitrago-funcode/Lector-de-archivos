package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.relations.framework.Src
import com.funcodestudiocb.analyzer.engine.security.DetectedSecret
import com.funcodestudiocb.analyzer.engine.security.SecurityRules

/** Un archivo durante la auditoría: su texto redactado, el lector de código y dónde dejar los hallazgos. */
internal class FileAudit(val file: RedactedText, val sink: AuditCollector) {
    val src = Src(file)
    val path: String = file.path
    val language: String? = file.language?.id
    val family: String? = Taint.family(language)
    val text: String get() = src.text

    /** El texto en minúsculas, para filtros rápidos (se calcula una vez). */
    val lowerText: String by lazy { src.text.lowercase() }
    val name: String = file.info.lower
    val ext: String = file.info.ext
    val lowerDirs: List<String> = file.info.lowerDirs

    /** El archivo es código (no configuración, marcado ni texto). */
    val code: Boolean get() = family != null || language in OTHER_CODE

    fun add(
        ruleId: String,
        line: Int?,
        certainty: Certainty,
        detail: String? = null,
        flow: List<FlowStep> = emptyList(),
        mask: List<IntRange> = emptyList(),
        secret: DetectedSecret? = null,
        severity: Severity? = null,
        downgraded: String? = null,
        snippet: Boolean = true,
    ) {
        val rule = SecurityRules.get(ruleId)
        val snip = if (snippet && line != null && line in 1..src.lineCount) Snippets.around(src, line, mask) else null
        sink.add(
            Finding(
                rule, path, line?.takeIf { it >= 1 }, certainty, severity ?: rule.severity, detail?.take(600), flow, snip?.first, snip?.second,
                language, secret, downgraded,
            ),
        )
    }

    fun addAt(ruleId: String, offset: Int, certainty: Certainty, detail: String? = null, flow: List<FlowStep> = emptyList(), mask: List<IntRange> = emptyList(), severity: Severity? = null, downgraded: String? = null) =
        add(ruleId, src.line(offset), certainty, detail, flow, mask, severity = severity, downgraded = downgraded)

    /** Cada línea con su número (desde 1). */
    inline fun lines(action: (Int, String) -> Unit) {
        for (n in 1..src.lineCount) action(n, src.lineText(n))
    }

    /** Alguno de los textos aparece en las líneas cercanas ([before] antes y [after] después). */
    fun near(line: Int, needles: List<String>, before: Int = 3, after: Int = 3): String? {
        for (n in (line - before).coerceAtLeast(1)..(line + after).coerceAtMost(src.lineCount)) {
            val t = src.lineText(n)
            needles.firstOrNull { t.contains(it) }?.let { return it }
        }
        return null
    }

    companion object {
        private val OTHER_CODE = setOf("dart", "swift", "objc", "ruby", "rust", "c", "cpp", "scala", "groovy", "lua", "gdscript", "perl", "elixir", "vb", "fsharp")
    }
}

/** Lugar del código guardado durante el recorrido, con su fragmento (el texto no se conserva). */
internal class Place(val path: String, val line: Int, val snippet: String?, val snippetLine: Int?)

internal fun FileAudit.place(offset: Int): Place {
    val line = src.line(offset)
    val (snippet, first) = Snippets.around(src, line)
    return Place(path, line, snippet, first)
}

/** Señales del proyecto entero que deciden hallazgos al final (CSRF, validación, permisos de Android…). */
internal class ProjectSignals {
    /** Palabras de uso de cada permiso peligroso de Android encontradas en el código. */
    val permissionUsage = HashSet<String>()
    var sessionCookies: Place? = null
    var csrfProtection = false
    var bearerTokens = false
    var requestData: Pair<Place, String>? = null
    var validation = false

    /** Configuración global de autenticación (Spring Security, middleware global…) y si menciona /admin. */
    var globalAuth = false
    var globalAdminRule = false
    val supabaseTables = LinkedHashMap<String, Place>()
    val supabaseRls = HashSet<String>()
    val androidPermissions = ArrayList<Pair<String, Place>>()
}

/**
 * Recoge los hallazgos de cada archivo durante el recorrido del comprimido (sobre el texto ya redactado) y las
 * señales que solo se pueden decidir al final. No ejecuta nada del proyecto.
 */
class AuditCollector : RedactedTextListener {
    internal val findings = ArrayList<Finding>()
    internal val signals = ProjectSignals()
    internal val access = HashMap<String, FileMarkers>()

    /** Archivos de texto examinados y archivos de código en lenguajes sin reglas de código (solo secretos y configuración). */
    var filesExamined = 0
        private set
    internal val withoutCodeRules = HashMap<String, Int>()

    /** Detectores que fallaron y en cuántos archivos (el análisis sigue). */
    internal val failures = HashMap<String, Int>()
    private val perRuleFile = HashMap<String, Int>()
    internal var cappedPerFile = 0
    internal var limitReached = false

    /** Archivos .env con valores y volcados SQL, que se deciden con la lista de archivos. */
    internal val envFiles = HashMap<String, Pair<Int, Boolean>>()
    internal val sqlDumps = HashSet<String>()
    internal val privateKeyFiles = HashSet<String>()

    override fun onText(context: StageContext, file: RedactedText) {
        filesExamined++
        val audit = FileAudit(file, this)
        guard("secretos") { SecretChecks.check(audit) }
        guard("archivos de configuración") { ConfigChecks.check(audit) }
        if (file.state == ContentState.NOT_STORED_GENERATED) return
        if (audit.family != null) {
            guard("inyecciones") { CodeChecks.check(audit) }
            guard("web y sesiones") { WebChecks.check(audit) }
            guard("control de acceso") { AccessControl.collect(audit)?.let { access[audit.path] = it } }
        } else if (audit.code) {
            withoutCodeRules.merge(file.language!!.name, 1, Int::plus)
        }
        if (audit.code || audit.language in setOf("html", "xml", "vue", "svelte")) {
            guard("plataformas") { PlatformChecks.check(audit) }
        }
        guard("supresiones") { SuppressionChecks.check(audit) }
    }

    private inline fun guard(detector: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failures.merge(detector, 1, Int::plus)
        } catch (e: StackOverflowError) {
            failures.merge(detector, 1, Int::plus)
        }
    }

    /** Hallazgo que no sale de un patrón de código (dependencias): sin el tope por regla y archivo. */
    internal fun addDirect(f: Finding) {
        if (findings.size >= MAX_FINDINGS) {
            limitReached = true
            return
        }
        findings += f
    }

    internal fun add(f: Finding) {
        if (findings.size >= MAX_FINDINGS) {
            limitReached = true
            return
        }
        val key = f.rule.id + "|" + f.path
        val n = perRuleFile.merge(key, 1, Int::plus)!!
        if (n > MAX_PER_RULE_FILE) {
            if (n == MAX_PER_RULE_FILE + 1) cappedPerFile++
            return
        }
        if (f.downgraded == null) {
            Context.location(f.path)?.let { reason ->
                f.downgraded = reason
                f.severity = when (f.rule.category) {
                    SecurityCategory.EXPOSED_SECRET, SecurityCategory.HARDCODED_PASSWORD, SecurityCategory.SENSITIVE_DATA_EXPOSURE -> Context.lower(f.severity)
                    else -> if (f.severity.ordinal < Severity.LOW.ordinal) Severity.LOW else f.severity
                }
            }
        }
        findings += f
    }

    internal companion object {
        const val MAX_FINDINGS = 5_000
        const val MAX_PER_RULE_FILE = 25
    }
}
