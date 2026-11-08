package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Severity

/**
 * Auditores de configuración (§8.2): manifiesto y seguridad de red de Android, Info.plist, ajustes de Django,
 * Spring, Laravel, ASP.NET y PHP, Docker, flujos de GitHub Actions, reglas de Firebase, seguridad por filas de
 * Supabase, variables públicas del frontend y plantillas que desactivan el escape.
 */
internal object ConfigChecks {

    fun check(a: FileAudit) {
        val name = a.name
        val path = a.path.lowercase()
        when {
            name == "androidmanifest.xml" -> androidManifest(a)
            a.ext == "xml" && a.text.contains("<network-security-config") -> networkConfig(a)
            name == "info.plist" -> infoPlist(a)
            isEnv(name) -> env(a)
            name == "settings.py" || name.startsWith("settings_") && a.ext == "py" || "settings" in a.lowerDirs && a.ext == "py" -> djangoSettings(a)
            name.startsWith("application") && (a.ext == "properties" || a.ext == "yml" || a.ext == "yaml") -> spring(a)
            name == "web.config" -> webConfig(a)
            name == "php.ini" || name == ".user.ini" -> phpIni(a)
            name.startsWith("docker-compose") || name.startsWith("compose.") && (a.ext == "yml" || a.ext == "yaml") -> compose(a)
            name == "dockerfile" || name.startsWith("dockerfile.") || name.endsWith(".dockerfile") -> dockerfile(a)
            path.contains(".github/workflows/") && (a.ext == "yml" || a.ext == "yaml") -> workflow(a)
            name == "firestore.rules" || name == "storage.rules" || a.ext == "rules" && a.text.contains("allow ") -> firebaseRules(a)
            name == "database.rules.json" -> realtimeRules(a)
            a.ext == "sql" -> sql(a)
            a.ext == "json" && a.text.contains("service_account") -> {} // credenciales: SecretChecks
        }
        templates(a)
        if (a.family == "js") publicEnvInCode(a)
    }

    private fun isEnv(name: String) = name == ".env" || name.startsWith(".env.") || name.endsWith(".env")

    private val TEMPLATE_SUFFIXES = listOf("example", "sample", "template", "dist", "defaults", "tpl", "schema")

    fun isEnvTemplate(name: String) = TEMPLATE_SUFFIXES.any { name.endsWith(".$it") || name.contains(".$it.") || name.contains("-$it") }

    // ------------------------------------------------------------------ Android

    private val DANGEROUS_PERMISSIONS = setOf(
        "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
        "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "PROCESS_OUTGOING_CALLS", "READ_CALENDAR", "WRITE_CALENDAR", "BODY_SENSORS",
        "ACTIVITY_RECOGNITION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES", "BLUETOOTH_SCAN",
        "NEARBY_WIFI_DEVICES", "USE_SIP", "ANSWER_PHONE_CALLS", "BIND_ACCESSIBILITY_SERVICE",
    )

    private fun androidManifest(a: FileAudit) {
        val text = a.text
        attribute(a, "android:allowBackup=\"true\"") { a.addAt("FC-ANDROID-01", it, Certainty.CONFIRMED) }
        attribute(a, "android:debuggable=\"true\"") {
            a.addAt("FC-DEBUG-01", it, Certainty.CONFIRMED, "El manifiesto declara android:debuggable=\"true\".", severity = Severity.HIGH)
        }
        attribute(a, "android:usesCleartextTraffic=\"true\"") {
            a.addAt("FC-TLS-02", it, Certainty.CONFIRMED, "El manifiesto permite tráfico HTTP sin cifrar (usesCleartextTraffic).")
        }
        // Permisos declarados: se deciden al final con el uso en el código.
        var i = text.indexOf("<uses-permission")
        while (i >= 0) {
            val end = text.indexOf('>', i)
            if (end < 0) break
            val tag = text.substring(i, end)
            val perm = tag.substringAfter("android:name=\"", "").substringBefore('"').substringAfterLast('.')
            if (perm in DANGEROUS_PERMISSIONS) a.sink.signals.androidPermissions += perm to a.place(i)
            i = text.indexOf("<uses-permission", end)
        }
        for (component in listOf("activity", "activity-alias", "service", "receiver", "provider")) exported(a, component)
    }

    private inline fun attribute(a: FileAudit, needle: String, action: (Int) -> Unit) {
        val i = a.text.indexOf(needle)
        if (i >= 0 && a.src.isCode(i)) action(i)
    }

    /** Componente con `android:exported="true"` y sin `android:permission`. */
    private fun exported(a: FileAudit, component: String) {
        val text = a.text
        val open = "<$component"
        var i = text.indexOf(open)
        while (i >= 0) {
            val next = text.getOrNull(i + open.length)
            if (next != null && !next.isWhitespace()) {
                i = text.indexOf(open, i + open.length)
                continue
            }
            val tagEnd = text.indexOf('>', i).takeIf { it > 0 } ?: break
            val tag = text.substring(i, tagEnd)
            val selfClosing = tag.endsWith("/")
            val close = if (selfClosing) tagEnd else text.indexOf("</$component>", tagEnd).let { if (it < 0) tagEnd else it }
            val body = text.substring(tagEnd, close)
            if (a.src.isCode(i) && tag.contains("android:exported=\"true\"") && !tag.contains("android:permission=") &&
                !(component == "provider" && tag.contains("android:readPermission=") && tag.contains("android:writePermission="))
            ) {
                val cls = tag.substringAfter("android:name=\"", "").substringBefore('"').take(120)
                val launcher = body.contains("android.intent.category.LAUNCHER") || body.contains("android.intent.category.LEANBACK_LAUNCHER")
                if (!launcher) {
                    val systemOrLink = body.contains("<intent-filter") &&
                        (body.contains("android.intent.category.BROWSABLE") || body.contains("android:name=\"android.intent.action.") && !body.contains("android.intent.action.SEND"))
                    a.addAt(
                        "FC-ANDROID-02", i, Certainty.CONFIRMED, "Componente «$cls» ($component) exportado sin permiso.",
                        severity = if (systemOrLink) Severity.LOW else null,
                        downgraded = if (systemOrLink) "Atiende enlaces o acciones del sistema: puede que tenga que estar exportado" else null,
                    )
                }
            }
            i = text.indexOf(open, tagEnd)
        }
    }

    private fun networkConfig(a: FileAudit) {
        val text = a.text
        var i = text.indexOf("cleartextTrafficPermitted=\"true\"")
        while (i >= 0) {
            if (a.src.isCode(i)) {
                val inDomain = text.lastIndexOf("<domain-config", i) > text.lastIndexOf("</domain-config>", i)
                a.addAt(
                    "FC-TLS-02", i, Certainty.CONFIRMED,
                    if (inDomain) "La configuración de red permite HTTP sin cifrar para algunos dominios." else "La configuración de red permite HTTP sin cifrar para todos los dominios.",
                    severity = if (inDomain) Severity.LOW else null,
                )
            }
            i = text.indexOf("cleartextTrafficPermitted=\"true\"", i + 10)
        }
        if (!text.contains("<debug-overrides")) {
            val u = text.indexOf("src=\"user\"")
            if (u >= 0 && a.src.isCode(u)) {
                a.addAt("FC-TLS-01", u, Certainty.CONFIRMED, "La app confía en certificados instalados por el usuario.", severity = Severity.MEDIUM)
            }
        }
    }

    private fun infoPlist(a: FileAudit) {
        val text = a.text
        for (key in listOf("NSAllowsArbitraryLoads", "NSExceptionAllowsInsecureHTTPLoads")) {
            val i = text.indexOf("<key>$key</key>")
            if (i < 0) continue
            val after = text.substring(i, minOf(text.length, i + key.length + 60))
            if (after.contains("<true/>")) {
                a.addAt("FC-TLS-02", i, Certainty.CONFIRMED, "Info.plist activa $key.", severity = if (key == "NSAllowsArbitraryLoads") null else Severity.LOW)
            }
        }
    }

    // ------------------------------------------------------------------ archivos de entorno

    private val PUBLIC_PREFIXES = listOf("NEXT_PUBLIC_", "VITE_", "REACT_APP_", "EXPO_PUBLIC_", "NUXT_PUBLIC_", "GATSBY_", "PUBLIC_", "VUE_APP_")
    private val PRIVATE_WORDS = listOf("SECRET", "PASSWORD", "PASSWD", "PRIVATE", "SERVICE_ROLE", "SERVICE_KEY", "ADMIN_KEY", "DB_PASS")

    private fun publicSecretName(key: String): Boolean {
        if (PUBLIC_PREFIXES.none { key.startsWith(it) }) return false
        val rest = key.uppercase()
        return PRIVATE_WORDS.any { rest.contains(it) }
    }

    private fun env(a: FileAudit) {
        val template = isEnvTemplate(a.name)
        var values = 0
        a.lines { n, line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@lines
            val body = t.removePrefix("export ").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) return@lines
            val key = body.substring(0, eq).trim()
            val value = body.substring(eq + 1).trim().trim('"', '\'')
            if (value.isNotEmpty()) values++
            if (publicSecretName(key)) {
                a.add("FC-PUBENV-01", n, Certainty.PROBABLE, "La variable «${key.take(80)}» es pública (se incluye en el código del navegador) y su nombre indica un secreto.")
            }
            val upper = key.uppercase()
            val v = value.lowercase()
            if ((upper == "APP_DEBUG" || upper == "DEBUG" || upper == "FLASK_DEBUG" || upper == "DJANGO_DEBUG") && (v == "true" || v == "1" || v == "on")) {
                val dev = Context.devConfig(a.path) ?: if (template) "plantilla de ejemplo" else null
                a.add("FC-DEBUG-01", n, Certainty.CONFIRMED, "$key=$value en ${a.name}.", severity = dev?.let { Context.lower(Severity.MEDIUM) }, downgraded = dev)
            }
        }
        if (!template && values > 0) {
            a.sink.envFiles[a.path] = values to a.file.secrets.any { !it.placeholder }
        }
    }

    private fun publicEnvInCode(a: FileAudit) {
        val text = a.text
        for (prefix in listOf("process.env.", "import.meta.env.")) {
            if (!text.contains(prefix)) continue
            a.src.each(prefix) { at ->
                val key = a.src.wordAt(at + prefix.length) ?: return@each
                if (publicSecretName(key)) {
                    a.addAt("FC-PUBENV-01", at, Certainty.PROBABLE, "El código del frontend lee «${key.take(80)}»: una variable pública cuyo nombre indica un secreto.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ marcos del servidor

    private fun djangoSettings(a: FileAudit) {
        val dev = Context.devConfig(a.path)
        a.lines { n, line ->
            val compact = line.filterNot { it == ' ' || it == '\t' }
            if (compact.startsWith("DEBUG=True")) {
                a.add("FC-DEBUG-01", n, Certainty.CONFIRMED, "DEBUG = True en los ajustes de Django.", severity = dev?.let { Severity.LOW }, downgraded = dev)
            }
            if (compact.startsWith("ALLOWED_HOSTS=['*']") || compact.startsWith("ALLOWED_HOSTS=[\"*\"]")) {
                a.add("FC-CONF-01", n, Certainty.CONFIRMED, "ALLOWED_HOSTS acepta cualquier nombre de servidor.", severity = Severity.LOW, downgraded = dev)
            }
        }
    }

    private fun spring(a: FileAudit) {
        val text = a.text
        val dev = Context.devConfig(a.path)
        a.lines { n, line ->
            val compact = line.filterNot { it == ' ' || it == '\t' }.lowercase()
            if (compact.startsWith("#")) return@lines
            val actuator = compact.startsWith("management.endpoints.web.exposure.include=") && compact.contains("*") ||
                compact.startsWith("include:") && compact.contains("*") && text.contains("exposure")
            if (actuator) {
                a.add("FC-CONF-01", n, Certainty.CONFIRMED, "Se exponen todos los endpoints de Actuator.", severity = dev?.let { Severity.LOW }, downgraded = dev)
            }
            if (compact.startsWith("spring.h2.console.enabled=true") || compact.startsWith("enabled:true") && n > 1 && a.src.lineText(n - 1).trim() == "console:") {
                a.add("FC-CONF-01", n, Certainty.CONFIRMED, "La consola web de H2 está activada.", severity = dev?.let { Severity.LOW }, downgraded = dev)
            }
            if (compact.startsWith("server.error.include-stacktrace=always") || compact.startsWith("include-stacktrace:always")) {
                a.add("FC-DEBUG-01", n, Certainty.CONFIRMED, "Las páginas de error incluyen la traza de la excepción.", severity = dev?.let { Severity.LOW }, downgraded = dev)
            }
        }
    }

    private fun webConfig(a: FileAudit) {
        val text = a.text
        for ((needle, detail) in listOf(
            "customErrors mode=\"Off\"" to "Los errores detallados se muestran a todos (customErrors mode=\"Off\").",
            "compilation debug=\"true\"" to "La aplicación se compila en modo de depuración.",
        )) {
            val i = text.indexOf(needle)
            if (i >= 0 && a.src.isCode(i)) a.addAt("FC-DEBUG-01", i, Certainty.CONFIRMED, detail, downgraded = Context.devConfig(a.path))
        }
    }

    private fun phpIni(a: FileAudit) {
        a.lines { n, line ->
            val c = line.filterNot { it == ' ' || it == '\t' }.lowercase()
            if (c.startsWith("display_errors=on") || c.startsWith("display_errors=1")) a.add("FC-DEBUG-01", n, Certainty.CONFIRMED, "display_errors está activo.")
        }
    }

    // ------------------------------------------------------------------ contenedores

    private fun compose(a: FileAudit) {
        a.lines { n, line ->
            val c = line.filterNot { it == ' ' || it == '\t' }
            if (c.startsWith("#")) return@lines
            if (c == "privileged:true" || c == "-privileged:true") a.add("FC-DOCKER-01", n, Certainty.CONFIRMED, "Un servicio se ejecuta con privileged: true.")
            if (c.contains("/var/run/docker.sock")) {
                a.add("FC-DOCKER-01", n, Certainty.CONFIRMED, "Un servicio monta el socket de Docker: puede controlar el motor de contenedores del anfitrión.")
            }
        }
    }

    private fun dockerfile(a: FileAudit) {
        var lastFrom = 0
        var user: Pair<Int, String>? = null
        a.lines { n, line ->
            val t = line.trim()
            val upper = t.uppercase()
            if (upper.startsWith("FROM ")) {
                lastFrom = n
                user = null
            } else if (upper.startsWith("USER ")) {
                user = n to t.substring(5).trim()
            }
        }
        if (lastFrom == 0) return
        val u = user
        when {
            u == null -> {
                val base = a.src.lineText(lastFrom).trim().substring(5).trim().lowercase()
                if (base.startsWith("scratch") || base.contains("distroless") && base.contains("nonroot")) return
                a.add(
                    "FC-DOCKER-02", lastFrom, Certainty.PROBABLE, "La última etapa (${base.substringBefore(' ').take(80)}) no cambia de usuario con USER.",
                    flow = emptyList(),
                )
            }
            u.second == "root" || u.second == "0" || u.second.startsWith("root:") || u.second.startsWith("0:") ->
                a.add("FC-DOCKER-02", u.first, Certainty.CONFIRMED, "La imagen termina ejecutándose como root (USER ${u.second.take(40)}).")
        }
    }

    // ------------------------------------------------------------------ GitHub Actions

    private val UNTRUSTED_CONTEXT = listOf(
        "github.event.issue.title", "github.event.issue.body", "github.event.pull_request.title", "github.event.pull_request.body",
        "github.event.comment.body", "github.event.review.body", "github.event.review_comment.body", "github.event.pull_request.head.ref",
        "github.event.pull_request.head.label", "github.event.pull_request.head.repo.default_branch", "github.head_ref",
        "github.event.head_commit.message", "github.event.head_commit.author.email", "github.event.head_commit.author.name",
        "github.event.commits", "github.event.discussion.title", "github.event.discussion.body", "github.event.pages",
    )

    private fun workflow(a: FileAudit) {
        val text = a.text
        val target = text.contains("pull_request_target") || text.contains("workflow_run")
        a.lines { n, line ->
            val t = line.trim()
            if (t.startsWith("#")) return@lines
            if (target && t.contains("ref:") && (t.contains("github.event.pull_request.head.sha") || t.contains("github.event.pull_request.head.ref") ||
                    t.contains("github.head_ref") || t.contains("refs/pull/") || t.contains("github.event.workflow_run.head"))
            ) {
                a.add("FC-CI-01", n, Certainty.CONFIRMED, "El flujo se dispara con pull_request_target o workflow_run y hace checkout del código de la pull request.")
            }
            if (!t.contains("\${{")) return@lines
            val ctx = UNTRUSTED_CONTEXT.firstOrNull { t.contains(it) } ?: return@lines
            if (inRunBlock(a, n)) {
                a.add("FC-CI-02", n, Certainty.CONFIRMED, "Se interpola «$ctx» dentro de un comando run/script.")
            }
        }
    }

    /** La línea es un `run:` o `script:` o está dentro de su bloque (más sangrada que él). */
    private fun inRunBlock(a: FileAudit, n: Int): Boolean {
        val line = a.src.lineText(n)
        val indent = line.indexOfFirst { !it.isWhitespace() }
        val t = line.trim().removePrefix("- ")
        if (t.startsWith("run:") || t.startsWith("script:")) return true
        for (k in n - 1 downTo (n - 200).coerceAtLeast(1)) {
            val prev = a.src.lineText(k)
            if (prev.isBlank()) continue
            val pi = prev.indexOfFirst { !it.isWhitespace() }
            if (pi < indent) {
                val pt = prev.trim().removePrefix("- ")
                return pt.startsWith("run:") || pt.startsWith("script:")
            }
        }
        return false
    }

    // ------------------------------------------------------------------ Firebase y Supabase

    private fun firebaseRules(a: FileAudit) {
        a.lines { n, line ->
            val c = line.filterNot { it == ' ' || it == '\t' }
            if (!c.startsWith("allow")) return@lines
            val open = c.endsWith(":iftrue;") || c.endsWith(";") && !c.contains(":") || c.contains(":iftrue")
            if (open && (c.contains("write") || c.contains("read") || c.contains("create") || c.contains("update") || c.contains("delete"))) {
                a.add("FC-FIREBASE-01", n, Certainty.CONFIRMED, "La regla «${line.trim().take(80)}» no tiene condición.")
            } else if (c.contains("request.time<timestamp.date(")) {
                a.add("FC-FIREBASE-01", n, Certainty.CONFIRMED, "Reglas del modo de prueba: abiertas a todos hasta una fecha.", severity = Severity.HIGH)
            }
        }
    }

    private fun realtimeRules(a: FileAudit) {
        a.lines { n, line ->
            val c = line.filterNot { it == ' ' || it == '\t' }
            if (c.startsWith("\".read\":true") || c.startsWith("\".write\":true") || c.startsWith("\".read\":\"true\"") || c.startsWith("\".write\":\"true\"")) {
                a.add("FC-FIREBASE-01", n, Certainty.CONFIRMED, "La regla ${c.substringBefore(':')} de Realtime Database es «true».")
            }
        }
    }

    private fun sql(a: FileAudit) {
        val lower = a.text.lowercase()
        var inserts = 0
        var i = lower.indexOf("insert into")
        while (i >= 0 && inserts < 1_000) {
            inserts++
            i = lower.indexOf("insert into", i + 11)
        }
        val name = a.name
        if (inserts >= 20 || name.contains("dump") || name.contains("backup")) {
            if ("migrations" !in a.lowerDirs) a.sink.sqlDumps += a.path
        }
        var d = lower.indexOf("disable row level security")
        while (d >= 0) {
            a.addAt("FC-SUPABASE-01", d, Certainty.CONFIRMED, "Se desactiva la seguridad por filas de una tabla.")
            d = lower.indexOf("disable row level security", d + 10)
        }
        if (!a.path.lowercase().contains("supabase")) return
        val signals = a.sink.signals
        var c = lower.indexOf("create table")
        while (c >= 0) {
            var j = c + 12
            val rest = lower.substring(j, minOf(lower.length, j + 200)).trimStart()
            val table = rest.removePrefix("if not exists").trim().takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' || it == '"' }.replace("\"", "").removePrefix("public.")
            if (table.isNotEmpty() && table !in signals.supabaseTables) signals.supabaseTables[table] = a.place(c)
            j = c + 12
            c = lower.indexOf("create table", j)
        }
        var e = lower.indexOf("enable row level security")
        while (e >= 0) {
            val before = lower.substring(maxOf(0, e - 200), e)
            val table = before.substringAfterLast("alter table").trim().removePrefix("if exists").trim().removePrefix("only").trim()
                .takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' || it == '"' }.replace("\"", "").removePrefix("public.")
            if (table.isNotEmpty()) signals.supabaseRls += table
            e = lower.indexOf("enable row level security", e + 10)
        }
    }

    // ------------------------------------------------------------------ plantillas

    private class Unescaped(val open: String, val close: String, val label: String)

    private fun unescapedFor(a: FileAudit): List<Unescaped> {
        val n = a.name
        return when {
            n.endsWith(".blade.php") -> listOf(Unescaped("{!!", "!!}", "{!! !!}"))
            a.ext in setOf("jinja", "jinja2", "j2", "twig", "njk", "djhtml") || a.ext == "html" && (a.text.contains("{%") || a.text.contains("{{")) ->
                listOf(Unescaped("|safe", "}}", "|safe"), Unescaped("| safe", "}}", "|safe"), Unescaped("|raw", "}}", "|raw"), Unescaped("| raw", "}}", "|raw"))
            a.ext == "ejs" -> listOf(Unescaped("<%-", "%>", "<%- %>"))
            a.ext in setOf("hbs", "handlebars", "mustache") -> listOf(Unescaped("{{{", "}}}", "{{{ }}}"))
            a.ext == "cshtml" || a.ext == "razor" -> listOf(Unescaped("Html.Raw(", ")", "Html.Raw"))
            a.ext == "erb" -> listOf(Unescaped(".html_safe", "%>", "html_safe"), Unescaped("raw(", ")", "raw"))
            else -> emptyList()
        } + if (a.ext == "html" && a.text.contains("th:utext")) listOf(Unescaped("th:utext=\"", "\"", "th:utext")) else emptyList()
    }

    private val SAFE_TEMPLATE_VALUES = listOf("csrf_field", "method_field", "->links(", "csrf_token", "@json", "json_encode", "render()", "Vite::", "vite(")

    private val TEMPLATE_EXTENSIONS = setOf("html", "htm", "jinja", "jinja2", "j2", "twig", "njk", "djhtml", "ejs", "hbs", "handlebars", "mustache", "cshtml", "razor", "erb")

    private fun templates(a: FileAudit) {
        if (a.ext !in TEMPLATE_EXTENSIONS && !a.name.endsWith(".blade.php")) return
        val text = a.text
        if (text.contains("{% autoescape off %}") || text.contains("{% autoescape false %}") || text.contains("{%autoescape false%}")) {
            val i = maxOf(text.indexOf("{% autoescape off %}"), text.indexOf("{% autoescape false %}"), text.indexOf("{%autoescape false%}"))
            a.addAt("FC-XSS-03", i, Certainty.POSSIBLE, "Se desactiva el escape automático en un bloque de la plantilla.")
        }
        for (u in unescapedFor(a)) {
            var i = text.indexOf(u.open)
            var count = 0
            while (i >= 0 && count < 200) {
                count++
                val end = text.indexOf(u.close, i + u.open.length).let { if (it < 0) minOf(text.length, i + 120) else it }
                val inner = if (u.open.startsWith("|")) {
                    text.substring(maxOf(0, text.lastIndexOf("{{", i)), i)
                } else {
                    text.substring(i + u.open.length, minOf(end, i + u.open.length + 200))
                }
                val value = inner.trim().removePrefix("{{").trim()
                val literal = value.startsWith("'") || value.startsWith("\"")
                if (!literal && value.isNotEmpty() && SAFE_TEMPLATE_VALUES.none { value.contains(it) }) {
                    a.addAt("FC-XSS-03", i, Certainty.POSSIBLE, "Se muestra «${value.take(60)}» sin escapar (${u.label}).")
                }
                i = text.indexOf(u.open, i + u.open.length)
            }
        }
    }
}
