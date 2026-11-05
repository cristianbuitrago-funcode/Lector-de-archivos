package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.security.SecurityRules
import java.util.BitSet

/** Protecciones visibles en un archivo: líneas cubiertas por anotaciones, middleware, grupos o comprobaciones. */
internal class FileMarkers(
    /** Líneas cubiertas por una protección (anotación, middleware en la misma línea, grupo, clase protegida). */
    val protectedLines: BitSet,
    /** Líneas marcadas como públicas a propósito (`[AllowAnonymous]`, `@PermitAll`, `AllowAny`). */
    val publicLines: BitSet,
    /** Líneas con comprobaciones dentro del código (`if (!req.user)`, `abort(403)`). */
    val bodyChecks: IntArray,
    /** A partir de esta línea todo está protegido (`router.use(auth)`); [Int.MAX_VALUE] si no hay. */
    val globalFrom: Int,
    /** Todo el archivo está protegido (middleware en el constructor del controlador, `before_request`). */
    val fileLevel: Boolean,
    /** Funciones y clases protegidas por sus anotaciones (para las rutas que se declaran en otro archivo). */
    val protectedNames: Set<String>,
)

/**
 * Control de acceso (§8.2): busca protecciones visibles junto a cada ruta del backend (anotaciones, middleware,
 * grupos, decoradores) y señala las rutas de administración sin ninguna y las que rompen el patrón de su archivo.
 * La protección puede estar en otra capa (pasarela, configuración global) que el análisis no puede asociar: por
 * eso la certeza es como mucho "posible" y se dice.
 */
internal object AccessControl {

    private val AUTH_WORDS = listOf(
        "login_required", "loginrequired", "guard", "protect", "verifytoken", "verify_token", "verifyjwt", "jwt_required", "jwtrequired",
        "permission", "requirerole", "require_role", "hasrole", "has_role", "hasanyrole", "roles_required", "rolesallowed", "secured", "isadmin",
        "is_admin", "admin_required", "adminonly", "admin_only", "current_user", "currentuser", "requireuser", "require_user", "ensureloggedin",
        "ensure_logged_in", "signedin", "signed_in", "checktoken", "check_token", "isloggedin", "is_logged_in", "passport.", "sanctum",
        "session_required", "requiresession", "withauth", "requirelogin", "require_login", "jwt(", "jwt.", "bearer", "apikeyauth",
    )
    /** Raíces de todas las palabras de protección: sin ninguna, la línea (o el archivo) no tiene nada. */
    private val ROOTS = listOf(
        "auth", "login", "guard", "protect", "verif", "jwt", "permis", "role", "secur", "admin", "user", "session", "passport", "sanctum",
        "bearer", "401", "403", "forbid", "principal", "anonym", "allowany", "permitall", "public", "skip", "token", "signed", "middleware",
        "before_request", "beforeaction", "logged",
    )
    private val PUBLIC_WORDS = listOf("[allowanonymous]", "@permitall", "@public(", "allowany", "@anonymous", "allow_anonymous", "skipauth", "@skipauth", "ispublic")
    private val BODY_WORDS = listOf(
        "req.user", "req.session.user", "req.isauthenticated(", "request.user.is_authenticated", "is_authenticated", "auth()->check", "auth::check",
        "auth()->user", "auth::user", "status(401", "status(403", "status_code=401", "status_code=403", "abort(401", "abort(403", "unauthorized",
        "forbidden", "getserversession(", "currentuser", "current_user", "requireauth", "user.isadmin", "@authenticationprincipal",
        "principal", "securitycontextholder", "user.identity.isauthenticated", "context.user", "request.user",
    )
    private val SIGNATURE_STARTS = listOf("def ", "async def ", "function ", "async function ", "fun ", "func ", "public ", "private ", "protected ", "internal ", "suspend fun ", "export ", "class ", "data class ", "static ")

    fun collect(a: FileAudit): FileMarkers? {
        val text = a.text
        val signals = a.sink.signals
        if (text.contains("SecurityFilterChain") || text.contains("WebSecurityConfigurerAdapter") || text.contains("authorizeHttpRequests") ||
            text.contains("authorizeRequests") || text.contains("FallbackPolicy") || text.contains("LoginRequiredMiddleware") ||
            a.name.startsWith("middleware.") && (text.contains("auth") || text.contains("session"))
        ) {
            signals.globalAuth = true
            if (text.contains("\"/admin") || text.contains("'/admin")) signals.globalAdminRule = true
        }
        val lowerText = a.lowerText
        if (ROOTS.none { lowerText.contains(it) }) return null
        val protected = BitSet()
        val public = BitSet()
        val body = ArrayList<Int>()
        val names = HashSet<String>()
        var globalFrom = Int.MAX_VALUE
        var fileLevel = false
        val count = a.src.lineCount
        for (n in 1..count) {
            val raw = a.src.lineText(n)
            if (raw.isBlank()) continue
            val rawLower = raw.lowercase()
            if (ROOTS.none { rawLower.contains(it) }) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            if (!a.src.isCode(a.src.lineStart(n) + indent)) continue
            val lower = withoutPaths(raw).lowercase()
            if (BODY_WORDS.any { lower.contains(it) }) body += n
            val isPublic = PUBLIC_WORDS.any { lower.contains(it) }
            val isAuth = !isPublic && hasAuth(lower)
            if (!isAuth && !isPublic) continue
            val target = if (isPublic) public else protected
            val trimmed = raw.trim()
            val compact = lower.filterNot { it == ' ' || it == '\t' }
            when {
                compact.contains("\$this->middleware(") || compact.contains("before_request") || compact.contains("beforeaction") -> {
                    if (isAuth) fileLevel = true
                }
                (compact.contains(".use(") || compact.contains(".all('*'") || compact.contains(".all(\"*\"")) && !compact.contains(".get(") && !compact.contains(".post(") -> {
                    if (isAuth) {
                        globalFrom = minOf(globalFrom, n)
                        signals.globalAuth = true
                    }
                }
                compact.contains("middleware") && compact.contains("group(") -> {
                    val brace = text.indexOf('{', a.src.lineStart(n)).takeIf { it >= 0 && it - a.src.lineStart(n) < 400 }
                    val end = brace?.let { a.src.close(it, 400_000) }?.takeIf { it > 0 }
                    target.set(n, if (end != null) a.src.line(end) + 1 else n + 1)
                }
                isAnnotation(trimmed) || isSignature(trimmed) -> {
                    val (first, last, signature) = block(a, n)
                    val sig = signature?.let { a.src.lineText(it).trim() }
                    val name = sig?.let { signatureName(it) }
                    if (sig != null && isClass(sig)) {
                        // Protección de la clase entera: hasta el final de la clase (o del archivo en Python).
                        val start = a.src.lineStart(signature)
                        val brace = text.indexOf('{', start).takeIf { it >= 0 && a.src.line(it) <= signature + 2 }
                        val end = brace?.let { a.src.close(it, 2_000_000) }?.takeIf { it > 0 }?.let { a.src.line(it) } ?: count
                        target.set(first, end + 1)
                    } else {
                        target.set(first, last + 1)
                    }
                    if (isAuth && name != null) names += name
                }
                else -> target.set(n)
            }
        }
        if (protected.isEmpty && public.isEmpty && body.isEmpty() && globalFrom == Int.MAX_VALUE && !fileLevel) return null
        return FileMarkers(protected, public, body.toIntArray(), globalFrom, fileLevel, names)
    }

    /** "auth" que no es parte de "author" (sí de authorize, authenticate, AuthGuard…), o alguna otra palabra de protección. */
    private fun hasAuth(lower: String): Boolean {
        var i = lower.indexOf("auth")
        while (i >= 0) {
            val author = lower.startsWith("author", i) && !lower.startsWith("authoriz", i) && !lower.startsWith("authoris", i)
            if (!author) return true
            i = lower.indexOf("auth", i + 4)
        }
        return AUTH_WORDS.any { lower.contains(it) }
    }

    /** Quita las cadenas que son rutas (`'/auth/login'`): su texto no es una protección. */
    private fun withoutPaths(line: String): String {
        if (!line.contains('/')) return line
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'' || c == '`') {
                val end = line.indexOf(c, i + 1)
                if (end > i) {
                    val content = line.substring(i + 1, end)
                    if (!content.contains('/')) sb.append(line, i, end + 1)
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isAnnotation(t: String) = t.startsWith("@") || t.startsWith("[") || t.startsWith("#[")

    private fun isSignature(t: String) = SIGNATURE_STARTS.any { t.startsWith(it) } && (t.contains('(') || t.contains("class "))

    private fun isClass(t: String) = t.contains("class ") && !t.contains("(") || t.startsWith("class ") || t.contains(" class ")

    /**
     * Bloque de declaración alrededor de [n]: anotaciones contiguas encima y debajo y la línea de la firma.
     * Devuelve (primera línea, última línea, línea de la firma o `null`).
     */
    private fun block(a: FileAudit, n: Int): Triple<Int, Int, Int?> {
        var first = n
        while (first > 1) {
            val t = a.src.lineText(first - 1).trim()
            if (isAnnotation(t) || t.isEmpty() && first - 1 > 1 && isAnnotation(a.src.lineText(first - 2).trim())) first-- else break
        }
        if (isSignature(a.src.lineText(n).trim())) return Triple(first, n, n)
        var last = n
        var signature: Int? = null
        var k = n + 1
        while (k <= a.src.lineCount && k <= n + 12) {
            val t = a.src.lineText(k).trim()
            when {
                t.isEmpty() || isAnnotation(t) -> last = k
                else -> {
                    last = k
                    signature = k.takeIf { isSignature(t) || t.contains('(') }
                    break
                }
            }
            k++
        }
        return Triple(first, last, signature)
    }

    private fun signatureName(sig: String): String? {
        if (sig.contains("class ")) return sig.substringAfter("class ").trim().takeWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        val open = sig.indexOf('(')
        if (open <= 0) return null
        var j = open - 1
        while (j >= 0 && sig[j].isWhitespace()) j--
        val end = j + 1
        while (j >= 0 && (sig[j].isLetterOrDigit() || sig[j] == '_')) j--
        return sig.substring(j + 1, end).ifEmpty { null }
    }

    // ------------------------------------------------------------------ evaluación con las rutas

    private val PUBLIC_SEGMENTS = setOf(
        "login", "logout", "signin", "sign-in", "signup", "sign-up", "register", "auth", "oauth", "callback", "health", "healthz", "status",
        "ping", "public", "webhook", "webhooks", "reset", "forgot", "forgot-password", "reset-password", "password", "verify", "confirm",
        "docs", "swagger", "openapi", "metrics", "csrf", "token", "refresh", "version", "robots.txt", "favicon.ico", "assets", "static",
        "index", "home", "about", "contact", "sitemap.xml", "manifest.json", "api-docs", "redoc", "ready", "live", "liveness", "readiness",
    )

    fun evaluate(endpoints: List<Endpoint>, markers: Map<String, FileMarkers>, signals: ProjectSignals, add: (Finding) -> Unit): Int {
        val api = endpoints.filter { it.kind == EndpointKind.API }
        val byFile = api.groupBy { it.file }
        var evaluated = 0
        for ((file, routes) in byFile) {
            val m = markers[file]
            val lines = routes.map { it.line }.distinct().sorted()
            val status = routes.associateWith { e ->
                evaluated++
                val next = lines.firstOrNull { it > e.line } ?: (e.line + 26)
                protection(e, m, markers, next)
            }
            val protectedCount = status.values.count { it == Protection.PROTECTED }
            for ((e, st) in status) {
                if (st != Protection.NONE) continue
                val segments = e.path.lowercase().split('/').filter { it.isNotEmpty() }
                val admin = segments.any { it == "admin" || it.startsWith("admin") || it == "administrator" || it == "backoffice" }
                val flow = listOf(FlowStep(e.line, "${e.method} ${e.path.take(200)}: no se ve protección en su declaración, en su archivo ni en su controlador"))
                if (admin) {
                    if (signals.globalAdminRule) continue
                    val global = signals.globalAuth
                    add(
                        Finding(
                            SecurityRules.get("FC-ACCESS-02"), file, e.line, if (global) Certainty.DYNAMIC else Certainty.POSSIBLE,
                            severity = if (global) Severity.MEDIUM else Severity.HIGH,
                            detail = "Ruta ${e.method} ${e.path.take(200)} (${e.framework}).",
                            flow = flow,
                            downgraded = if (global) "Hay configuración global de autenticación en el proyecto que puede protegerla; solo una prueba con la app en marcha lo confirma" else null,
                        ),
                    )
                    continue
                }
                // Proporción sobre las rutas que no son públicas por su nombre (login, health…).
                val total = status.keys.count { r -> r.path.lowercase().split('/').filter { it.isNotEmpty() }.let { seg -> seg.isNotEmpty() && seg.none { it in PUBLIC_SEGMENTS } } }
                if (total >= 3 && protectedCount >= 2 && protectedCount * 3 >= total * 2 && segments.none { it in PUBLIC_SEGMENTS } && segments.isNotEmpty()) {
                    add(
                        Finding(
                            SecurityRules.get("FC-ACCESS-01"), file, e.line, Certainty.POSSIBLE,
                            detail = "Ruta ${e.method} ${e.path.take(200)}: $protectedCount de $total rutas de este archivo tienen protección visible y esta no.",
                            flow = flow,
                        ),
                    )
                }
            }
        }
        return evaluated
    }

    private enum class Protection { PROTECTED, PUBLIC, NONE }

    private fun protection(e: Endpoint, m: FileMarkers?, all: Map<String, FileMarkers>, nextRoute: Int): Protection {
        if (m != null) {
            if (m.publicLines[e.line]) return Protection.PUBLIC
            if (m.protectedLines[e.line] || e.line >= m.globalFrom || m.fileLevel) return Protection.PROTECTED
            val limit = minOf(nextRoute, e.line + 25)
            if (m.bodyChecks.any { it > e.line && it < limit }) return Protection.PROTECTED
        }
        val handlerMarkers = e.handlerFile?.let { all[it] }
        if (handlerMarkers != null) {
            if (handlerMarkers.fileLevel) return Protection.PROTECTED
            val name = e.handler?.substringAfterLast('@')?.substringAfterLast('.')?.substringAfterLast("::")?.substringAfterLast('#')
            val cls = e.handler?.substringBefore('@')?.substringAfterLast('.')?.substringAfterLast('\\')
            if (name != null && name in handlerMarkers.protectedNames || cls != null && cls in handlerMarkers.protectedNames) return Protection.PROTECTED
        }
        return Protection.NONE
    }
}
