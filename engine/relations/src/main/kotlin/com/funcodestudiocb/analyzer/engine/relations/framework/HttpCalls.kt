package com.funcodestudiocb.analyzer.engine.relations.framework

/** Llamadas HTTP del código cliente: método, URL (con sus partes dinámicas marcadas) y campos enviados. */
internal object HttpCalls {

    private val JS = setOf("javascript", "typescript", "tsx", "vue", "svelte", "html")
    private val METHODS = mapOf("get" to "GET", "post" to "POST", "put" to "PUT", "patch" to "PATCH", "delete" to "DELETE", "head" to "HEAD", "options" to "OPTIONS")

    fun extract(src: Src, out: FrameworkFacts) {
        val consts = constants(src)
        when (src.language) {
            in JS -> {
                javascript(src, out, consts)
                if (src.language == "html") forms(src, out)
            }
            "python" -> python(src, out, consts)
            "dart" -> dart(src, out, consts)
            "java", "kotlin" -> jvm(src, out, consts)
            "csharp" -> csharp(src, out, consts)
            "php" -> php(src, out, consts)
            "gdscript" -> gdscript(src, out, consts)
            "go" -> go(src, out, consts)
            "swift" -> swift(src, out, consts)
        }
        if (src.language == "php" || src.language == "vue" || src.language == "svelte") forms(src, out)
    }

    // ------------------------------------------------------------------ utilidades comunes

    /**
     * Constantes con una URL o ruta en el mismo archivo (`const API = 'http://…'`, `val BASE = "/api"`): sustituyen al
     * nombre cuando aparece en una URL.
     */
    private fun constants(src: Src): Map<String, String> {
        val map = HashMap<String, String>()
        for (q in listOf("\"", "'", "`")) {
            for (start in listOf("${q}http", "$q/")) {
                src.each(start) { at ->
                    if (map.size >= 200) return@each
                    val name = Routes.assignedName(src, at) ?: return@each
                    val lit = src.literal(at) ?: return@each
                    if (lit.value.length in 1..300) map.putIfAbsent(name, lit.value)
                }
            }
        }
        return map
    }

    /**
     * URL de una expresión: cadenas, plantillas y concatenaciones; lo que no es literal se marca como [DYNAMIC].
     * `null` si no tiene ninguna parte literal.
     */
    fun urlOf(src: Src, r: IntRange, consts: Map<String, String>, depth: Int = 0): String? {
        if (depth > 2) return null
        val start = src.skipWs(r.first)
        if (start > r.last) return null
        val text = src.text
        // Envoltorios: Uri.parse(x), URI.create(x), new URL(x), url(x), String.format(x, …), Uri.https(host, path)
        for (w in WRAPPERS) {
            if (text.startsWith(w, start)) {
                val open = start + w.length - 1
                val args = src.args(open) ?: return null
                val first = args.firstOrNull() ?: return null
                if (w == "Uri.https(" || w == "Uri.http(") {
                    val host = src.literalArg(first)?.value ?: DYNAMIC
                    val path = args.getOrNull(1)?.let { urlOf(src, it, consts, depth + 1) } ?: ""
                    return (if (w == "Uri.https(") "https://" else "http://") + host + path
                }
                val inner = urlOf(src, first, consts, depth + 1) ?: return null
                return if (w == "String.format(") Regexes.printf(inner) else inner
            }
        }
        val separator = if (src.language == "php") '.' else '+'
        val pieces = src.splitTop(start, r.last + 1, separator)
        if (pieces.isEmpty()) return null
        val sb = StringBuilder()
        var literal = false
        for (p in pieces) {
            val ps = src.skipWs(p.first)
            val lit = src.literal(ps)
            if (lit != null && src.skipWs(lit.end) > p.last) {
                sb.append(lit.value)
                literal = true
                continue
            }
            // Formato de Python: '/x/{}'.format(y), '/x/%s' % y
            if (lit != null && text.startsWith(".format(", src.skipWs(lit.end))) {
                sb.append(Regexes.braces(lit.value))
                literal = true
                continue
            }
            if (lit != null && text.startsWith("%", src.skipWs(lit.end))) {
                sb.append(Regexes.printf(lit.value))
                literal = true
                continue
            }
            val word = src.slice(p)
            val constant = consts[word] ?: consts[word.substringAfterLast('.')].takeIf { word.startsWith("this.") || word.startsWith("self.") || word.contains("::") }
            if (constant != null) {
                sb.append(constant)
                literal = true
            } else {
                sb.append(DYNAMIC)
            }
        }
        if (!literal) return null
        return collapse(sb.toString())
    }

    private fun collapse(url: String): String {
        val sb = StringBuilder()
        for (c in url) if (!(c == DYNAMIC[0] && sb.endsWith(DYNAMIC))) sb.append(c)
        return sb.toString()
    }

    private val WRAPPERS = listOf(
        "Uri.parse(", "Uri.tryParse(", "Uri.https(", "Uri.http(", "URI.create(", "URI(", "URL(", "new URL(", "new Uri(", "url(", "String.format(",
        "encodeURI(", "Url(", "HttpUrl.parse(",
    )

    /** Campos enviados: claves de un objeto literal, de `JSON.stringify({…})`, `jsonEncode({…})`, `json={…}`… */
    private fun fieldsOf(src: Src, r: IntRange?): List<String> {
        if (r == null) return emptyList()
        var k = src.skipWs(r.first)
        val text = src.text
        for (w in listOf("JSON.stringify(", "jsonEncode(", "json.encode(", "json.dumps(", "JsonSerializer.Serialize(", "new FormData(")) {
            if (text.startsWith(w, k)) k = src.skipWs(k + w.length)
        }
        for (w in listOf("mapOf(", "hashMapOf(", "Map.of(", "dict(", "array(", "new { ", "new {")) {
            if (text.startsWith(w, k)) {
                val open = if (w.startsWith("new")) text.indexOf('{', k) else k + w.length - 1
                return if (w.startsWith("new")) src.objectKeys(open) else src.args(open)?.mapNotNull { a ->
                    val s = src.skipWs(a.first)
                    src.literal(s)?.value ?: src.wordAt(s)
                }.orEmpty().filter { it.length in 1..60 }.take(20)
            }
        }
        val c = text.getOrNull(k)
        return if (c == '{' || c == '[') src.objectKeys(k) else emptyList()
    }

    /** Valor literal de la opción `key` dentro de un objeto `{ key: 'x' }` o de argumentos con nombre. */
    private fun option(src: Src, r: IntRange?, vararg keys: String): IntRange? {
        if (r == null) return null
        val k = src.skipWs(r.first)
        if (src.text.getOrNull(k) != '{') return null
        val end = src.close(k)
        if (end < 0) return null
        return Routes.namedArg(src, src.splitTop(k + 1, end, ','), *keys)
    }

    private fun methodLiteral(src: Src, r: IntRange?): String? = r?.let { src.literalArg(it)?.value?.uppercase()?.takeIf { m -> m in METHODS.values } }

    /** URL base + ruta relativa, con una sola barra entre ellas. */
    fun joinUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

    private fun add(out: FrameworkFacts, src: Src, at: Int, method: String, url: String?, client: String, fields: List<String> = emptyList(), retrofit: Boolean = false) {
        if (url == null || url.isBlank() || url == DYNAMIC) return
        // Solo lo que parece una URL o una ruta (no `get('nombre')` de un mapa o una petición).
        if (!url.contains('/') && !Regexes.isScriptPath(url) && !retrofit) return
        if (out.calls.size >= MAX_CALLS) return
        out.calls += HttpCallFact(method, url, src.line(at), client, fields, retrofit)
    }

    // ------------------------------------------------------------------ JavaScript / TypeScript

    private fun javascript(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        val text = src.text
        // fetch y similares: fetch(url, { method, body })
        for (fn in listOf("fetch(", "\$fetch(", "useFetch(", "ofetch(", "useSWR(", "ky(")) {
            src.each(fn) { at ->
                if (at > 0 && (Src.isIdent(text[at - 1]) || text[at - 1] == '$')) return@each
                if (at > 0 && text[at - 1] == '.' && !text.startsWith("window.", maxOf(0, at - 7)) && !text.startsWith("globalThis.", maxOf(0, at - 11))) return@each
                val args = src.args(at + fn.length - 1) ?: return@each
                val url = args.firstOrNull()?.let { urlOf(src, it, consts) }
                val opts = args.getOrNull(1)
                val method = methodLiteral(src, option(src, opts, "method")) ?: "GET"
                val fields = fieldsOf(src, option(src, opts, "body"))
                add(out, src, at, method, url, fn.removeSuffix("(").removePrefix("\$").ifEmpty { "fetch" }, fields)
            }
        }
        // Instancias de axios con URL base: const api = axios.create({ baseURL: '/api' })
        val instances = HashMap<String, String?>()
        src.each("axios.create(") { at ->
            val name = Routes.assignedName(src, at) ?: return@each
            val args = src.args(at + "axios.create".length)
            val base = option(src, args?.firstOrNull(), "baseURL", "baseUrl")?.let { urlOf(src, it, consts) }
            instances[name] = base
        }
        src.each("axios.defaults.baseURL") { at ->
            val eq = src.skipWs(at + "axios.defaults.baseURL".length)
            if (text.getOrNull(eq) == '=' && text.getOrNull(eq + 1) != '=') {
                val lit = src.literal(eq + 1)
                if (lit != null && !lit.dynamic) out.baseUrls += "axios:${lit.value}" to src.line(at)
            }
        }
        val angular = src.has("HttpClient")
        val receivers = buildList {
            add("axios" to null as String?)
            instances.forEach { (n, b) -> add(n to b) }
            if (angular) {
                add("this.http" to null)
                add("this.httpClient" to null)
                add("http" to null)
                add("httpClient" to null)
            }
            add("\$http" to null)
            add("this.\$http" to null)
            add("this.\$axios" to null)
            add("\$axios" to null)
            add("superagent" to null)
            add("ky" to null)
        }
        for ((recv, base) in receivers) {
            src.each("$recv.") { at ->
                if (at > 0 && (Src.isIdent(text[at - 1]) || text[at - 1] == '$' || (text[at - 1] == '.' && !recv.startsWith("this.")))) return@each
                val nameStart = at + recv.length + 1
                val verb = src.wordAt(nameStart) ?: return@each
                var open = src.skipWs(nameStart + verb.length)
                if (text.getOrNull(open) == '<') open = src.skipWs(src.close(open).let { if (it < 0) return@each else it + 1 })
                if (text.getOrNull(open) != '(') return@each
                val args = src.args(open) ?: return@each
                val client = if (recv.contains("http") && angular) "HttpClient" else recv.removePrefix("this.").removePrefix("\$")
                when {
                    verb in METHODS -> {
                        val path = args.firstOrNull()?.let { urlOf(src, it, consts) }
                        val url = if (base != null && path != null && !path.contains("://")) joinUrl(base, path) else path
                        val fields = if (verb in setOf("post", "put", "patch")) fieldsOf(src, args.getOrNull(1)) else emptyList()
                        add(out, src, at, METHODS.getValue(verb), url, client, fields)
                    }
                    verb == "request" -> {
                        val opts = args.firstOrNull()
                        if (angular && recv.contains("http")) {
                            add(out, src, at, methodLiteral(src, args.firstOrNull()) ?: "ANY", args.getOrNull(1)?.let { urlOf(src, it, consts) }, client)
                        } else {
                            val url = option(src, opts, "url")?.let { urlOf(src, it, consts) }
                            add(out, src, at, methodLiteral(src, option(src, opts, "method")) ?: "GET", url, client, fieldsOf(src, option(src, opts, "data")))
                        }
                    }
                }
            }
        }
        // axios({ method, url, data })
        src.eachWord("axios(") { at ->
            val args = src.args(at + 5) ?: return@eachWord
            val opts = args.firstOrNull() ?: return@eachWord
            val url = option(src, opts, "url")?.let { urlOf(src, it, consts) } ?: urlOf(src, opts, consts)
            add(out, src, at, methodLiteral(src, option(src, opts, "method")) ?: methodLiteral(src, option(src, args.getOrNull(1), "method")) ?: "GET", url, "axios", fieldsOf(src, option(src, opts, "data")))
        }
        // jQuery
        for ((fn, method) in listOf("$.get(" to "GET", "$.post(" to "POST", "$.getJSON(" to "GET", "jQuery.get(" to "GET", "jQuery.post(" to "POST")) {
            src.each(fn) { at ->
                val args = src.args(at + fn.length - 1) ?: return@each
                add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "jQuery", if (method == "POST") fieldsOf(src, args.getOrNull(1)) else emptyList())
            }
        }
        for (fn in listOf("$.ajax(", "jQuery.ajax(")) {
            src.each(fn) { at ->
                val args = src.args(at + fn.length - 1) ?: return@each
                val opts = args.lastOrNull()
                val url = option(src, opts, "url")?.let { urlOf(src, it, consts) } ?: args.takeIf { it.size > 1 }?.first()?.let { urlOf(src, it, consts) }
                add(out, src, at, methodLiteral(src, option(src, opts, "method", "type")) ?: "GET", url, "jQuery", fieldsOf(src, option(src, opts, "data")))
            }
        }
        src.each("new EventSource(") { at ->
            add(out, src, at, "GET", src.args(at + "new EventSource".length)?.firstOrNull()?.let { urlOf(src, it, consts) }, "EventSource")
        }
    }

    /** `<form action="/login" method="post">` en HTML, PHP y plantillas. */
    private fun forms(src: Src, out: FrameworkFacts) {
        val m = Regexes.FORM.matcher(src.text)
        var n = 0
        while (m.find() && n++ < 500) {
            if (!src.isCode(m.start())) continue
            val tag = m.group(0)
            val action = Regexes.attr(tag, "action") ?: continue
            if (action.isBlank() || action.startsWith("#") || action.startsWith("javascript:") || action.contains("{{") || action.contains("<?") || action.contains("{%")) continue
            val method = Regexes.attr(tag, "method")?.uppercase()?.takeIf { it in METHODS.values } ?: "GET"
            val fields = Regexes.formFields(src.text, m.end())
            add(out, src, m.start(), method, action, "formulario HTML", fields)
        }
    }

    // ------------------------------------------------------------------ Python

    private fun python(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        val receivers = mutableListOf("requests", "httpx")
        if (src.has("aiohttp") || src.has("httpx")) receivers += listOf("session", "client", "self.session", "self.client")
        for (recv in receivers) {
            src.each("$recv.") { at ->
                if (at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
                val verb = src.wordAt(at + recv.length + 1) ?: return@each
                val open = at + recv.length + 1 + verb.length
                if (src.text.getOrNull(open) != '(') return@each
                val args = src.args(open) ?: return@each
                when {
                    verb in METHODS -> {
                        val fields = fieldsOf(src, Routes.namedArg(src, args, "json", "data"))
                        add(out, src, at, METHODS.getValue(verb), args.firstOrNull()?.let { urlOf(src, it, consts) }, recv.substringAfterLast('.'), fields)
                    }
                    verb == "request" -> add(out, src, at, methodLiteral(src, args.firstOrNull()) ?: "ANY", args.getOrNull(1)?.let { urlOf(src, it, consts) }, recv)
                }
            }
        }
        src.eachWord("urlopen(") { at -> add(out, src, at, "GET", src.args(at + 7)?.firstOrNull()?.let { urlOf(src, it, consts) }, "urllib") }
    }

    // ------------------------------------------------------------------ Dart / Flutter

    private fun dart(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        val receivers = mutableListOf<String>()
        if (src.has("package:http/")) receivers += "http"
        if (src.has("dio")) receivers += listOf("dio", "_dio", "Dio()", "client", "_client", "api", "_api")
        for (recv in receivers) {
            src.each("$recv.") { at ->
                if (at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
                val verb = src.wordAt(at + recv.length + 1) ?: return@each
                val open = at + recv.length + 1 + verb.length
                if (src.text.getOrNull(open) != '(' || verb !in METHODS) return@each
                val args = src.args(open) ?: return@each
                val fields = fieldsOf(src, Routes.namedArg(src, args, "body", "data"))
                add(out, src, at, METHODS.getValue(verb), args.firstOrNull()?.let { urlOf(src, it, consts) }, if (recv == "http") "http" else "Dio", fields)
            }
        }
    }

    // ------------------------------------------------------------------ Java / Kotlin

    private fun jvm(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        val text = src.text
        // Retrofit: @GET("users/{id}") en una interfaz; JAX-RS usa @GET sin argumentos.
        if (src.has("retrofit")) {
            for ((ann, method) in listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT", "PATCH" to "PATCH", "DELETE" to "DELETE", "HEAD" to "HEAD")) {
                src.each("@$ann(") { at ->
                    if (!src.annotationAt(at)) return@each
                    val args = src.args(at + ann.length + 1) ?: return@each
                    val path = args.firstOrNull()?.let { a -> Routes.namedArg(src, args, "value")?.let { src.literalArg(it) } ?: src.literalArg(a) }?.value ?: return@each
                    add(out, src, at, method, path, "Retrofit", retrofit = true)
                }
            }
            src.each("@HTTP(") { at ->
                if (!src.annotationAt(at)) return@each
                val args = src.args(at + 5) ?: return@each
                val method = methodLiteral(src, Routes.namedArg(src, args, "method")) ?: "ANY"
                val path = Routes.namedArg(src, args, "path")?.let { src.literalArg(it)?.value } ?: return@each
                add(out, src, at, method, path, "Retrofit", retrofit = true)
            }
        }
        src.each(".baseUrl(") { at ->
            val lit = src.args(at + ".baseUrl".length)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
            if (!lit.dynamic) out.baseUrls += "retrofit:${lit.value}" to src.line(at)
        }
        // OkHttp: Request.Builder().url("…").post(…)
        src.each(".url(") { at ->
            val before = text.substring(maxOf(0, at - 60), at)
            if (!before.contains("Builder()")) return@each
            val args = src.args(at + 4) ?: return@each
            val chainEnd = text.indexOf(".build()", at).takeIf { it in at..at + 600 } ?: (at + 200).coerceAtMost(text.length)
            val chain = text.substring(at, chainEnd)
            val method = METHODS.entries.firstOrNull { (k, _) -> chain.contains(".$k(") }?.value ?: "GET"
            add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "OkHttp")
        }
        // Ktor, RestTemplate, WebClient, Volley
        if (src.has("io.ktor")) {
            for (recv in listOf("client", "httpClient", "HttpClient()")) {
                src.each("$recv.") { at ->
                    val verb = src.wordAt(at + recv.length + 1) ?: return@each
                    val open = at + recv.length + 1 + verb.length
                    if (text.getOrNull(open) != '(' || verb !in METHODS) return@each
                    add(out, src, at, METHODS.getValue(verb), src.args(open)?.firstOrNull()?.let { urlOf(src, it, consts) }, "Ktor")
                }
            }
        }
        for ((fn, method) in listOf(
            "getForObject(" to "GET", "getForEntity(" to "GET", "postForObject(" to "POST", "postForEntity(" to "POST", "postForLocation(" to "POST",
        )) {
            src.each(".$fn") { at -> add(out, src, at, method, src.args(at + fn.length)?.firstOrNull()?.let { urlOf(src, it, consts) }, "RestTemplate") }
        }
        src.each(".exchange(") { at ->
            val args = src.args(at + ".exchange".length) ?: return@each
            val method = args.getOrNull(1)?.let { Regexes.HTTP_METHOD_ENUM.matcher(src.slice(it)).let { m -> if (m.find()) m.group(1) else null } } ?: return@each
            add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "RestTemplate")
        }
        src.each(".uri(") { at ->
            val before = text.substring(maxOf(0, at - 20), at)
            val verb = METHODS.keys.firstOrNull { before.endsWith(".$it()") } ?: return@each
            add(out, src, at, METHODS.getValue(verb), src.args(at + 4)?.firstOrNull()?.let { urlOf(src, it, consts) }, "WebClient")
        }
        src.each("Request.Method.") { at ->
            val verb = src.wordAt(at + "Request.Method.".length) ?: return@each
            val method = verb.uppercase().takeIf { it in METHODS.values } ?: return@each
            val rest = src.skipWs(at + "Request.Method.".length + verb.length)
            if (text.getOrNull(rest) != ',') return@each
            val urlStart = rest + 1
            val end = src.splitTop(urlStart, minOf(text.length, urlStart + 400), ',').firstOrNull() ?: return@each
            add(out, src, at, method, urlOf(src, end, consts), "Volley")
        }
    }

    // ------------------------------------------------------------------ C#

    private fun csharp(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        val calls = listOf(
            "GetAsync" to "GET", "GetStringAsync" to "GET", "GetStreamAsync" to "GET", "GetByteArrayAsync" to "GET", "GetFromJsonAsync" to "GET",
            "PostAsync" to "POST", "PostAsJsonAsync" to "POST", "PutAsync" to "PUT", "PutAsJsonAsync" to "PUT", "PatchAsync" to "PATCH",
            "PatchAsJsonAsync" to "PATCH", "DeleteAsync" to "DELETE",
        )
        if (src.has("HttpClient") || src.has("Http.")) {
            for ((fn, method) in calls) {
                src.each(".$fn") { at ->
                    var open = at + 1 + fn.length
                    if (src.text.getOrNull(open) == '<') open = src.close(open).let { if (it < 0) return@each else it + 1 }
                    if (src.text.getOrNull(open) != '(') return@each
                    val args = src.args(open) ?: return@each
                    val fields = if (method != "GET" && method != "DELETE") fieldsOf(src, args.getOrNull(1)) else emptyList()
                    add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "HttpClient", fields)
                }
            }
            src.each("new HttpRequestMessage(") { at ->
                val args = src.args(at + "new HttpRequestMessage".length) ?: return@each
                val method = args.firstOrNull()?.let { Regexes.CS_HTTP_METHOD.matcher(src.slice(it)).let { m -> if (m.find()) m.group(1).uppercase() else null } } ?: "ANY"
                add(out, src, at, method, args.getOrNull(1)?.let { urlOf(src, it, consts) }, "HttpClient")
            }
        }
        for ((fn, method) in listOf("Get(" to "GET", "Post(" to "POST", "Put(" to "PUT", "Delete(" to "DELETE", "PostWwwForm(" to "POST")) {
            src.each("UnityWebRequest.$fn") { at ->
                add(out, src, at, method, src.args(at + "UnityWebRequest.".length + fn.length - 1)?.firstOrNull()?.let { urlOf(src, it, consts) }, "UnityWebRequest")
            }
        }
    }

    // ------------------------------------------------------------------ PHP

    private fun php(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        for ((verb, method) in METHODS) {
            src.each("Http::$verb(") { at ->
                val args = src.args(at + 6 + verb.length) ?: return@each
                add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "Laravel Http", fieldsOf(src, args.getOrNull(1)))
            }
            if (src.has("GuzzleHttp")) {
                src.each("->$verb(") { at ->
                    val args = src.args(at + 2 + verb.length) ?: return@each
                    add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "Guzzle")
                }
            }
        }
        if (src.has("GuzzleHttp")) {
            src.each("->request(") { at ->
                val args = src.args(at + "->request".length) ?: return@each
                add(out, src, at, methodLiteral(src, args.firstOrNull()) ?: "ANY", args.getOrNull(1)?.let { urlOf(src, it, consts) }, "Guzzle")
            }
        }
        src.eachWord("curl_init(") { at -> add(out, src, at, "ANY", src.args(at + 9)?.firstOrNull()?.let { urlOf(src, it, consts) }, "cURL") }
    }

    // ------------------------------------------------------------------ GDScript, Go, Swift

    private fun gdscript(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        src.each(".request(") { at ->
            val args = src.args(at + ".request".length) ?: return@each
            val method = args.getOrNull(2)?.let { Regexes.GD_METHOD.matcher(src.slice(it)).let { m -> if (m.find()) m.group(1) else null } } ?: "GET"
            add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "HTTPRequest")
        }
    }

    private fun go(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        for ((fn, method) in listOf("http.Get(" to "GET", "http.Post(" to "POST", "http.Head(" to "HEAD", "http.PostForm(" to "POST")) {
            src.each(fn) { at -> add(out, src, at, method, src.args(at + fn.length - 1)?.firstOrNull()?.let { urlOf(src, it, consts) }, "net/http") }
        }
        src.each("http.NewRequest(") { at ->
            val args = src.args(at + "http.NewRequest".length) ?: return@each
            add(out, src, at, methodLiteral(src, args.firstOrNull()) ?: "ANY", args.getOrNull(1)?.let { urlOf(src, it, consts) }, "net/http")
        }
    }

    private fun swift(src: Src, out: FrameworkFacts, consts: Map<String, String>) {
        src.each("AF.request(") { at ->
            val args = src.args(at + "AF.request".length) ?: return@each
            val method = Routes.namedArg(src, args, "method")?.let { r -> src.slice(r).removePrefix(".").uppercase().takeIf { it in METHODS.values } } ?: "GET"
            add(out, src, at, method, args.firstOrNull()?.let { urlOf(src, it, consts) }, "Alamofire")
        }
        src.each("URL(string:") { at ->
            val lit = src.literal(at + "URL(string:".length) ?: return@each
            if (lit.value.contains("://")) add(out, src, at, "GET", lit.value, "URLSession")
        }
    }

    private const val MAX_CALLS = 300
}
