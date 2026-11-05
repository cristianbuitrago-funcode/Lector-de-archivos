package com.funcodestudiocb.analyzer.engine.security

import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecretType
import com.google.re2j.Pattern

/** Un posible secreto encontrado. Nunca contiene el valor: solo su tipo, posición, longitud y la versión enmascarada. */
data class DetectedSecret(
    val type: SecretType,
    /** Línea (desde 1) donde empieza. */
    val line: Int,
    val length: Int,
    /** Como máximo los 4 primeros y los 2 últimos caracteres (§8.4), y nunca más de 18 caracteres en total. */
    val masked: String,
    /** HMAC-SHA256 del valor con la clave local (32 caracteres hexadecimales); `null` sin clave. */
    val fingerprint: String? = null,
    /** El valor parece de ejemplo o de marcador (`changeme`, `your_api_key`…): se rebaja, no se oculta (§8.6). */
    val placeholder: Boolean = false,
)

/** Texto con los posibles secretos sustituidos por su máscara (misma longitud: las líneas y columnas no cambian). */
class RedactionResult(val text: String, val secrets: List<DetectedSecret>)

/**
 * Oculta los posibles secretos de un archivo de texto antes de que nada salga del proceso aislado
 * (§8.4 de la arquitectura): la copia del código, los informes y la pantalla solo ven la versión enmascarada.
 *
 * Reconoce formatos conocidos de credenciales (claves de nube, tokens de servicios, claves privadas, JWT,
 * contraseñas en cadenas de conexión) y valores asignados a nombres sensibles (`password`, `apiKey`,
 * `client_secret`…). Usa RE2/J, de tiempo lineal, así que un archivo hostil no puede bloquearlo. Los patrones no
 * usan repeticiones acotadas grandes (`{1,500}`): RE2/J las compila con un anidamiento que desborda la pila de
 * los hilos de Android; los límites de longitud se comprueban en el código.
 *
 * Es deliberadamente generoso: ocultar de más solo afecta a la lectura del código; ocultar de menos expondría
 * un secreto. La detección completa de secretos, con severidad y certeza, es parte de la auditoría (Fase 6).
 */
object SecretRedactor {

    const val MASK: Char = RecordValidator.MASK_CHAR

    private class TokenRule(
        val type: SecretType,
        /** Si ninguno aparece en el texto, la regla no se evalúa (ahorra pasadas sobre archivos grandes). */
        val triggers: List<String>,
        regex: String,
        val accept: (String) -> Boolean = { true },
    ) {
        val pattern: Pattern = Pattern.compile(regex)
    }

    private val TOKEN_RULES = listOf(
        TokenRule(SecretType.AWS_ACCESS_KEY, listOf("AKIA", "ASIA", "ABIA", "ACCA"), """\b((?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16})\b"""),
        TokenRule(
            SecretType.AWS_SECRET_KEY,
            listOf("ecret_access_key", "ecretAccessKey", "ECRET_ACCESS_KEY", "ecret_Access_Key"),
            """(?i)aws_?secret_?access_?key["']?\s*[:=]\s*["']?([A-Za-z0-9/+=]{40})(?:[^A-Za-z0-9/+=]|$)""",
        ),
        TokenRule(
            SecretType.GITHUB_TOKEN,
            listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"),
            """\b((?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})""",
        ),
        TokenRule(SecretType.GITLAB_TOKEN, listOf("glpat-"), """\b(glpat-[A-Za-z0-9_\-]{20,})"""),
        TokenRule(SecretType.GOOGLE_API_KEY, listOf("AIza"), """\b(AIza[0-9A-Za-z_\-]{35})"""),
        TokenRule(SecretType.GOOGLE_OAUTH_SECRET, listOf("GOCSPX-"), """\b(GOCSPX-[A-Za-z0-9_\-]{28})"""),
        TokenRule(SecretType.SLACK_TOKEN, listOf("xox"), """\b(xox[abposr]-[A-Za-z0-9\-]{10,})"""),
        TokenRule(SecretType.SLACK_WEBHOOK, listOf("hooks.slack.com"), """(https://hooks\.slack\.com/services/[A-Za-z0-9_/]{20,})"""),
        TokenRule(SecretType.STRIPE_KEY, listOf("sk_live_", "sk_test_", "rk_live_", "rk_test_"), """\b((?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,})"""),
        TokenRule(SecretType.SENDGRID_KEY, listOf("SG."), """\b(SG\.[A-Za-z0-9_\-]{22}\.[A-Za-z0-9_\-]{43})"""),
        TokenRule(SecretType.ANTHROPIC_KEY, listOf("sk-ant-"), """\b(sk-ant-[A-Za-z0-9_\-]{20,})"""),
        TokenRule(
            SecretType.OPENAI_KEY,
            listOf("sk-"),
            """\b(sk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_\-]{20,})""",
            accept = { it.count(Char::isDigit) >= 2 && it.count(Char::isLetter) >= 6 && !it.startsWith("sk-ant-") },
        ),
        TokenRule(SecretType.JWT, listOf("eyJ"), """\b(eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,})"""),
        TokenRule(SecretType.FIREBASE_SERVER_KEY, listOf("AAAA"), """\b(AAAA[A-Za-z0-9_\-]{7}:[A-Za-z0-9_\-]{100,})"""),
        TokenRule(
            SecretType.CONNECTION_STRING_PASSWORD,
            listOf("://"),
            """\b[a-zA-Z][a-zA-Z0-9+.\-]+://[^\s:/@"'<>]+:([^\s@/"'<>]+)@""",
            accept = { !isReference(it) },
        ),
    )

    /**
     * Valor entre comillas asignado a un nombre: `password = "…"`, `"apiKey": "…"`, `'secret' => '…'`, `API_KEY: "…"`,
     * también con anotación de tipo (`val apiKey: String = "…"`, `const token: string = "…"`).
     */
    private val QUOTED_ASSIGNMENT = Pattern.compile(
        """(?:^|[^A-Za-z0-9_.\-])([A-Za-z][A-Za-z0-9_.\-]*)["']?(?:\s*:\s*[A-Za-z_][A-Za-z0-9_.<>\[\]?|]*)?\s*(?:=|:|=>|:=)\s*[rRbBuUfF@]?(?:"([^"\r\n]+)"|'([^'\r\n]+)')""",
    )

    /** Valor sin comillas en archivos de configuración: `DB_PASSWORD=…`, `api_key: …`, `export TOKEN=…`. */
    private val UNQUOTED_ASSIGNMENT = Pattern.compile(
        """^[ \t]*(?:export[ \t]+)?([A-Za-z][A-Za-z0-9_.\-]*)[ \t]*[=:][ \t]*([^\s"'][^\r\n]*)$""",
    )

    /** Recursos XML: `<string name="api_key">…</string>`. */
    private val XML_ELEMENT = Pattern.compile(
        """(?i)<(?:string|item|entry|value|add|param)\b[^>]*?\b(?:name|key)\s*=\s*["']([^"'\r\n]+)["'][^>]*>([^<\r\n]+)</""",
    )

    /** .NET: `<add key="ApiKey" value="…"/>`. */
    private val XML_ADD = Pattern.compile(
        """(?i)<add\b[^>]*?\bkey\s*=\s*["']([^"'\r\n]+)["'][^>]*?\bvalue\s*=\s*["']([^"'\r\n]+)["']""",
    )

    /**
     * Claves de firma escritas como argumento literal: `jwt.sign(datos, 'clave')`, `jwt.encode(p, "clave", …)`,
     * `Algorithm.HMAC256("clave")`, `Keys.hmacShaKeyFor("clave".getBytes())`, `setSigningKey("clave")`,
     * `cookieParser('clave')`, `new SymmetricSecurityKey(Encoding.UTF8.GetBytes("clave"))`.
     */
    private val SIGNING_TRIGGERS = listOf("jwt.", "JWT.", "jsonwebtoken.", "HMAC", "hmacShaKeyFor", "SigningKey", "signWith", "cookieParser", "SymmetricSecurityKey")
    private val SIGNING_SECOND_ARG = Pattern.compile(
        """\b(?:jwt|JWT|jsonwebtoken)\.(?:sign|verify|encode|decode)\s*\(\s*[^"'\s,)][^\r\n]*?,\s*[rRbB]?["']([^"'\r\n]{4,})["']\s*[,)]""",
    )
    private val SIGNING_FIRST_ARG = Pattern.compile(
        """(?:\bHMAC(?:256|384|512)|\bhmacShaKeyFor|\bsetSigningKey|\bcookieParser|SymmetricSecurityKey\s*\(\s*Encoding\.[A-Za-z0-9]+\.GetBytes)\s*\(\s*[rRbB@]?["']([^"'\r\n]{4,})["']""",
    )
    private val SIGNING_WITH = Pattern.compile("""\bsignWith\s*\(\s*SignatureAlgorithm\.[A-Z0-9]+\s*,\s*["']([^"'\r\n]{4,})["']""")
    private val NOT_SIGNING_VALUES = setOf("hs256", "hs384", "hs512", "rs256", "rs384", "rs512", "es256", "es384", "none", "utf-8", "utf8", "base64", "hex")

    private val ASSIGNMENT_TRIGGERS = listOf("pass", "pwd", "secret", "key", "token", "credential", "bearer", "auth")

    private val PASSWORD_WORDS = setOf("password", "passwd", "passphrase", "pwd", "pass", "pw", "contrasena", "contraseña", "clave")
    private val SECRET_WORDS = setOf("secret", "token", "credential", "credentials", "apikey", "authorization", "bearer", "secretkey", "privatekey", "accesskey")
    private val KEY_QUALIFIERS = setOf("api", "secret", "access", "private", "encryption", "signing", "master", "license", "client", "app", "auth", "service")
    private val SECRET_QUALIFIED = setOf("secret", "token") // client_secret, auth_token, access_token…

    /** Valores que no son secretos: vacíos lógicos y etiquetas de interfaz habituales ("Contraseña", "Password"…). */
    private val NOT_SECRET_VALUES = setOf(
        "true", "false", "null", "none", "nil", "yes", "no", "undefined", "empty", "required", "optional", "string",
        "password", "passwort", "senha", "contraseña", "contrasena", "clave", "secret", "secreto", "token", "pin", "apikey", "api_key",
    )

    private val CONFIG_EXTENSIONS = setOf("env", "properties", "ini", "cfg", "conf", "toml", "yaml", "yml", "npmrc", "pypirc", "netrc", "gitconfig")
    private val XML_EXTENSIONS = setOf("xml", "config", "plist", "xaml", "resx", "csproj")

    private class Hit(val start: Int, val end: Int, val type: SecretType)

    /**
     * @param fingerprintKey clave local para la huella de cada secreto (HMAC-SHA256). El valor se usa solo aquí,
     * dentro del proceso aislado, y no sale de esta función.
     */
    fun redact(text: String, fileName: String, fingerprintKey: ByteArray? = null): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, emptyList())
        val hits = mutableListOf<Hit>()
        privateKeys(text, hits)
        for (rule in TOKEN_RULES) {
            if (rule.triggers.none { text.contains(it) }) continue
            val m = rule.pattern.matcher(text)
            while (m.find()) {
                val value = m.group(1) ?: continue
                if (rule.accept(value)) hits += Hit(m.start(1), m.end(1), rule.type)
            }
        }
        assignments(text, fileName.lowercase(), hits)
        if (SIGNING_TRIGGERS.any { text.contains(it) }) signingKeys(text, hits)
        if (hits.isEmpty()) return RedactionResult(text, emptyList())

        val kept = resolveOverlaps(hits)
        val lineStarts = lineStarts(text)
        val out = StringBuilder(text)
        val secrets = kept.map { hit ->
            val value = text.substring(hit.start, hit.end)
            out.replace(hit.start, hit.end, maskInText(value, hit.type))
            DetectedSecret(
                hit.type, lineOf(lineStarts, hit.start), value.length, maskForRecord(value, hit.type),
                fingerprintKey?.let { fingerprint(it, value) }, isPlaceholder(value),
            )
        }
        return RedactionResult(out.toString(), secrets)
    }

    /** Huella del secreto: los primeros 16 bytes de HMAC-SHA256(clave, valor), en hexadecimal. */
    fun fingerprint(key: ByteArray, value: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Valores de ejemplo, de marcador o de prueba evidentes (no se ocultan, se rebajan). */
    fun isPlaceholder(value: String): Boolean {
        val v = value.lowercase()
        if (PLACEHOLDER_WORDS.any { v.contains(it) } || v in KNOWN_PUBLIC_VALUES) return true
        if (v.startsWith("<") && v.endsWith(">") || v.startsWith("[") && v.endsWith("]")) return true
        // Muy pocos caracteres distintos: "xxxxxxxx", "12341234", "abcabcabc".
        return v.length >= 6 && v.toSet().size <= 3
    }

    /** Valores públicos y conocidos: la contraseña de los almacenes de depuración de Android es "android". */
    private val KNOWN_PUBLIC_VALUES = setOf("android")

    private val PLACEHOLDER_WORDS = listOf(
        "changeme", "change_me", "change-me", "changethis", "your_", "your-", "yourkey", "yourapi", "yoursecret", "yourpassword", "example",
        "sample", "dummy", "placeholder", "xxxx", "****", "fake", "todo", "replace", "insert_", "password123", "secret123", "mysecret",
        "foobar", "lorem", "redacted", "notasecret", "not_a_secret", "test_key", "testkey", "testsecret", "testpassword",
    )

    /** Versión enmascarada para mostrar en informes y fichas (corta). */
    fun maskForRecord(value: String, type: SecretType): String {
        if (type == SecretType.PRIVATE_KEY || value.length < 12) return MASK.toString().repeat(minOf(value.length, 12).coerceAtLeast(1))
        return visible(value.take(4)) + MASK.toString().repeat(minOf(value.length - 6, 12)) + visible(value.takeLast(2))
    }

    /** Sustituto del secreto dentro del texto: misma longitud para no mover líneas ni columnas. */
    private fun maskInText(value: String, type: SecretType): String {
        if (type == SecretType.PRIVATE_KEY) {
            return buildString(value.length) { value.forEach { append(if (it == '\n' || it == '\r') it else MASK) } }
        }
        if (value.length < 12) return MASK.toString().repeat(value.length)
        return visible(value.take(4)) + MASK.toString().repeat(value.length - 6) + visible(value.takeLast(2))
    }

    /** Solo se muestran caracteres ASCII visibles; cualquier otro se enmascara. */
    private fun visible(part: String): String = part.map { if (it in '!'..'~' && it != MASK) it else MASK }.joinToString("")

    private fun privateKeys(text: String, hits: MutableList<Hit>) {
        var from = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN ", from)
            if (begin < 0) return
            val headerEnd = text.indexOf("-----", begin + 11)
            if (headerEnd < 0 || headerEnd - begin > 80) {
                from = begin + 11
                continue
            }
            val label = text.substring(begin + 11, headerEnd)
            from = headerEnd + 5
            if (!label.contains("PRIVATE KEY")) continue
            val bodyStart = headerEnd + 5
            val end = text.indexOf("-----END", bodyStart).let { if (it < 0) minOf(text.length, bodyStart + MAX_UNTERMINATED_KEY) else it }
            if (end > bodyStart) hits += Hit(bodyStart, end, SecretType.PRIVATE_KEY)
            from = end
        }
    }

    private fun assignments(text: String, lowerName: String, hits: MutableList<Hit>) {
        val ext = lowerName.substringAfterLast('.', "")
        val configFile = ext in CONFIG_EXTENSIONS || lowerName.startsWith(".env") || lowerName.endsWith(".env")
        val xmlFile = ext in XML_EXTENSIONS
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, nl)
            val lower = line.lowercase()
            if (ASSIGNMENT_TRIGGERS.any { lower.contains(it) }) {
                scanLine(line, lineStart, QUOTED_ASSIGNMENT, hits) { m -> if (m.group(2) != null) 2 else 3 }
                if (configFile) scanLine(line, lineStart, UNQUOTED_ASSIGNMENT, hits) { 2 }
                if (xmlFile) {
                    scanLine(line, lineStart, XML_ELEMENT, hits) { 2 }
                    scanLine(line, lineStart, XML_ADD, hits) { 2 }
                }
            }
            if (nl >= text.length) break
            lineStart = nl + 1
        }
    }

    private fun signingKeys(text: String, hits: MutableList<Hit>) {
        for (pattern in listOf(SIGNING_SECOND_ARG, SIGNING_FIRST_ARG, SIGNING_WITH)) {
            val m = pattern.matcher(text)
            while (m.find()) {
                val value = m.group(1) ?: continue
                if (value.lowercase() in NOT_SIGNING_VALUES || !looksLikeSecretValue(value)) continue
                hits += Hit(m.start(1), m.end(1), SecretType.SECRET)
            }
        }
    }

    private inline fun scanLine(line: String, offset: Int, pattern: Pattern, hits: MutableList<Hit>, valueGroup: (com.google.re2j.Matcher) -> Int) {
        val m = pattern.matcher(line)
        while (m.find()) {
            val key = m.group(1)?.takeIf { it.length <= MAX_KEY_LENGTH } ?: continue
            val group = valueGroup(m)
            val raw = m.group(group) ?: continue
            val type = sensitiveKind(key) ?: continue
            // Sin comillas: se corta un comentario final ("valor  # comentario") y los espacios.
            var start = m.start(group)
            var value = raw
            if (pattern === UNQUOTED_ASSIGNMENT) {
                val comment = Regexes.trailingComment(value)
                value = value.substring(0, comment).trimEnd()
            }
            val lead = value.length - value.trimStart().length
            start += lead
            value = value.trim()
            if (!looksLikeSecretValue(value)) continue
            hits += Hit(offset + start, offset + start + value.length, type)
        }
    }

    /** Si el nombre termina en una palabra sensible, el tipo de secreto; si no, `null`. */
    internal fun sensitiveKind(key: String): SecretType? {
        val words = splitWords(key)
        if (words.isEmpty()) return null
        val last = words.last()
        val previous = words.getOrNull(words.size - 2)
        return when {
            last in PASSWORD_WORDS -> SecretType.PASSWORD
            last in SECRET_WORDS -> SecretType.SECRET
            last == "key" && previous in KEY_QUALIFIERS -> SecretType.SECRET
            last in SECRET_QUALIFIED -> SecretType.SECRET
            else -> null
        }
    }

    /** Divide un identificador en palabras en minúsculas: `dbPassword` → [db, password], `API_KEY` → [api, key]. */
    internal fun splitWords(key: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString().lowercase()
            current.setLength(0)
        }
        for (i in key.indices) {
            val ch = key[i]
            when {
                ch == '_' || ch == '-' || ch == '.' || ch == ':' -> flush()
                ch.isUpperCase() && i > 0 && key[i - 1].isLowerCase() -> {
                    flush()
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        flush()
        return words
    }

    /**
     * Un valor con espacios es texto natural (etiquetas de interfaz como "Contraseña actual"), no una credencial:
     * los tokens y claves no llevan espacios. Las contraseñas con espacios no se detectan por asignación; es una
     * limitación aceptada a cambio de no ocultar los textos de la interfaz.
     */
    private fun looksLikeSecretValue(value: String): Boolean {
        if (value.length < 4) return false
        if (value.any { it.isWhitespace() }) return false
        if (value.lowercase().trimEnd('.', ':', '…') in NOT_SECRET_VALUES) return false
        if (isReference(value)) return false
        if (value.toSet().size == 1) return false
        return true
    }

    /** Referencias a variables o plantillas: no contienen el secreto. */
    private fun isReference(value: String): Boolean {
        val v = value.trim()
        return v.startsWith("\${") || v.startsWith("$(") || v.startsWith("{{") || v.startsWith("%(") || v.startsWith("%{") ||
            v.startsWith("#{") || v.startsWith("<%") || v.startsWith("@string/") || v.startsWith("@{") ||
            (v.startsWith("$") && v.length > 1 && (v[1].isLetter() || v[1] == '_')) ||
            (v.startsWith("%") && v.endsWith("%") && v.length > 2) ||
            v.startsWith("process.env") || v.startsWith("os.environ") || v.startsWith("System.getenv") || v.startsWith("env(")
    }

    /** Se queda con un único resultado por zona: el que empieza antes y, a igualdad, el más largo. */
    private fun resolveOverlaps(hits: List<Hit>): List<Hit> {
        val sorted = hits.sortedWith(compareBy<Hit> { it.start }.thenByDescending { it.end - it.start })
        val kept = mutableListOf<Hit>()
        for (hit in sorted) {
            val last = kept.lastOrNull()
            if (last != null && hit.start < last.end) {
                if (hit.end > last.end) kept[kept.size - 1] = Hit(last.start, hit.end, last.type)
                continue
            }
            kept += hit
        }
        return kept
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { i, ch -> if (ch == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    private fun lineOf(starts: IntArray, offset: Int): Int {
        val i = starts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    /** Nombres más largos no son identificadores reales: se ignoran. */
    private const val MAX_KEY_LENGTH = 64

    /** Hasta dónde se oculta una clave privada sin marca de final. */
    private const val MAX_UNTERMINATED_KEY = 8_192
}

private object Regexes {
    /** Posición donde empieza un comentario final (" #" o " ;"), o el final del valor. */
    fun trailingComment(value: String): Int {
        for (i in 1 until value.length) {
            if ((value[i] == '#' || value[i] == ';') && value[i - 1].isWhitespace()) return i
        }
        return value.length
    }
}
