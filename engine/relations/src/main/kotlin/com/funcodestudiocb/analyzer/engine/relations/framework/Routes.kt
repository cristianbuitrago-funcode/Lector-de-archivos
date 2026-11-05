package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.api.EndpointKind

/** Rutas del backend y del frontend declaradas en el código. */
internal object Routes {

    private val JS = setOf("javascript", "typescript", "tsx")
    private val VERBS = mapOf(
        "get" to "GET", "post" to "POST", "put" to "PUT", "patch" to "PATCH", "delete" to "DELETE", "del" to "DELETE",
        "all" to "ANY", "options" to "OPTIONS", "head" to "HEAD", "any" to "ANY",
    )

    fun extract(src: Src, out: FrameworkFacts) {
        when (src.language) {
            in JS -> {
                if (src.has("express") || src.has("Router(") || src.has("fastify") || src.has("Fastify") || src.has("new Hono") || src.has("koa")) express(src, out)
                if (src.has("@Controller")) nest(src, out)
                if (src.has(".setGlobalPrefix(")) globalPrefix(src, out)
                frontend(src, out)
            }
            "vue", "svelte" -> frontend(src, out)
            "python" -> {
                if (src.has("urlpatterns") || src.info.lower == "urls.py") django(src, out)
                pythonDecorators(src, out)
            }
            "php" -> if (src.info.lowerDirs.contains("routes") && src.has("Route::")) laravel(src, out)
            "java", "kotlin" -> if (src.has("Mapping") || src.has("@Path")) spring(src, out)
            "csharp" -> aspnet(src, out)
            "dart" -> if (src.has("GoRoute(") || src.has("routes:")) flutter(src, out)
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Une prefijos y rutas con una sola barra entre ellos. */
    fun joinPath(vararg parts: String): String {
        val segs = parts.flatMap { it.split('/') }.filter { it.isNotEmpty() }
        val trailing = parts.lastOrNull()?.let { it.length > 1 && it.endsWith("/") } == true
        return "/" + segs.joinToString("/") + if (trailing && segs.isNotEmpty()) "/" else ""
    }

    /** Nombre de la variable a la que se asigna la expresión que empieza en [at] (`const app = express()`). */
    fun assignedName(src: Src, at: Int): String? {
        val t = src.text
        var i = at - 1
        while (i >= 0 && t[i].isWhitespace()) i--
        if (i < 0 || t[i] != '=' || (i > 0 && t[i - 1] in "=!<>:+-*/")) {
            // Kotlin y Dart: `val x: Tipo = …` ya cubierto; C#: `var g = …`.
            return null
        }
        i--
        while (i >= 0 && t[i].isWhitespace()) i--
        // `const app: Express = express()`: se salta la anotación de tipo.
        val end = i
        while (i >= 0 && (Src.isIdent(t[i]) || t[i] == '$' || t[i] == '.')) i--
        val name = t.substring(i + 1, end + 1)
        if (name.isEmpty()) return null
        val before = run {
            var k = i
            while (k >= 0 && t[k].isWhitespace()) k--
            k
        }
        if (before >= 0 && t[before] == ':') {
            // Anotación de tipo: el nombre está antes de los dos puntos.
            var k = before - 1
            while (k >= 0 && t[k].isWhitespace()) k--
            val e = k
            while (k >= 0 && Src.isIdent(t[k])) k--
            return t.substring(k + 1, e + 1).takeIf { it.isNotEmpty() }
        }
        return name.substringAfterLast('.').takeIf { it.isNotEmpty() && it.first().let { c -> c.isLetter() || c == '_' || c == '$' } }
    }

    /** Referencia al código que atiende una ruta a partir de la expresión escrita. */
    fun handlerOf(src: Src, r: IntRange): HandlerRef? {
        var s = src.slice(r)
        if (s.isEmpty()) return null
        if (s.startsWith("async ")) return null
        if (s.startsWith("function") || s.startsWith("(") || s.contains("=>") || s.startsWith("lambda") || s.startsWith("[") && s.contains("function")) return null
        // Envoltorios de una sola llamada: asyncHandler(ctrl.list), wrap(handler)
        repeat(2) {
            val open = s.indexOf('(')
            if (open > 0 && s.endsWith(")") && s.substring(0, open).all { Src.isIdent(it) || it == '.' }) {
                val inner = s.substring(open + 1, s.length - 1).trim()
                if (inner.isNotEmpty() && inner.all { Src.isIdent(it) || it == '.' || it == '$' }) s = inner else return@repeat
            }
        }
        if (s.startsWith("require(")) {
            val lit = src.literal(src.text.indexOf("require(", r.first) + 8) ?: return null
            return HandlerRef(lit.value, null, HandlerRef.Style.MODULE)
        }
        if (!s.all { Src.isIdent(it) || it == '.' || it == '$' }) return null
        val base = s.substringBefore('.')
        val member = s.substringAfter('.', "").ifEmpty { null }
        return HandlerRef(base, member, HandlerRef.Style.BINDING)
    }

    private fun stringValue(src: Src, r: IntRange): String? = src.literalArg(r)?.takeIf { !it.dynamic }?.value

    /** Valor del argumento con nombre `name=` / `name:` / `name =` en una lista de argumentos. */
    fun namedArg(src: Src, args: List<IntRange>, vararg names: String): IntRange? {
        for (a in args) {
            val s = src.skipWs(a.first)
            val word = src.wordAt(s) ?: continue
            if (word !in names) continue
            var k = src.skipWs(s + word.length)
            if (k <= a.last && (src.text[k] == '=' || src.text[k] == ':') && src.text.getOrNull(k + 1) != '=') {
                k++
                return k..a.last
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Express, Fastify, Koa, Hono

    private fun express(src: Src, out: FrameworkFacts) {
        val groups = LinkedHashMap<String, Boolean>() // nombre → raíz
        for ((trigger, root) in listOf(
            "express()" to true, "express.Router(" to false, "Router(" to false, "new Router(" to false, "new Hono(" to true,
            "fastify(" to true, "Fastify(" to true, "polka()" to true,
        )) {
            src.each(trigger) { i ->
                if (i > 0 && (Src.isIdent(src.text[i - 1]) || src.text[i - 1] == '.') && !trigger.startsWith("new")) return@each
                assignedName(src, i)?.let { name -> if (name !in groups) groups[name] = root }
            }
        }
        val importsExpress = src.has("'express'") || src.has("\"express\"") || src.has("fastify") || src.has("hono") || src.has("koa-router") || src.has("@koa/router")
        if (!importsExpress && groups.isEmpty()) return
        if (importsExpress) {
            if ("app" !in groups) groups["app"] = true
            if ("router" !in groups) groups["router"] = false
        }
        groups.forEach { (name, root) -> out.groups += GroupFact(name, "", root, "Express") }
        val framework = when {
            src.has("fastify") || src.has("Fastify") -> "Fastify"
            src.has("new Hono") -> "Hono"
            src.has("koa") -> "Koa"
            else -> "Express"
        }
        for (g in groups.keys) {
            src.eachWord("$g.") { at ->
                val nameStart = at + g.length + 1
                val verb = src.wordAt(nameStart) ?: return@eachWord
                val open = src.skipWs(nameStart + verb.length)
                if (src.text.getOrNull(open) != '(') return@eachWord
                val args = src.args(open) ?: return@eachWord
                when {
                    verb in VERBS -> {
                        val path = args.firstOrNull()?.let { stringValue(src, it) } ?: return@eachWord
                        if (!(path.startsWith("/") || path == "*" || path.isEmpty())) return@eachWord
                        if (args.size < 2) return@eachWord
                        val handler = handlerOf(src, args.last())
                        out.routes += RouteFact(
                            EndpointKind.API, VERBS.getValue(verb), path, src.line(at), framework, handler,
                            handler?.toString(), group = g,
                        )
                    }
                    verb == "use" || verb == "register" || (verb == "route" && args.size == 2) -> {
                        val first = args.firstOrNull() ?: return@eachWord
                        val prefixLiteral = stringValue(src, first)
                        val (prefix, targetRange) = when {
                            verb == "register" -> {
                                val opts = args.getOrNull(1)
                                val p = opts?.let { o ->
                                    val ob = src.skipWs(o.first)
                                    if (src.text.getOrNull(ob) == '{') {
                                        val end = src.close(ob)
                                        if (end > 0) namedArg(src, src.splitTop(ob + 1, end, ','), "prefix")?.let { stringValue(src, it) } else null
                                    } else {
                                        null
                                    }
                                } ?: ""
                                p to first
                            }
                            prefixLiteral != null && args.size >= 2 -> prefixLiteral to args.last()
                            prefixLiteral == null -> "" to args.last()
                            else -> return@eachWord
                        }
                        val target = mountTarget(src, targetRange) ?: return@eachWord
                        out.mounts += MountFact(prefix, target, src.line(at), framework, g)
                    }
                    verb == "route" && args.size == 1 -> {
                        val path = stringValue(src, args[0]) ?: return@eachWord
                        var k = src.skipWs(src.close(open) + 1)
                        var guard = 0
                        while (k < src.text.length && src.text[k] == '.' && guard++ < 10) {
                            val v = src.wordAt(k + 1) ?: break
                            val o = src.skipWs(k + 1 + v.length)
                            if (src.text.getOrNull(o) != '(') break
                            val vArgs = src.args(o) ?: break
                            if (v in VERBS && vArgs.isNotEmpty()) {
                                val handler = handlerOf(src, vArgs.last())
                                out.routes += RouteFact(EndpointKind.API, VERBS.getValue(v), path, src.line(k), framework, handler, handler?.toString(), group = g)
                            }
                            k = src.skipWs(src.close(o) + 1)
                        }
                    }
                }
            }
        }
    }

    private fun mountTarget(src: Src, r: IntRange): HandlerRef? {
        val s = src.slice(r)
        val req = s.indexOf("require(")
        if (req >= 0) {
            val lit = src.literal(src.text.indexOf("require(", r.first) + 8) ?: return null
            return HandlerRef(lit.value, null, HandlerRef.Style.MODULE)
        }
        if (s.startsWith("import(")) {
            val lit = src.literal(src.text.indexOf("import(", r.first) + 7) ?: return null
            return HandlerRef(lit.value, null, HandlerRef.Style.MODULE)
        }
        return handlerOf(src, r)
    }

    // ------------------------------------------------------------------ NestJS

    private fun nest(src: Src, out: FrameworkFacts) {
        val classes = mutableListOf<Pair<Int, String>>() // posición → prefijo
        src.each("@Controller(") { at ->
            if (!src.annotationAt(at)) return@each
            val open = at + "@Controller".length
            val args = src.args(open) ?: return@each
            val prefix = args.firstOrNull()?.let { a ->
                stringValue(src, a) ?: run {
                    val ob = src.skipWs(a.first)
                    if (src.text.getOrNull(ob) == '{') {
                        val end = src.close(ob)
                        if (end > 0) namedArg(src, src.splitTop(ob + 1, end, ','), "path")?.let { stringValue(src, it) } else null
                    } else {
                        null
                    }
                }
            } ?: ""
            classes += at to prefix
        }
        if (classes.isEmpty()) return
        val className = Regexes.CLASS_NAME
        for ((verb, method) in listOf("Get" to "GET", "Post" to "POST", "Put" to "PUT", "Patch" to "PATCH", "Delete" to "DELETE", "All" to "ANY", "Head" to "HEAD", "Options" to "OPTIONS")) {
            src.each("@$verb(") { at ->
                if (!src.annotationAt(at)) return@each
                val owner = classes.lastOrNull { it.first < at } ?: return@each
                val open = at + verb.length + 1
                val args = src.args(open) ?: return@each
                val sub = args.firstOrNull()?.let { stringValue(src, it) } ?: if (args.isEmpty()) "" else return@each
                val methodName = memberAfter(src, src.close(open) + 1)
                val cls = className.matcher(src.text.substring(owner.first, minOf(src.text.length, owner.first + 400)))
                val clsName = if (cls.find()) cls.group(1) else null
                out.routes += RouteFact(
                    EndpointKind.API, method, joinPath(owner.second, sub), src.line(at), "NestJS",
                    handlerLabel = listOfNotNull(clsName, methodName).joinToString("."), group = "nest",
                )
            }
        }
    }

    /** `app.setGlobalPrefix('api')` de NestJS (en main.ts, sin controladores). */
    private fun globalPrefix(src: Src, out: FrameworkFacts) {
        src.each(".setGlobalPrefix(") { at ->
            val args = src.args(at + ".setGlobalPrefix".length) ?: return@each
            args.firstOrNull()?.let { stringValue(src, it) }?.let { out.globalPrefixes += GlobalPrefixFact("NestJS", joinPath(it), src.line(at)) }
        }
    }

    /** Nombre del método o función que sigue a unas anotaciones o decoradores. */
    fun memberAfter(src: Src, from: Int): String? {
        var k = src.skipWs(from)
        var guard = 0
        while (k < src.text.length && guard++ < 20) {
            val c = src.text[k]
            when {
                c == '@' || c == '[' -> {
                    // Otra anotación: @Algo(…) o [Atributo(…)]
                    if (c == '[') {
                        val end = src.close(k)
                        if (end < 0) return null
                        k = src.skipWs(end + 1)
                        continue
                    }
                    val w = src.wordAt(k + 1) ?: return null
                    k += 1 + w.length
                    while (k < src.text.length && src.text[k] == '.') k += 1 + (src.wordAt(k + 1)?.length ?: 0)
                    if (src.text.getOrNull(k) == '(') {
                        val end = src.close(k)
                        if (end < 0) return null
                        k = end + 1
                    }
                    k = src.skipWs(k)
                }
                Src.isIdent(c) -> {
                    val w = src.wordAt(k) ?: return null
                    var after = src.skipWs(k + w.length)
                    if (src.text.getOrNull(after) == '<') after = src.skipWs(skipGeneric(src, after))
                    if (src.text.getOrNull(after) == '(' && w !in MODIFIERS) return w
                    k = after
                }
                else -> k++
            }
        }
        return null
    }

    /** Posición después de `<…>` (tipos genéricos), acotado. */
    private fun skipGeneric(src: Src, open: Int): Int {
        var depth = 0
        var i = open
        val end = minOf(src.text.length, open + 300)
        while (i < end) {
            when (src.text[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                ';', '{', '(' -> return i
            }
            i++
        }
        return i
    }

    /** Lo que sigue a una anotación (y sus argumentos) es la declaración de una clase. */
    private fun nextIsClass(src: Src, from: Int): Boolean {
        var k = src.skipWs(from)
        if (src.text.getOrNull(k) == '(') k = src.close(k).let { if (it < 0) return false else it + 1 }
        var guard = 0
        while (guard++ < 20) {
            k = src.skipWs(k)
            if (k >= src.text.length) return false
            if (src.text[k] == '@') {
                val w = src.wordAt(k + 1) ?: return false
                k += 1 + w.length
                while (src.text.getOrNull(k) == '.') k += 1 + (src.wordAt(k + 1)?.length ?: 0)
                if (src.text.getOrNull(src.skipWs(k)) == '(') k = src.close(src.skipWs(k)).let { if (it < 0) return false else it + 1 }
                continue
            }
            val w = src.wordAt(k) ?: return false
            if (w in setOf("class", "interface", "object", "record", "enum")) return true
            if (w !in CLASS_MODIFIERS) return false
            k += w.length
        }
        return false
    }

    private val CLASS_MODIFIERS = setOf("public", "private", "protected", "internal", "abstract", "final", "open", "data", "sealed", "static", "partial", "annotation")

    private val MODIFIERS = setOf(
        "public", "private", "protected", "internal", "static", "async", "final", "open", "override", "suspend", "abstract", "virtual",
        "fun", "def", "function", "sealed", "readonly", "inline", "operator", "synchronized", "default",
    )

    // ------------------------------------------------------------------ Django

    private fun django(src: Src, out: FrameworkFacts) {
        for (fn in listOf("path", "re_path", "url")) {
            src.eachWord("$fn(") { at ->
                val open = at + fn.length
                val args = src.args(open) ?: return@eachWord
                if (args.size < 2) return@eachWord
                val route = stringValue(src, args[0]) ?: return@eachWord
                val regex = fn != "path"
                val clean = if (regex) route.removePrefix("^").removeSuffix("$") else route
                val target = args[1]
                val ts = src.slice(target)
                if (ts.startsWith("include(")) {
                    val inc = src.args(src.text.indexOf("include(", target.first) + 7)
                    val first = inc?.firstOrNull() ?: return@eachWord
                    val lit = stringValue(src, first)
                    val ref = if (lit != null) HandlerRef(lit, null, HandlerRef.Style.MODULE) else handlerOf(src, first) ?: return@eachWord
                    out.mounts += MountFact(clean, ref, src.line(at), "Django")
                    return@eachWord
                }
                val viewExpr = ts.removeSuffix(".as_view()").substringBefore(".as_view(")
                val handler = if (viewExpr.all { Src.isIdent(it) || it == '.' }) {
                    HandlerRef(viewExpr.substringBefore('.'), viewExpr.substringAfter('.', "").ifEmpty { null }, HandlerRef.Style.BINDING)
                } else {
                    null
                }
                out.routes += RouteFact(EndpointKind.API, "ANY", clean, src.line(at), "Django", handler, viewExpr.takeIf { handler != null }, regex = regex || clean.contains('('))
            }
        }
        // Routers de Django REST framework: router.register(r'users', UserViewSet)
        src.each(".register(") { at ->
            val group = src.receiverBefore(at) ?: return@each
            val args = src.args(at + ".register".length) ?: return@each
            if (args.size < 2) return@each
            val prefix = stringValue(src, args[0]) ?: return@each
            val handler = handlerOf(src, args[1])
            for (p in listOf(prefix, "$prefix/<pk>")) {
                out.routes += RouteFact(EndpointKind.API, "ANY", "$p/", src.line(at), "Django REST framework", handler, handler?.toString(), group = group)
            }
            out.groups += GroupFact(group, "", false, "Django REST framework")
        }
    }

    // ------------------------------------------------------------------ Flask y FastAPI

    private fun pythonDecorators(src: Src, out: FrameworkFacts) {
        val groups = HashMap<String, String>() // nombre → framework
        for ((ctor, framework, root) in listOf(
            Triple("Flask(", "Flask", true), Triple("Blueprint(", "Flask", false), Triple("FastAPI(", "FastAPI", true), Triple("APIRouter(", "FastAPI", false),
        )) {
            src.eachWord(ctor) { at ->
                val name = assignedName(src, at) ?: return@eachWord
                val args = src.args(at + ctor.length - 1)
                val prefix = args?.let { namedArg(src, it, "url_prefix", "prefix") }?.let { stringValue(src, it) } ?: ""
                groups[name] = framework
                out.groups += GroupFact(name, prefix, root, framework)
            }
        }
        if (groups.isEmpty()) return
        for ((g, framework) in groups) {
            src.each("@$g.") { at ->
                if (!src.annotationAt(at)) return@each
                val verbStart = at + g.length + 2
                val verb = src.wordAt(verbStart) ?: return@each
                val open = src.skipWs(verbStart + verb.length)
                if (src.text.getOrNull(open) != '(') return@each
                val args = src.args(open) ?: return@each
                val path = args.firstOrNull()?.let { stringValue(src, it) } ?: return@each
                val methods = when (verb) {
                    "route", "api_route", "websocket" -> {
                        val m = namedArg(src, args, "methods")
                        val list = m?.let { r ->
                            val ob = src.skipWs(r.first)
                            if (src.text.getOrNull(ob) == '[' || src.text.getOrNull(ob) == '(') {
                                val end = src.close(ob)
                                if (end > 0) src.splitTop(ob + 1, end, ',').mapNotNull { stringValue(src, it)?.uppercase() } else null
                            } else {
                                null
                            }
                        }
                        list ?: if (verb == "route") listOf("GET") else listOf("ANY")
                    }
                    in VERBS -> listOf(VERBS.getValue(verb))
                    else -> return@each
                }
                val fn = functionAfter(src, src.close(open) + 1)
                for (m in methods.filter { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "ANY") }) {
                    out.routes += RouteFact(EndpointKind.API, m, path, src.line(at), framework, handlerLabel = fn, group = g)
                }
            }
            src.each("$g.register_blueprint(") { at -> pyMount(src, out, at + "$g.register_blueprint".length, g, framework) }
            src.each("$g.include_router(") { at -> pyMount(src, out, at + "$g.include_router".length, g, framework) }
        }
    }

    private fun pyMount(src: Src, out: FrameworkFacts, open: Int, group: String, framework: String) {
        val args = src.args(open) ?: return
        val target = args.firstOrNull()?.let { handlerOf(src, it) } ?: return
        val prefix = namedArg(src, args, "url_prefix", "prefix")?.let { stringValue(src, it) } ?: ""
        out.mounts += MountFact(prefix, target, src.line(open), framework, group)
    }

    /** Nombre de la función (`def x`) que sigue a unos decoradores. */
    private fun functionAfter(src: Src, from: Int): String? {
        val idx = src.text.indexOf("def ", from)
        if (idx < 0 || idx - from > 2_000) return null
        return src.wordAt(src.skipWs(idx + 4))
    }

    // ------------------------------------------------------------------ Laravel

    private fun laravel(src: Src, out: FrameworkFacts) {
        val base = if (src.info.stem == "api") "/api" else ""
        val text = src.text
        // Grupos: prefijo y controlador, con el rango de su cuerpo.
        class Group(val start: Int, val end: Int, val prefix: String?, val controller: String?)
        val groups = mutableListOf<Group>()
        src.each("group(") { at ->
            if (at < 2 || !(text.startsWith("->", at - 2) || text.startsWith("::", at - 2))) return@each
            val stmt = text.lastIndexOf("Route::", at).takeIf { it >= 0 } ?: return@each
            val chain = text.substring(stmt, at)
            val args = src.args(at + 5) ?: return@each
            var prefix = Regexes.firstLiteralArg(chain, "prefix(")
            if (prefix == null && text.startsWith("::group(", at - 2) && args.size >= 2) {
                val ob = src.skipWs(args[0].first)
                if (text.getOrNull(ob) == '[') {
                    val arr = text.substring(ob, (src.close(ob) + 1).coerceAtLeast(ob))
                    prefix = Regexes.arrayValue(arr, "prefix")
                }
            }
            val controller = Regexes.CONTROLLER_GROUP.matcher(chain).let { if (it.find()) it.group(1) else null }
            val body = text.indexOf('{', args.last().first).takeIf { it in 0..args.last().last } ?: return@each
            val end = src.close(body, 200_000)
            if (end > 0) groups += Group(body, end, prefix, controller)
        }
        src.each("Route::") { at ->
            // Cadena de llamadas: Route::middleware(...)->prefix(...)->get('/x', ...)
            var k = at + 7
            var guard = 0
            while (guard++ < 12) {
                val name = src.wordAt(k) ?: return@each
                val open = k + name.length
                if (text.getOrNull(open) != '(') return@each
                val args = src.args(open) ?: return@each
                val end = src.close(open)
                val active = groups.filter { at > it.start && at < it.end }
                val prefix = joinPath(base, *active.mapNotNull { it.prefix }.toTypedArray())
                val groupController = active.lastOrNull { it.controller != null }?.controller
                when (name) {
                    "get", "post", "put", "patch", "delete", "options", "any" -> {
                        val path = args.firstOrNull()?.let { stringValue(src, it) } ?: return@each
                        val handler = args.getOrNull(1)?.let { phpHandler(src, it, groupController) }
                        out.routes += RouteFact(EndpointKind.API, VERBS.getValue(name), joinPath(prefix, path), src.line(at), "Laravel", handler, handler?.let { "${it.name}@${it.member}" })
                        return@each
                    }
                    "match" -> {
                        val verbs = args.firstOrNull()?.let { r ->
                            val ob = src.skipWs(r.first)
                            if (text.getOrNull(ob) == '[') src.splitTop(ob + 1, src.close(ob).coerceAtLeast(ob + 1), ',').mapNotNull { stringValue(src, it)?.uppercase() } else null
                        }.orEmpty()
                        val path = args.getOrNull(1)?.let { stringValue(src, it) } ?: return@each
                        val handler = args.getOrNull(2)?.let { phpHandler(src, it, groupController) }
                        verbs.filter { it in VERBS.values }.forEach {
                            out.routes += RouteFact(EndpointKind.API, it, joinPath(prefix, path), src.line(at), "Laravel", handler, handler?.let { h -> "${h.name}@${h.member}" })
                        }
                        return@each
                    }
                    "resource", "apiResource" -> {
                        val res = args.firstOrNull()?.let { stringValue(src, it) } ?: return@each
                        val cls = args.getOrNull(1)?.let { classConst(src, it) } ?: return@each
                        val param = "{" + res.substringAfterLast('.').substringAfterLast('/').removeSuffix("s") + "}"
                        val actions = mutableListOf(
                            Triple("GET", res, "index"), Triple("POST", res, "store"), Triple("GET", "$res/$param", "show"),
                            Triple("PUT", "$res/$param", "update"), Triple("PATCH", "$res/$param", "update"), Triple("DELETE", "$res/$param", "destroy"),
                        )
                        if (name == "resource") {
                            actions += Triple("GET", "$res/create", "create")
                            actions += Triple("GET", "$res/$param/edit", "edit")
                        }
                        for ((m, p, action) in actions) {
                            out.routes += RouteFact(
                                EndpointKind.API, m, joinPath(prefix, p.replace('.', '/')), src.line(at), "Laravel",
                                HandlerRef(cls, action, HandlerRef.Style.CLASS), "$cls@$action",
                            )
                        }
                        return@each
                    }
                }
                if (end < 0) return@each
                k = src.skipWs(end + 1)
                if (!text.startsWith("->", k)) return@each
                k = src.skipWs(k + 2)
            }
        }
    }

    private fun classConst(src: Src, r: IntRange): String? {
        val s = src.slice(r)
        if (!s.endsWith("::class")) return null
        return s.removeSuffix("::class").trim().takeIf { n -> n.all { Src.isIdent(it) || it == '\\' } }
    }

    private fun phpHandler(src: Src, r: IntRange, groupController: String?): HandlerRef? {
        val s = src.slice(r)
        if (s.startsWith("[")) {
            val ob = src.skipWs(r.first)
            val end = src.close(ob)
            if (end < 0) return null
            val parts = src.splitTop(ob + 1, end, ',')
            val cls = parts.getOrNull(0)?.let { classConst(src, it) } ?: return null
            val method = parts.getOrNull(1)?.let { stringValue(src, it) }
            return HandlerRef(cls, method ?: "__invoke", HandlerRef.Style.CLASS)
        }
        classConst(src, r)?.let { return HandlerRef(it, "__invoke", HandlerRef.Style.CLASS) }
        val lit = stringValue(src, r) ?: return null
        if (lit.contains('@')) return HandlerRef(lit.substringBefore('@'), lit.substringAfter('@'), HandlerRef.Style.CLASS)
        if (groupController != null && lit.all { Src.isIdent(it) }) return HandlerRef(groupController, lit, HandlerRef.Style.CLASS)
        return null
    }

    // ------------------------------------------------------------------ Spring y JAX-RS

    private fun spring(src: Src, out: FrameworkFacts) {
        val text = src.text
        // Clases y su prefijo de @RequestMapping / @Path de clase.
        val classes = mutableListOf<Triple<Int, String, String>>() // posición de la clase, nombre, prefijo
        val m = Regexes.CLASS_DECL.matcher(text)
        while (m.find()) {
            if (!src.isCode(m.start())) continue
            val pos = m.start()
            val name = m.group(2)
            // Anotaciones justo antes de la clase.
            val head = Regexes.annotationBlock(text, pos)
            val prefix = Regexes.lastMapping(head, "RequestMapping") ?: Regexes.lastMapping(head, "Path") ?: ""
            classes += Triple(pos, name, prefix)
        }
        val verbs = listOf(
            "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT", "PatchMapping" to "PATCH", "DeleteMapping" to "DELETE", "RequestMapping" to null,
        )
        for ((ann, fixed) in verbs) {
            src.each("@$ann") { at ->
                val after = at + ann.length + 1
                if (after < text.length && Src.isIdent(text[after])) return@each
                if (!src.annotationAt(at)) return@each
                val owner = classes.lastOrNull { it.first < at } ?: return@each
                // Las anotaciones de clase ya están en el prefijo.
                if (nextIsClass(src, after)) return@each
                var paths = listOf("")
                var method = fixed ?: "ANY"
                if (text.getOrNull(src.skipWs(after)) == '(') {
                    val open = src.skipWs(after)
                    val args = src.args(open) ?: return@each
                    val explicit = namedArg(src, args, "value", "path")
                    val pathArg = explicit ?: args.firstOrNull()?.takeIf { a -> src.wordAt(src.skipWs(a.first)).let { w -> w == null || src.text.getOrNull(src.skipWs(src.skipWs(a.first) + w.length)) != '=' } }
                    if (pathArg != null) paths = listValues(src, pathArg).ifEmpty { listOf("") }
                    if (fixed == null) {
                        namedArg(src, args, "method")?.let { r -> Regexes.REQUEST_METHOD.matcher(src.slice(r)).let { mm -> if (mm.find()) method = mm.group(1) } }
                    }
                }
                val name = memberAfter(src, if (text.getOrNull(src.skipWs(after)) == '(') src.close(src.skipWs(after)) + 1 else after)
                for (p in paths) {
                    out.routes += RouteFact(EndpointKind.API, method, joinPath(owner.third, p), src.line(at), "Spring", handlerLabel = listOfNotNull(owner.second, name).joinToString("."), group = "spring")
                }
            }
        }
        // JAX-RS: @GET + @Path opcional en el método.
        for (verb in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            src.each("@$verb") { at ->
                val after = at + verb.length + 1
                if (after < text.length && Src.isIdent(text[after])) return@each
                if (!src.annotationAt(at)) return@each
                val owner = classes.lastOrNull { it.first < at } ?: return@each
                if (!text.contains("@Path")) return@each
                // @Path del método: entre la declaración anterior y la siguiente.
                val blockStart = maxOf(text.lastIndexOf(';', at), text.lastIndexOf('}', at), text.lastIndexOf('{', at), owner.first)
                val paren = text.indexOf('(', after).let { if (it < 0) text.length else it }
                val nextAnn = text.indexOf("@Path", after).takeIf { it in after until paren + 200 && Regexes.lastMapping(text.substring(it, minOf(text.length, it + 200)), "Path") != null }
                val local = Regexes.lastMapping(text.substring(blockStart + 1, at), "Path") ?: nextAnn?.let { Regexes.lastMapping(text.substring(it, minOf(text.length, it + 200)), "Path") }
                out.routes += RouteFact(EndpointKind.API, verb, joinPath(owner.third, local ?: ""), src.line(at), "JAX-RS", handlerLabel = listOfNotNull(owner.second, memberAfter(src, after)).joinToString("."), group = "spring")
            }
        }
    }

    /** Valores de un argumento que puede ser una cadena o una lista (`"/a"`, `["/a", "/b"]`, `{"/a"}`). */
    private fun listValues(src: Src, r: IntRange): List<String> {
        val s = src.skipWs(r.first)
        val c = src.text.getOrNull(s)
        if (c == '[' || c == '{') {
            val end = src.close(s)
            if (end < 0) return emptyList()
            return src.splitTop(s + 1, end, ',').mapNotNull { stringValue(src, it) }
        }
        if (src.text.startsWith("arrayOf(", s)) return src.args(s + 7)?.mapNotNull { stringValue(src, it) }.orEmpty()
        return listOfNotNull(stringValue(src, r))
    }

    // ------------------------------------------------------------------ ASP.NET Core

    private fun aspnet(src: Src, out: FrameworkFacts) {
        val text = src.text
        if (src.has("[Http") || src.has("[Route(")) {
            val classes = mutableListOf<Triple<Int, String, String?>>()
            val m = Regexes.CS_CLASS.matcher(text)
            while (m.find()) {
                if (!src.isCode(m.start())) continue
                classes += Triple(m.start(), m.group(1), Regexes.lastAttribute(Regexes.annotationBlock(text, m.start()), "Route"))
            }
            val verbs = listOf("HttpGet" to "GET", "HttpPost" to "POST", "HttpPut" to "PUT", "HttpPatch" to "PATCH", "HttpDelete" to "DELETE", "HttpHead" to "HEAD", "HttpOptions" to "OPTIONS")
            for ((attr, method) in verbs) {
                src.each("[$attr") { at ->
                    val after = at + attr.length + 1
                    if (after < text.length && Src.isIdent(text[after])) return@each
                    if (!src.annotationAt(at)) return@each
                    val owner = classes.lastOrNull { it.first < at } ?: return@each
                    val close = src.close(at)
                    if (close < 0) return@each
                    val inner = text.substring(at + 1, close)
                    var template = Regexes.firstLiteral(inner) ?: ""
                    // [Route("x")] del método, si la hay.
                    if (template.isEmpty()) {
                        val around = text.substring(at, minOf(text.length, at + 400))
                        val upto = around.indexOf("public ").let { if (it < 0) minOf(around.length, 200) else it }
                        Regexes.lastAttribute(around.substring(0, upto), "Route")?.let { template = it }
                    }
                    val name = memberAfter(src, close + 1)
                    val cls = owner.second.removeSuffix("Controller")
                    val prefix = (owner.third ?: "").replace("[controller]", cls, ignoreCase = true).replace("[action]", name ?: "", ignoreCase = true)
                    val t = template.replace("[controller]", cls, ignoreCase = true).replace("[action]", name ?: "", ignoreCase = true)
                    val path = if (t.startsWith("/") || t.startsWith("~/")) joinPath(t.removePrefix("~")) else joinPath(prefix, t)
                    out.routes += RouteFact(EndpointKind.API, method, path, src.line(at), "ASP.NET Core", handlerLabel = listOfNotNull(owner.second, name).joinToString("."), group = "aspnet")
                }
            }
        }
        if (src.has(".Map")) {
            val groups = HashMap<String, String>()
            src.each(".MapGroup(") { at ->
                val args = src.args(at + ".MapGroup".length) ?: return@each
                val prefix = args.firstOrNull()?.let { stringValue(src, it) } ?: return@each
                val name = assignedName(src, src.text.lastIndexOf(src.receiverBefore(at) ?: return@each, at)) ?: return@each
                val parent = src.receiverBefore(at)?.let { groups[it] } ?: ""
                groups[name] = joinPath(parent, prefix)
            }
            for ((call, method) in listOf("MapGet" to "GET", "MapPost" to "POST", "MapPut" to "PUT", "MapPatch" to "PATCH", "MapDelete" to "DELETE")) {
                src.each(".$call(") { at ->
                    val args = src.args(at + call.length + 1) ?: return@each
                    val path = args.firstOrNull()?.let { stringValue(src, it) } ?: return@each
                    val prefix = src.receiverBefore(at)?.let { groups[it] } ?: ""
                    out.routes += RouteFact(EndpointKind.API, method, joinPath(prefix, path), src.line(at), "ASP.NET Core", handlerLabel = null, group = "aspnet")
                }
            }
        }
        src.each(".UsePathBase(") { at ->
            src.args(at + ".UsePathBase".length)?.firstOrNull()?.let { stringValue(src, it) }?.let { out.globalPrefixes += GlobalPrefixFact("ASP.NET Core", joinPath(it), src.line(at)) }
        }
    }

    // ------------------------------------------------------------------ enrutadores del frontend

    private fun frontend(src: Src, out: FrameworkFacts) {
        val router = src.has("vue-router") || src.has("@angular/router") || src.has("react-router") || src.has("createBrowserRouter") ||
            src.has("RouterModule") || src.has("<Route") || src.has("createRouter") || src.has("Routes")
        if (!router) return
        val framework = when {
            src.has("@angular/router") || src.has("RouterModule") -> "Angular Router"
            src.has("vue-router") -> "Vue Router"
            else -> "React Router"
        }
        // Forma de objeto: { path: '/x', component: X }
        class Obj(val start: Int, val end: Int, val path: String, val handler: HandlerRef?, val line: Int)
        val objs = mutableListOf<Obj>()
        src.eachWord("path") { at ->
            var k = src.skipWs(at + 4)
            if (src.text.getOrNull(k) != ':') return@eachWord
            k++
            val lit = src.literal(k) ?: return@eachWord
            if (lit.dynamic) return@eachWord
            val open = enclosingBrace(src, at) ?: return@eachWord
            val end = src.close(open)
            if (end < 0) return@eachWord
            val parts = src.splitTop(open + 1, end, ',')
            var handler: HandlerRef? = null
            for (key in listOf("component", "Component", "element", "loadComponent", "loadChildren", "lazy")) {
                val r = namedArg(src, parts, key) ?: continue
                handler = componentRef(src, r)
                if (handler != null) break
            }
            // Las redirecciones no son páginas.
            if (handler == null && parts.any { p -> src.wordAt(src.skipWs(p.first)) in setOf("redirectTo", "redirect") }) return@eachWord
            objs += Obj(open, end, lit.value, handler, src.line(at))
        }
        for (o in objs) {
            val parents = objs.filter { it !== o && it.start < o.start && it.end > o.end }.sortedBy { it.start }
            val full = joinPath(*parents.map { it.path }.toTypedArray(), o.path)
            out.routes += RouteFact(EndpointKind.PAGE, "ANY", full, o.line, framework, o.handler, o.handler?.toString())
        }
        // JSX: <Route path="/x" element={<X />} />, con anidamiento.
        if (src.has("<Route")) {
            val stack = ArrayDeque<String>()
            var i = 0
            val t = src.text
            while (i < t.length) {
                val open = t.indexOf("<Route", i)
                val close = t.indexOf("</Route>", i)
                if (open < 0 && close < 0) break
                if (close >= 0 && (open < 0 || close < open)) {
                    if (stack.isNotEmpty()) stack.removeLast()
                    i = close + 8
                    continue
                }
                i = open + 6
                if (i < t.length && (Src.isIdent(t[i]))) continue
                if (!src.isCode(open) || src.inString(open)) continue
                val tagEnd = jsxTagEnd(src, i) ?: continue
                val attrs = t.substring(i, tagEnd)
                val selfClosing = attrs.trimEnd().endsWith("/")
                val path = Regexes.jsxAttr(attrs, "path")
                val comp = Regexes.JSX_ELEMENT.matcher(attrs).let { mm -> if (mm.find()) mm.group(1) else null }
                    ?: Regexes.JSX_COMPONENT.matcher(attrs).let { mm -> if (mm.find()) mm.group(1) else null }
                val parent = stack.lastOrNull() ?: ""
                val full = when {
                    path == null -> parent
                    path.startsWith("/") -> joinPath(path)
                    else -> joinPath(parent, path)
                }
                if (path != null || attrs.contains("index")) {
                    val handler = comp?.let { HandlerRef(it, null, HandlerRef.Style.BINDING) }
                    out.routes += RouteFact(EndpointKind.PAGE, "ANY", full, src.line(open), "React Router", handler, comp)
                }
                if (!selfClosing) stack.addLast(full)
                i = tagEnd + 1
            }
        }
    }

    /** Llave `{` que abre el objeto que contiene la posición (hacia atrás, acotado). */
    private fun enclosingBrace(src: Src, at: Int): Int? {
        var depth = 0
        var i = at - 1
        val stop = maxOf(0, at - 3_000)
        while (i >= stop) {
            when (src.text[i]) {
                '}', ']', ')' -> depth++
                '{' -> if (depth == 0) return i else depth--
                '[', '(' -> if (depth == 0) return null else depth--
            }
            i--
        }
        return null
    }

    private fun jsxTagEnd(src: Src, from: Int): Int? {
        var depth = 0
        var i = from
        val end = minOf(src.text.length, from + 2_000)
        while (i < end) {
            when (src.text[i]) {
                '{' -> depth++
                '}' -> depth--
                '>' -> if (depth == 0) return i
            }
            i++
        }
        return null
    }

    /** Componente de una ruta: `X`, `<X />`, `() => import('./x')`, `lazy(() => import('./x'))`. */
    private fun componentRef(src: Src, r: IntRange): HandlerRef? {
        val s = src.slice(r)
        val imp = s.indexOf("import(")
        if (imp >= 0) {
            val lit = src.literal(src.text.indexOf("import(", r.first) + 7) ?: return null
            return HandlerRef(lit.value, null, HandlerRef.Style.MODULE)
        }
        val tag = Regexes.LEADING_TAG.matcher(s)
        if (tag.find()) return HandlerRef(tag.group(1), null, HandlerRef.Style.BINDING)
        if (s.isNotEmpty() && s.all { Src.isIdent(it) || it == '.' || it == '$' }) return HandlerRef(s.substringBefore('.'), s.substringAfter('.', "").ifEmpty { null }, HandlerRef.Style.BINDING)
        return null
    }

    // ------------------------------------------------------------------ Flutter

    private fun flutter(src: Src, out: FrameworkFacts) {
        // routes: { '/x': (context) => const XScreen(), }
        src.eachWord("routes") { at ->
            var k = src.skipWs(at + 6)
            if (src.text.getOrNull(k) != ':') return@eachWord
            k = src.skipWs(k + 1)
            if (src.text.getOrNull(k) != '{') return@eachWord
            val end = src.close(k)
            if (end < 0) return@eachWord
            for (entry in src.splitTop(k + 1, end, ',')) {
                val lit = src.literal(entry.first) ?: continue
                val cls = Regexes.firstClassAfterArrow(src.text.substring(lit.end, entry.last + 1))
                out.routes += RouteFact(EndpointKind.PAGE, "ANY", joinPath(lit.value), src.line(lit.start), "Flutter", cls?.let { HandlerRef(it, null, HandlerRef.Style.CLASS) }, cls)
            }
        }
        // GoRoute(path: '/x', builder: (c, s) => XScreen()), anidadas en routes: [...]
        class R(val start: Int, val end: Int, val path: String, val cls: String?, val line: Int)
        val list = mutableListOf<R>()
        src.eachWord("GoRoute(") { at ->
            val open = at + 7
            val end = src.close(open)
            if (end < 0) return@eachWord
            val args = src.splitTop(open + 1, end, ',')
            val path = namedArg(src, args, "path")?.let { stringValue(src, it) } ?: return@eachWord
            val builder = namedArg(src, args, "builder", "pageBuilder")?.let { Regexes.firstClassAfterArrow(src.slice(it)) }
            list += R(open, end, path, builder, src.line(at))
        }
        for (r in list) {
            val parents = list.filter { it !== r && it.start < r.start && it.end > r.end }.sortedBy { it.start }
            val full = if (r.path.startsWith("/")) joinPath(r.path) else joinPath(*parents.map { it.path }.toTypedArray(), r.path)
            out.routes += RouteFact(EndpointKind.PAGE, "ANY", full, r.line, "go_router", r.cls?.let { HandlerRef(it, null, HandlerRef.Style.CLASS) }, r.cls)
        }
    }
}
