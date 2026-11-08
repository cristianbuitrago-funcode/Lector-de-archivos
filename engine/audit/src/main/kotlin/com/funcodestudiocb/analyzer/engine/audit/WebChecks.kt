package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FlowStep

/**
 * Autenticación, criptografía, TLS, CORS, cookies, sesiones, CSRF, asignación masiva y registros (§8.2). Casi todo
 * son configuraciones escritas en el código: se comparan sin espacios (`rejectUnauthorized:false`), solo en líneas
 * que no están comentadas.
 */
internal object WebChecks {

    /** Patrón de una línea: todos los textos de [all] (sin espacios) y ninguno de [none]. */
    private class LinePattern(
        val rule: String,
        val all: List<String>,
        val certainty: Certainty,
        val detail: String,
        val none: List<String> = emptyList(),
        /** Otras palabras (en minúsculas) que tienen que estar cerca (±3 líneas): contexto de cookie, de contraseña… */
        val near: List<String> = emptyList(),
        val ignoreCase: Boolean = false,
    )

    private fun p(rule: String, vararg all: String, certainty: Certainty = Certainty.CONFIRMED, detail: String, none: List<String> = emptyList(), near: List<String> = emptyList(), ignoreCase: Boolean = false) =
        LinePattern(rule, all.toList(), certainty, detail, none, near, ignoreCase)

    private val COOKIE_WORDS = listOf("cookie", "session")

    private val PATTERNS: Map<String, List<LinePattern>> = mapOf(
        "js" to listOf(
            p("FC-TLS-01", "rejectUnauthorized:false", detail = "Se desactiva la comprobación del certificado (rejectUnauthorized: false)."),
            p("FC-TLS-01", "NODE_TLS_REJECT_UNAUTHORIZED", "0", detail = "NODE_TLS_REJECT_UNAUTHORIZED = 0 desactiva la comprobación de certificados en todo el proceso."),
            p("FC-TLS-01", "strictSSL:false", detail = "Se desactiva la comprobación del certificado (strictSSL: false)."),
            p("FC-AUTH-02", "algorithms:", "none", detail = "Se acepta el algoritmo «none»: tokens sin firma.", ignoreCase = true),
            p("FC-AUTH-02", "algorithm:", "'none'", detail = "Se firma con el algoritmo «none»: tokens sin firma."),
            p("FC-ELECTRON-01", "nodeIntegration:true", detail = "nodeIntegration: true da acceso a Node.js al contenido de la ventana."),
            p("FC-ELECTRON-01", "contextIsolation:false", detail = "contextIsolation: false mezcla el contexto de la página con el de la app."),
            p("FC-ELECTRON-01", "webSecurity:false", detail = "webSecurity: false desactiva la política del mismo origen."),
            p("FC-COOKIE-01", "httpOnly:false", detail = "La cookie se crea con httpOnly: false."),
            p("FC-COOKIE-02", "secure:false", detail = "La cookie se crea con secure: false.", near = COOKIE_WORDS),
            p("FC-CSRF-01", "csrf:false", detail = "Se desactiva la protección CSRF."),
        ),
        "python" to listOf(
            p("FC-TLS-01", "verify=False", detail = "La petición se hace con verify=False: acepta cualquier certificado.", none = listOf("jwt.", "\"verify_signature\"", "'verify_signature'")),
            p("FC-TLS-01", "_create_unverified_context", detail = "Se crea un contexto SSL sin verificación."),
            p("FC-TLS-01", "CERT_NONE", detail = "Se usa ssl.CERT_NONE: no se comprueba el certificado."),
            p("FC-TLS-01", "check_hostname=False", detail = "Se desactiva la comprobación del nombre del servidor."),
            p("FC-AUTH-02", "jwt.decode(", "verify=False", detail = "jwt.decode(…, verify=False) no comprueba la firma."),
            p("FC-AUTH-02", "\"verify_signature\":False", detail = "Se desactiva la comprobación de la firma del JWT (verify_signature: False)."),
            p("FC-AUTH-02", "'verify_signature':False", detail = "Se desactiva la comprobación de la firma del JWT (verify_signature: False)."),
            p("FC-AUTH-02", "algorithms=", "none", detail = "Se acepta el algoritmo «none»: tokens sin firma.", ignoreCase = true),
            p("FC-DEBUG-01", "app.run(", "debug=True", detail = "Flask se arranca con debug=True: la consola de depuración permite ejecutar código."),
            p("FC-COOKIE-01", "SESSION_COOKIE_HTTPONLY=False", detail = "La cookie de sesión es accesible desde JavaScript."),
            p("FC-COOKIE-01", "httponly=False", detail = "La cookie se crea con httponly=False.", near = COOKIE_WORDS),
            p("FC-COOKIE-02", "SESSION_COOKIE_SECURE=False", detail = "La cookie de sesión también viaja por HTTP."),
            p("FC-COOKIE-02", "secure=False", detail = "La cookie se crea con secure=False.", near = COOKIE_WORDS),
            p("FC-CSRF-01", "@csrf_exempt", detail = "La vista está exenta de la protección CSRF (@csrf_exempt)."),
            p("FC-CSRF-01", "WTF_CSRF_ENABLED=False", detail = "Se desactiva la protección CSRF de Flask-WTF."),
            p("FC-CSRF-01", "CSRF_ENABLED=False", detail = "Se desactiva la protección CSRF.", none = listOf("WTF_CSRF_ENABLED")),
        ),
        "php" to listOf(
            p("FC-TLS-01", "CURLOPT_SSL_VERIFYPEER,false", detail = "cURL no comprueba el certificado (CURLOPT_SSL_VERIFYPEER = false)."),
            p("FC-TLS-01", "CURLOPT_SSL_VERIFYPEER,0", detail = "cURL no comprueba el certificado (CURLOPT_SSL_VERIFYPEER = 0)."),
            p("FC-TLS-01", "CURLOPT_SSL_VERIFYHOST,0", detail = "cURL no comprueba el nombre del servidor (CURLOPT_SSL_VERIFYHOST = 0)."),
            p("FC-TLS-01", "'verify'=>false", detail = "El cliente HTTP no comprueba el certificado ('verify' => false)."),
            p("FC-DEBUG-01", "ini_set(", "display_errors", "1", detail = "Se muestran los errores de PHP en la página (display_errors)."),
            p("FC-DEBUG-01", "ini_set(", "display_errors", "On", detail = "Se muestran los errores de PHP en la página (display_errors)."),
            p("FC-COOKIE-01", "'http_only'=>false", detail = "La cookie de sesión es accesible desde JavaScript ('http_only' => false)."),
            p("FC-COOKIE-01", "'httponly'=>false", detail = "La cookie es accesible desde JavaScript ('httponly' => false)."),
            p("FC-COOKIE-02", "'secure'=>false", detail = "La cookie también viaja por HTTP ('secure' => false).", near = COOKIE_WORDS),
            p("FC-MASS-01", "\$guarded=[]", certainty = Certainty.POSSIBLE, detail = "El modelo permite asignar cualquier campo (\$guarded = [])."),
        ),
        "jvm" to listOf(
            p("FC-TLS-01", "NoopHostnameVerifier", detail = "Se usa NoopHostnameVerifier: no se comprueba el nombre del servidor.", none = listOf("import ")),
            p("FC-TLS-01", "ALLOW_ALL_HOSTNAME_VERIFIER", detail = "Se acepta cualquier nombre de servidor."),
            p("FC-TLS-01", "hostnameVerifier{_,_->true}", detail = "El verificador de nombres de servidor siempre devuelve verdadero."),
            p("FC-TLS-01", "HostnameVerifier{_,_->true}", detail = "El verificador de nombres de servidor siempre devuelve verdadero."),
            p("FC-TLS-01", "(hostname,session)->true", detail = "El verificador de nombres de servidor siempre devuelve verdadero."),
            p("FC-TLS-01", "setHostnameVerifier(", "->true", detail = "El verificador de nombres de servidor siempre devuelve verdadero."),
            p("FC-AUTH-02", "parseClaimsJwt(", detail = "parseClaimsJwt acepta tokens sin firma."),
            p("FC-AUTH-02", "parseUnsecuredClaims(", detail = "Se aceptan tokens sin firma."),
            p("FC-WEBVIEW-02", "setAllowFileAccess(true)", detail = "El WebView permite acceder a archivos locales."),
            p("FC-WEBVIEW-02", "allowFileAccess=true", detail = "El WebView permite acceder a archivos locales."),
            p("FC-WEBVIEW-02", "setAllowUniversalAccessFromFileURLs(true)", detail = "Una página local puede leer cualquier origen."),
            p("FC-WEBVIEW-02", "allowUniversalAccessFromFileURLs=true", detail = "Una página local puede leer cualquier origen."),
            p("FC-WEBVIEW-02", "setAllowFileAccessFromFileURLs(true)", detail = "Una página local puede leer otros archivos."),
            p("FC-WEBVIEW-02", "allowFileAccessFromFileURLs=true", detail = "Una página local puede leer otros archivos."),
            p("FC-DEBUG-01", "setWebContentsDebuggingEnabled(true)", detail = "La depuración remota del WebView está activa.", none = listOf("BuildConfig.DEBUG", "isDebuggable")),
            p("FC-COOKIE-01", "setHttpOnly(false)", detail = "La cookie se crea con setHttpOnly(false)."),
            p("FC-COOKIE-02", "setSecure(false)", detail = "La cookie se crea con setSecure(false)."),
            p("FC-CSRF-01", "csrf().disable()", detail = "Spring Security con la protección CSRF desactivada."),
            p("FC-CSRF-01", "csrf{disable()}", detail = "Spring Security con la protección CSRF desactivada."),
            p("FC-CSRF-01", "csrf(AbstractHttpConfigurer::disable)", detail = "Spring Security con la protección CSRF desactivada."),
            p("FC-CSRF-01", "csrf(", ".disable())", detail = "Spring Security con la protección CSRF desactivada."),
        ),
        "csharp" to listOf(
            p("FC-TLS-01", "ServerCertificateCustomValidationCallback", "=>true", detail = "La validación del certificado siempre devuelve verdadero."),
            p("FC-TLS-01", "ServerCertificateValidationCallback", "=>true", detail = "La validación del certificado siempre devuelve verdadero."),
            p("FC-TLS-01", "DangerousAcceptAnyServerCertificateValidator", detail = "Se acepta cualquier certificado."),
            p("FC-AUTH-02", "ValidateIssuerSigningKey=false", detail = "No se valida la clave que firma los tokens."),
            p("FC-AUTH-02", "RequireSignedTokens=false", detail = "Se aceptan tokens sin firma."),
            p("FC-AUTH-02", "SignatureValidator=", detail = "Se sustituye la validación de la firma por una propia."),
            p("FC-DESER-01", "TypeNameHandling.All", certainty = Certainty.POSSIBLE, detail = "TypeNameHandling.All deja que el JSON decida qué tipos se crean."),
            p("FC-DESER-01", "TypeNameHandling.Auto", certainty = Certainty.POSSIBLE, detail = "TypeNameHandling.Auto deja que el JSON decida qué tipos se crean."),
            p("FC-DESER-01", "TypeNameHandling.Objects", certainty = Certainty.POSSIBLE, detail = "TypeNameHandling.Objects deja que el JSON decida qué tipos se crean."),
            p("FC-COOKIE-01", "HttpOnly=false", detail = "La cookie se crea con HttpOnly = false."),
            p("FC-COOKIE-02", "CookieSecurePolicy.None", detail = "Las cookies también viajan por HTTP (CookieSecurePolicy.None)."),
            p("FC-COOKIE-02", "Secure=false", detail = "La cookie se crea con Secure = false.", near = COOKIE_WORDS),
            p("FC-CSRF-01", "[IgnoreAntiforgeryToken", detail = "Se ignora la protección antifalsificación ([IgnoreAntiforgeryToken])."),
        ),
        "go" to listOf(
            p("FC-TLS-01", "InsecureSkipVerify:true", detail = "La conexión TLS se hace con InsecureSkipVerify: true."),
            p("FC-COOKIE-01", "HttpOnly:false", detail = "La cookie se crea con HttpOnly: false."),
            p("FC-COOKIE-02", "Secure:false", detail = "La cookie se crea con Secure: false.", near = COOKIE_WORDS),
        ),
    )

    /** Texto que tiene que aparecer en el archivo para que el patrón pueda coincidir (su nombre más largo). */
    private fun trigger(pt: LinePattern): String {
        val first = pt.all.first()
        var best = ""
        var cur = StringBuilder()
        for (ch in first + " ") {
            if (ch.isLetterOrDigit() || ch == '_') cur.append(ch) else {
                if (cur.length > best.length) best = cur.toString()
                cur = StringBuilder()
            }
        }
        return if (pt.ignoreCase) best.lowercase() else best
    }

    private val TRIGGERS = HashMap<LinePattern, String>()

    private val CORS_WORDS = listOf("origin", "cors", "credentials")

    fun check(a: FileAudit) {
        val fam = a.family ?: return
        val text = a.text
        val lowerText = a.lowerText
        val patterns = PATTERNS[fam].orEmpty().filter { pt ->
            val t = synchronized(TRIGGERS) { TRIGGERS.getOrPut(pt) { trigger(pt) } }
            (if (pt.ignoreCase) lowerText else text).contains(t)
        }
        val triggers = patterns.map { synchronized(TRIGGERS) { TRIGGERS.getValue(it) } to it.ignoreCase }
        val hashOn = lowerText.contains("md5") || lowerText.contains("sha1")
        val randomOn = lowerText.contains("rand")
        val storageOn = fam == "js" && text.contains("Storage")
        val corsOn = CORS_WORDS.any { lowerText.contains(it) }
        val cors = CorsState()
        if (patterns.isNotEmpty() || hashOn || randomOn || storageOn || corsOn) a.lines { n, line ->
            if (line.isBlank()) return@lines
            val lowerLine by lazy(LazyThreadSafetyMode.NONE) { line.lowercase() }
            val relevant = triggers.any { (t, ic) -> if (ic) lowerLine.contains(t) else line.contains(t) } ||
                hashOn && (lowerLine.contains("md5") || lowerLine.contains("sha1")) || randomOn && lowerLine.contains("rand") ||
                storageOn && line.contains("Storage") || corsOn && CORS_WORDS.any { lowerLine.contains(it) }
            if (!relevant || !lineIsCode(a, n, line)) return@lines
            val (compact, map) = compactWithMap(line)
            val lowerCompact = compact.lowercase()
            val start = a.src.lineStart(n)
            // La posición en el texto de un índice de la línea compacta está en código (no dentro de una cadena).
            val inCode = { idx: Int -> idx >= 0 && !a.src.inString(start + map[idx]) }
            for (pt in patterns) {
                val hay = if (pt.ignoreCase) lowerCompact else compact
                val first = if (pt.ignoreCase) pt.all.first().lowercase() else pt.all.first()
                if (pt.all.any { !hay.contains(if (pt.ignoreCase) it.lowercase() else it) }) continue
                if (pt.none.any { compact.contains(it) }) continue
                // La evidencia empieza en código: un nombre o la comilla que abre un texto (no dentro de otra cadena).
                if (!inCode(hay.indexOf(first))) continue
                if (pt.near.isNotEmpty() && a.nearLower(n, pt.near) == null) continue
                a.add(pt.rule, n, pt.certainty, pt.detail)
            }
            weakHash(a, n, lowerCompact, inCode)
            insecureRandom(a, n, lowerCompact, inCode)
            tokenStorage(a, n, compact, inCode)
            corsLine(a, n, compact, cors, inCode)
        }
        signals(a)
        cors.report(a)
        trustManagers(a)
        jwtDecode(a)
        massAssignment(a)
        if (LOG_HINTS.any { lowerText.contains(it) }) logging(a)
    }

    private val LOG_HINTS = listOf("log", "print", "dump", "console")

    /** La línea sin espacios ni tabuladores y, para cada carácter, su posición en la línea original. */
    private fun compactWithMap(line: String): Pair<String, IntArray> {
        val sb = StringBuilder(line.length)
        val map = IntArray(line.length)
        for (i in line.indices) {
            val c = line[i]
            if (c == ' ' || c == '\t') continue
            map[sb.length] = i
            sb.append(c)
        }
        return sb.toString() to map
    }

    /** La línea no empieza dentro de un comentario (`// rejectUnauthorized: false`). */
    fun lineIsCode(a: FileAudit, n: Int, line: String): Boolean {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        return indent >= 0 && a.src.isCode(a.src.lineStart(n) + indent)
    }

    private fun FileAudit.nearLower(line: Int, words: List<String>): String? {
        for (k in (line - 3).coerceAtLeast(1)..(line + 3).coerceAtMost(src.lineCount)) {
            val t = src.lineText(k).lowercase()
            words.firstOrNull { t.contains(it) }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ contraseñas y aleatorios

    private val PASSWORD_WORDS = listOf("password", "passwd", "pwd", "contraseña", "contrasena", "passphrase")
    private val STRONG = listOf("bcrypt", "argon", "pbkdf", "scrypt", "password_hash", "hmac")

    /** Usos reales de MD5 o SHA-1 (llamadas), en minúsculas y sin espacios. */
    private val WEAK_HASH_CALLS = listOf(
        "md5(", "sha1(", "hashlib.md5", "hashlib.sha1", "getinstance(\"md5\")", "getinstance(\"sha-1\")", "getinstance(\"sha1\")",
        "createhash('md5')", "createhash(\"md5\")", "createhash('sha1')", "createhash(\"sha1\")", "hash('md5'", "hash(\"md5\"",
        "hash('sha1'", "hash(\"sha1\"", "md5.create", "sha1.create", "md5hex(", "sha1hex(", "hashing.md5", "hashing.sha1",
        "digestutils.md5", "digestutils.sha1", "crypto/md5", "crypto/sha1", "md5.sum(", "sha1.sum(", "md5.new(", "sha1.new(",
    )

    private fun weakHash(a: FileAudit, n: Int, lower: String, inCode: (Int) -> Boolean) {
        val call = WEAK_HASH_CALLS.firstOrNull { c -> lower.contains(c) && inCode(lower.indexOf(c)) } ?: return
        if (STRONG.any { lower.contains(it) }) return
        // La contraseña tiene que estar en la misma línea o encima, dentro de la misma función.
        var wordLine = -1
        var word: String? = null
        for (k in n downTo (n - 3).coerceAtLeast(1)) {
            val t = a.src.lineText(k)
            val lowerLine = t.lowercase()
            PASSWORD_WORDS.firstOrNull { lowerLine.contains(it) }?.let {
                word = it
                wordLine = k
            }
            if (word != null || isSignature(t)) break
        }
        val found = word ?: return
        val hash = if (call.contains("md5")) "MD5" else "SHA-1"
        val wordAt = wordLine
        a.add(
            "FC-AUTH-01", n, Certainty.PROBABLE, "Se usa $hash y cerca aparece «$found».",
            flow = listOfNotNull(
                FlowStep(wordAt, "Aparece «$found»").takeIf { wordAt != n },
                FlowStep(n, "Se calcula $hash" + if (wordAt == n) " sobre «$found»" else ""),
            ),
        )
    }

    /** Firma de una función: `def x(`, `function x(`, `String x(…) {`, `fun x(`. */
    private fun isSignature(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("def ") || t.startsWith("function ") || t.startsWith("fun ") || t.startsWith("func ") || t.startsWith("async ") ||
            (t.contains(") {") || t.contains("){") || t.contains('(') && t.endsWith(":")) &&
            !t.startsWith("if") && !t.startsWith("for") && !t.startsWith("while") && !t.startsWith("}")
    }

    private val RANDOM_CALLS = listOf("math.random()", "random.random()", "random.randint(", "random.choice(", "random.choices(", "newrandom(", "random()", "mt_rand(", "rand(", "random.next", "rand.intn(", "rand.int(")
    private val SECRET_USE = listOf(
        "token", "passw", "secret", "otp", "nonce", "salt", "apikey", "api_key", "sessionid", "session_id", "resetcode", "reset_code",
        "verificationcode", "verification_code", "csrf", "invite",
    )

    private fun insecureRandom(a: FileAudit, n: Int, lower: String, inCode: (Int) -> Boolean) {
        val call = RANDOM_CALLS.firstOrNull { lower.contains(it) && inCode(lower.indexOf(it)) } ?: return
        if (call == "random()" && !lower.contains("newrandom()") && !lower.contains("=random()")) return
        if (call == "rand(" && a.family != "php") return
        if (call.startsWith("rand.") && !a.text.contains("\"math/rand\"")) return
        if (lower.contains("securerandom") || lower.contains("crypto") || lower.contains("secrets.") || lower.contains("random_bytes") || lower.contains("random_int(")) return
        val use = SECRET_USE.firstOrNull { lower.contains(it) } ?: return
        a.add(
            "FC-AUTH-04", n, Certainty.PROBABLE, "Se genera «$use» con ${call.trimEnd('(')}, que no es criptográfico.",
            flow = listOf(FlowStep(n, "Un valor de ${call.trimEnd('(')} se usa como «$use»")),
        )
    }

    // ------------------------------------------------------------------ sesiones en el navegador

    private val TOKEN_WORDS = listOf("token", "jwt", "auth", "session", "access", "refresh", "bearer")

    private fun tokenStorage(a: FileAudit, n: Int, compact: String, inCode: (Int) -> Boolean) {
        if (a.family != "js" || !inCode(compact.indexOf("Storage"))) return
        val storage = when {
            compact.contains("localStorage.setItem(") || compact.contains("localStorage[") || compact.contains("localStorage.token") -> "localStorage"
            compact.contains("sessionStorage.setItem(") || compact.contains("sessionStorage[") -> "sessionStorage"
            else -> return
        }
        val lower = compact.lowercase()
        val word = TOKEN_WORDS.firstOrNull { lower.substringAfter(storage.lowercase()).contains(it) } ?: return
        if (lower.contains("getitem(") && !lower.contains("setitem(")) return
        a.add("FC-SESSION-01", n, Certainty.PROBABLE, "Se guarda «$word» en $storage.")
    }

    // ------------------------------------------------------------------ CORS

    private class CorsState {
        var anyOrigin: Pair<Int, String>? = null
        var reflect: Pair<Int, String>? = null
        var credentials = false

        fun report(a: FileAudit) {
            reflect?.let { (line, how) ->
                a.add("FC-CORS-03", line, Certainty.CONFIRMED, how + if (credentials) " Además se permiten credenciales." else "")
                return
            }
            anyOrigin?.let { (line, how) ->
                if (credentials) {
                    a.add("FC-CORS-01", line, Certainty.CONFIRMED, "$how Y se permiten credenciales.")
                } else {
                    a.add("FC-CORS-02", line, Certainty.CONFIRMED, how)
                }
            }
        }
    }

    private val ANY_ORIGIN = listOf(
        "origin:'*'", "origin:\"*\"", "'Access-Control-Allow-Origin','*'", "\"Access-Control-Allow-Origin\",\"*\"", "header('Access-Control-Allow-Origin:*')",
        "header(\"Access-Control-Allow-Origin:*\")",
        "allow_origins=[\"*\"]", "allow_origins=['*']", "CORS_ALLOW_ALL_ORIGINS=True", "CORS_ORIGIN_ALLOW_ALL=True", "AllowAnyOrigin()",
        "allowedOrigins(\"*\")", "allowedOriginPatterns(\"*\")", "origins=\"*\"", "origins='*'", "'allowed_origins'=>['*']",
        "AllowAllOrigins:true", "AllowOrigins:[]string{\"*\"}", "app.use(cors())", "CORS(app)", "@CrossOrigin(origins=\"*\")",
        "AllowedOrigins:[]string{\"*\"}", "Header().Set(\"Access-Control-Allow-Origin\",\"*\")", "'allowed_origins'=>[\"*\"]",
    )
    private val REFLECT = listOf(
        "origin:true", "SetIsOriginAllowed(_=>true)", "SetIsOriginAllowed(origin=>true)", "SetIsOriginAllowed(x=>true)", "callback(null,true)",
    )
    private val ORIGIN_HEADER = listOf("req.headers.origin", "req.header('origin')", "req.get('origin')", "\$_SERVER['HTTP_ORIGIN']", "request.headers.get('Origin')", "Request.Headers[\"Origin\"]", "r.Header.Get(\"Origin\")")
    private val CREDENTIALS = listOf(
        "credentials:true", "'Access-Control-Allow-Credentials','true'", "\"Access-Control-Allow-Credentials\",\"true\"", "header('Access-Control-Allow-Credentials:true')",
        "allow_credentials=True", "CORS_ALLOW_CREDENTIALS=True", "AllowCredentials()", "allowCredentials(true)", "allowCredentials=\"true\"",
        "'supports_credentials'=>true", "AllowCredentials:true", "supports_credentials=True", "header(\"Access-Control-Allow-Credentials:true\")",
    )

    private fun corsLine(a: FileAudit, n: Int, compact: String, cors: CorsState, inCode: (Int) -> Boolean) {
        // La evidencia empieza en código: un nombre (`origin:`, `AllowAnyOrigin()`) o la comilla de apertura de un texto.
        val found = { needle: String -> compact.contains(needle) && inCode(compact.indexOf(needle)) }
        if (cors.anyOrigin == null) {
            ANY_ORIGIN.firstOrNull(found)?.let { cors.anyOrigin = n to "Se permite cualquier origen («${it.take(60)}»)." }
            if (found("@CrossOrigin") && !compact.contains("origins") && !compact.contains("value=") && !compact.contains("(\"")) {
                cors.anyOrigin = n to "@CrossOrigin sin orígenes permite cualquiera."
            }
        }
        if (cors.reflect == null) {
            REFLECT.firstOrNull(found)?.takeIf { it != "callback(null,true)" || compact.contains("origin") }?.let {
                cors.reflect = n to "El origen permitido se acepta sin comprobarlo («${it.take(60)}»)."
            }
            if (compact.contains("Access-Control-Allow-Origin")) {
                ORIGIN_HEADER.firstOrNull(found)?.let { cors.reflect = n to "La cabecera Access-Control-Allow-Origin copia el origen de la petición." }
            }
        }
        if (!cors.credentials && CREDENTIALS.any(found)) cors.credentials = true
    }

    // ------------------------------------------------------------------ señales del proyecto

    private fun signals(a: FileAudit) {
        val s = a.sink.signals
        val text = a.text
        if (s.sessionCookies == null && a.family == "js") {
            listOf("express-session", "cookie-session").firstOrNull { text.contains(it) }?.let { lib ->
                s.sessionCookies = a.place(text.indexOf(lib))
            }
        }
        val lower = a.lowerText
        if (!s.csrfProtection && (lower.contains("csrf") || lower.contains("samesite") || lower.contains("lusca")) && CSRF_PROTECTION.any { text.contains(it) }) {
            s.csrfProtection = true
        }
        if (!s.bearerTokens && (lower.contains("bearer") || lower.contains("jwt") || lower.contains("stateless")) && BEARER.any { text.contains(it) }) {
            s.bearerTokens = true
        }
        if (!s.validation && VALIDATION_HINTS.any { lower.contains(it) } && VALIDATION.any { text.contains(it) }) s.validation = true
        if (s.requestData == null && a.family in setOf("js", "python", "php")) {
            val sources = when (a.family) {
                "js" -> listOf("req.body", "req.query")
                "python" -> listOf("request.form", "request.json", "request.get_json(", "request.args")
                else -> listOf("\$_POST", "\$_GET", "\$_REQUEST")
            }
            sources.firstOrNull { text.contains(it) }?.let { src -> s.requestData = a.place(text.indexOf(src)) to a.family!! }
        }
    }

    private val VALIDATION_HINTS = listOf("valid", "joi", "zod", "yup", "schema", "pydantic", "marshmallow", "cerberus", "wtforms", "forms.", "filter_", "superstruct", "celebrate")

    private val CSRF_PROTECTION = listOf(
        "csurf", "csrf-csrf", "lusca", "CSRFProtect", "sameSite: 'strict'", "sameSite: \"strict\"", "sameSite: 'lax'", "sameSite: \"lax\"",
        "sameSite:'strict'", "sameSite:'lax'", "tiny-csrf", "csrfProtection", "CsrfProtect", "flask_wtf", "SESSION_COOKIE_SAMESITE",
    )
    private val BEARER = listOf(
        "Bearer", "jsonwebtoken", "JwtDecoder", "oauth2ResourceServer", "JwtBearer", "SessionCreationPolicy.STATELESS", "flask_jwt",
        "rest_framework_simplejwt", "passport-jwt",
    )

    /** Bibliotecas o mecanismos de validación de datos de entrada. */
    private val VALIDATION = listOf(
        "require('joi')", "from 'joi'", "require(\"joi\")", "require('zod')", "from 'zod'", "from \"zod\"", "from 'yup'", "require('yup')",
        "express-validator", "class-validator", "require('ajv')", "from 'ajv'", "celebrate", "superstruct", "valibot", "pydantic", "marshmallow",
        "cerberus", "wtforms", "flask_wtf", "jsonschema", "voluptuous", "->validate(", "Validator::make(", "@Valid", "jakarta.validation",
        "javax.validation", "ModelState.IsValid", "FluentValidation", "validator.New(", "go-playground/validator", ".is_valid(", "forms.Form",
        "forms.ModelForm", "filter_input(", "filter_var(",
    )

    // ------------------------------------------------------------------ TLS en Java/Kotlin

    /** `checkServerTrusted(…) {}` vacío: el gestor de confianza acepta cualquier certificado. */
    private fun trustManagers(a: FileAudit) {
        if (a.family != "jvm" || !a.text.contains("checkServerTrusted")) return
        a.src.each("checkServerTrusted") { at ->
            val open = a.text.indexOf('(', at)
            if (open < 0 || open - at > 20) return@each
            val close = a.src.close(open)
            if (close < 0) return@each
            val brace = a.text.indexOf('{', close)
            if (brace < 0 || brace - close > 120) return@each
            val end = a.src.close(brace)
            if (end < 0) return@each
            val body = a.text.substring(brace + 1, end)
            val meaningful = body.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() && !it.startsWith("/*") && !it.startsWith("*") }
            if (meaningful.isEmpty() || meaningful.all { it == "return" || it == "return;" || it == "Unit" }) {
                a.addAt("FC-TLS-01", at, Certainty.CONFIRMED, "checkServerTrusted no comprueba nada: se acepta cualquier certificado.")
            }
        }
    }

    /** `jwt.decode(token)` de jsonwebtoken o `JWT.decode(token)` de auth0 sin ninguna verificación en el archivo. */
    private fun jwtDecode(a: FileAudit) {
        val (needle, verify) = when (a.family) {
            "js" -> "jwt.decode(" to listOf("jwt.verify(", ".verify(")
            "jvm" -> "JWT.decode(" to listOf("JWT.require(", ".verify(")
            else -> return
        }
        if (!a.text.contains(needle) || verify.any { a.text.contains(it) }) return
        a.src.each(needle) { at ->
            a.addAt("FC-AUTH-02", at, Certainty.POSSIBLE, "Se decodifica el token con ${needle.trimEnd('(')} y en este archivo no se verifica la firma.")
        }
    }

    // ------------------------------------------------------------------ asignación masiva

    private val MASS_SOURCES = mapOf(
        "js" to listOf("req.body", "ctx.request.body", "request.body"),
        "php" to listOf("\$request->all()", "\$request->input()", "Input::all()", "\$_POST"),
        "python" to listOf("**request.data", "**request.POST", "**request.json", "**request.get_json()", "**request.form", "**request.validated_data"),
    )
    private val MASS_CALLS = setOf(
        "create", "update", "insert", "insertMany", "insertOne", "build", "upsert", "findByIdAndUpdate", "findOneAndUpdate", "updateOne", "updateMany",
        "save", "assign", "merge", "fill", "forceFill", "updateOrCreate", "firstOrCreate", "firstOrNew", "objects.create", "update_or_create",
        "get_or_create", "bulkCreate", "createMany", "updateMany",
    )

    private fun massAssignment(a: FileAudit) {
        val sources = MASS_SOURCES[a.family] ?: return
        val text = a.text
        for (source in sources) {
            if (!text.contains(source)) continue
            var i = text.indexOf(source)
            var count = 0
            while (i >= 0 && count < 200) {
                count++
                val at = i
                i = text.indexOf(source, i + source.length)
                if (!a.src.isCode(at) || a.src.inString(at)) continue
                val after = text.getOrNull(a.src.skipWs(at + source.length))
                if (a.family == "js" && after != ')' && after != ',' && after != '}') continue
                if (a.family == "php" && source == "\$_POST" && after != ')' && after != ',') continue
                val name = enclosingCall(text, at) ?: continue
                val short = name.substringAfterLast('.').substringAfterLast("->").substringAfterLast("::")
                val newModel = name.startsWith("new ") && name.removePrefix("new ").firstOrNull()?.isUpperCase() == true
                val pythonModel = a.family == "python" && short.firstOrNull()?.isUpperCase() == true
                if (short !in MASS_CALLS && name !in MASS_CALLS && !newModel && !pythonModel) continue
                val line = a.src.line(at)
                val lineText = a.src.lineText(line)
                if (lineText.contains("validate") || lineText.contains("only(") || lineText.contains("pick(") || lineText.contains("validated(")) continue
                a.add(
                    "FC-MASS-01", line, Certainty.PROBABLE, "Se pasa ${source.removePrefix("**")} completo a ${name.take(60)}().",
                    listOf(FlowStep(line, "Todos los campos de la petición (${source.removePrefix("**")}) llegan a ${name.take(60)}()")),
                )
            }
        }
    }

    /** Nombre de la llamada cuyo paréntesis contiene la posición (`User.create`, `new Post`), o `null`. */
    private fun enclosingCall(text: String, at: Int): String? {
        var depth = 0
        var i = at - 1
        val stop = maxOf(0, at - 300)
        while (i >= stop) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '[', '{' -> if (depth > 0) depth-- // `{ ...req.body }` dentro de la llamada
                '(' -> {
                    if (depth > 0) depth-- else {
                        var j = i - 1
                        while (j >= 0 && text[j].isWhitespace()) j--
                        val end = j + 1
                        while (j >= 0 && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.' || text[j] == '$' || text[j] == '>' || text[j] == '-' || text[j] == ':')) j--
                        var name = text.substring(j + 1, end).trimStart('-', '>', ':', '.')
                        if (name.isEmpty()) return null
                        val before = text.substring(maxOf(0, j - 3), j + 1)
                        if (before.endsWith("new ")) name = "new $name"
                        return name
                    }
                }
                ';', '\n' -> if (depth == 0 && text[i] == ';') return null
            }
            i--
        }
        return null
    }

    // ------------------------------------------------------------------ registros

    private val LOG_CALLS = listOf(
        "console.log(", "console.info(", "console.debug(", "console.warn(", "console.error(", "logger.info(", "logger.debug(", "logger.warn(",
        "logger.warning(", "logger.error(", "log.info(", "log.debug(", "log.warn(", "log.error(", "Log.d(", "Log.i(", "Log.e(", "Log.v(", "Log.w(",
        "logging.info(", "logging.debug(", "logging.warning(", "logging.error(", "print(", "println(", "System.out.println(", "System.out.print(",
        "error_log(", "Console.WriteLine(", "_logger.LogInformation(", "_logger.LogDebug(", "_logger.LogWarning(", "_logger.LogError(",
        "fmt.Println(", "fmt.Printf(", "log.Println(", "log.Printf(", "Timber.d(", "Timber.i(", "Timber.e(", "var_dump(", "print_r(",
    )
    private val SENSITIVE = setOf("password", "passwd", "pwd", "secret", "token", "apikey", "credential", "credentials", "cvv", "creditcard", "cardnumber", "ssn", "privatekey")
    private val HARMLESS_LAST = setOf(
        "length", "len", "size", "count", "valid", "invalid", "required", "field", "label", "hint", "error", "errors", "policy", "strength", "type",
        "name", "url", "expiry", "expires", "expiration", "endpoint", "id", "ids", "status", "exists", "changed", "reset", "match", "matches",
        "visible", "enabled", "input", "view", "key", "header", "prefix", "regex", "pattern", "min", "max",
    )

    private fun logging(a: FileAudit) {
        val text = a.text
        for (call in LOG_CALLS) {
            if (!text.contains(call)) continue
            a.src.each(call) { at ->
                if (call == "print(" || call == "println(") {
                    val prev = text.getOrNull(at - 1)
                    if (prev != null && (Character.isLetterOrDigit(prev) || prev == '_' || prev == '.')) return@each
                }
                val open = at + call.length - 1
                val args = a.src.args(open) ?: return@each
                for (arg in args) {
                    val e = Taint.expr(a.src, arg)
                    val hit = e.parts.firstNotNullOfOrNull { sensitivePart(it) } ?: continue
                    val line = a.src.line(at)
                    a.add("FC-LOG-01", line, Certainty.POSSIBLE, "Se escribe «${hit.take(60)}» con ${call.trimEnd('(')}.", listOf(FlowStep(line, "«${hit.take(60)}» llega al registro")))
                    return@each
                }
            }
        }
    }

    /** La parte es una variable con nombre de dato sensible (`password`, `user.apiKey`, `accessToken`). */
    private fun sensitivePart(part: String): String? {
        val p = part.trim()
        if (p.contains('(') || p.isEmpty()) return null
        val last = p.substringAfterLast('.').substringAfterLast("->").removePrefix("$").trim('[', ']', '"', '\'')
        val words = words(last)
        if (words.isEmpty() || words.last() in HARMLESS_LAST) return null
        val joined = words.joinToString("")
        val sensitive = words.any { it in SENSITIVE } || joined.endsWith("apikey") || joined.endsWith("accesstoken") || joined.endsWith("secretkey")
        return if (sensitive) p else null
    }

    /** `accessToken` → [access, token]; `API_KEY` → [api, key]. */
    fun words(name: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (i in name.indices) {
            val c = name[i]
            when {
                c == '_' || c == '-' -> if (cur.isNotEmpty()) {
                    out += cur.toString().lowercase()
                    cur.setLength(0)
                }
                c.isUpperCase() && i > 0 && name[i - 1].isLowerCase() -> {
                    if (cur.isNotEmpty()) out += cur.toString().lowercase()
                    cur.setLength(0)
                    cur.append(c)
                }
                c.isLetterOrDigit() -> cur.append(c)
                else -> {}
            }
        }
        if (cur.isNotEmpty()) out += cur.toString().lowercase()
        return out
    }
}
