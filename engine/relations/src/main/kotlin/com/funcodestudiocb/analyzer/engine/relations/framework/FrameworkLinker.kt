package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.api.ConfigKey
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DataEntity
import com.funcodestudiocb.analyzer.engine.api.DataEntityKind
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.languages.ImportRef
import com.funcodestudiocb.analyzer.engine.languages.SymbolKind
import com.funcodestudiocb.analyzer.engine.relations.IdentifierSet
import com.funcodestudiocb.analyzer.engine.relations.ProjectIndex
import com.funcodestudiocb.analyzer.engine.relations.Resolution
import com.funcodestudiocb.analyzer.engine.relations.Resolver
import com.funcodestudiocb.analyzer.engine.relations.ResourceRef
import com.funcodestudiocb.analyzer.engine.relations.SourceFacts

/** Conexión ya creada (o fusionada) por la etapa: el enlazador le añade los lugares afectados. */
internal interface DraftSink {
    /** Crea o amplía la conexión y devuelve su clave, o `null` si se descartó (límite). */
    fun add(source: String, line: Int?, r: Resolution, reference: String, kind: RelationKind, what: String, extra: Pair<String, Int>? = null): String?

    /** Lugares ("archivo:línea") que dependen del mismo destino concreto que la conexión [key]. */
    fun affected(key: String, places: Collection<String>)

    /** Destinos de las importaciones ya resueltas de un archivo, con lo que importa de cada uno. */
    fun importsOf(source: String): List<Pair<String, List<String>>>
}

/**
 * Enlaza los hechos de los analizadores de framework con los archivos del proyecto. Nunca inventa un destino: lo que no
 * se encuentra se registra como externo o no verificado, con el motivo; una ruta o clase que debería estar en el
 * proyecto y no está es una conexión rota (posible error).
 */
internal class FrameworkLinker(
    private val index: ProjectIndex,
    private val resolver: Resolver,
    private val facts: Map<String, FrameworkFacts>,
    private val dependencies: List<DeclaredDependency>,
    private val sink: DraftSink,
) {
    val endpoints = mutableListOf<Endpoint>()
    val entities = mutableListOf<DataEntity>()
    val configKeys = mutableListOf<ConfigKey>()
    val notes = mutableListOf<Pair<CoverageReason, String>>()
    val endpointFiles = HashSet<String>()
    var httpCalls = 0
        private set
    var httpMatched = 0
        private set
    var pages = 0
        private set

    /** Llamadas HTTP sin ruta coincidente en el backend del proyecto (para el informe). */
    val unmatchedCalls = mutableListOf<String>()

    /** Nombre declarado → archivos que lo declaran (todas las clases, funciones… del proyecto). */
    private val byName: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { map ->
        index.declared.forEach { (file, names) -> names.keys.forEach { map.getOrPut(it) { mutableListOf() } += file } }
    }

    fun link() {
        routes()
        http()
        data()
        config()
        injection()
        android()
        unity()
        paths()
        channels()
    }

    // ================================================================== utilidades

    private fun sf(path: String): SourceFacts =
        index.facts[path] ?: SourceFacts(path, "", emptyList(), emptyList(), emptyList(), IdentifierSet.EMPTY, emptyList())

    private fun place(path: String, line: Int?) = if (line != null) "$path:$line" else path

    private class Found(val file: String, val confidence: RelationConfidence, val how: String)

    /** Clase por su nombre desde un archivo: importaciones, nombre completo, mismo paquete o único en el proyecto. */
    private fun findClass(from: String, rawName: String): Found? {
        val name = rawName.trim().removePrefix("\\").replace('$', '.')
        if (name.isEmpty()) return null
        val simple = name.substringAfterLast('.').substringAfterLast('\\')
        // Nombre completo
        if (name.contains('.') || name.contains('\\')) {
            (index.jvmSymbols[name] ?: index.csSymbols[name] ?: index.phpSymbols[name.replace('.', '\\')])?.firstOrNull { it != from }?.let {
                return Found(it, RelationConfidence.VERIFIED, "nombre completo de la clase")
            }
        }
        // Importaciones ya resueltas
        sink.importsOf(from).firstOrNull { (t, symbols) -> t != from && (simple in symbols || index.declared[t]?.containsKey(simple) == true && symbols.isEmpty()) }
            ?.let { return Found(it.first, RelationConfidence.VERIFIED, "se importa de ese archivo") }
        sink.importsOf(from).firstOrNull { (t, _) -> t != from && index.declared[t]?.containsKey(simple) == true }
            ?.let { return Found(it.first, RelationConfidence.VERIFIED, "se importa de ese archivo") }
        // `use` de PHP
        facts[from]?.bindings?.get(simple)?.first?.let { fqcn ->
            index.phpSymbols[fqcn.trim('\\')]?.firstOrNull { it != from }?.let { return Found(it, RelationConfidence.VERIFIED, "use $fqcn") }
        }
        // Mismo paquete o espacio de nombres
        val ns = index.facts[from]?.namespaces.orEmpty()
        for (n in ns) {
            (index.jvmSymbols["$n.$simple"] ?: index.csSymbols["$n.$simple"] ?: index.phpSymbols["${n.trim('\\')}\\$simple"])?.firstOrNull { it != from }?.let {
                return Found(it, RelationConfidence.VERIFIED, "clase del mismo paquete")
            }
        }
        if (simple in index.declared[from].orEmpty()) return Found(from, RelationConfidence.VERIFIED, "declarada en el mismo archivo")
        // Único en el proyecto
        val all = byName[simple].orEmpty().filter { it != from }
        val sameFamily = all.filter { family(it) == family(from) }.ifEmpty { all }
        if (sameFamily.size == 1) return Found(sameFamily.single(), RelationConfidence.PROBABLE, "único archivo del proyecto que declara «$simple»")
        return null
    }

    private fun family(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java" -> "jvm"
        "ts", "tsx", "js", "jsx", "mjs", "cjs", "vue", "svelte" -> "web"
        "cs" -> "cs"
        "php" -> "php"
        "py" -> "py"
        "dart" -> "dart"
        else -> "otro"
    }

    /** Nombre local importado (JavaScript, Python) → archivo del que viene. */
    private fun resolveBinding(from: String, name: String): Found? {
        val (module, member) = facts[from]?.bindings?.get(name) ?: return null
        val ref = ImportRef(module, 1, listOfNotNull(member?.takeIf { it != "default" }))
        val resolutions = resolver.resolve(sf(from), ref).filter { it.target != null && it.confidence != RelationConfidence.BROKEN }
        val pick = resolutions.firstOrNull { r -> r.target!!.substringAfterLast('/').substringBeforeLast('.') == (member ?: name) } ?: resolutions.firstOrNull() ?: return null
        return Found(pick.target!!, pick.confidence, "«$name» se importa de «$module»")
    }

    private fun resolveModule(from: String, module: String): Found? {
        val resolutions = resolver.resolve(sf(from), ImportRef(module, 1)).filter { it.target != null && it.confidence != RelationConfidence.BROKEN }
        val r = resolutions.firstOrNull() ?: return null
        return Found(r.target!!, r.confidence, r.how)
    }

    // ================================================================== rutas

    private class Mount(val from: String, val fromGroup: String?, val to: String, val toGroup: String?, val prefix: String)

    private class Ep(val route: RouteFact, val file: String, val path: String, val known: Boolean, val framework: String, var handlerFile: String?, val segs: List<Seg>, val rest: Boolean) {
        val callers = mutableListOf<String>()
        val callerKeys = mutableListOf<String>()
        var routeKey: String? = null
    }

    private class Seg(val text: String, val param: Boolean)

    private val eps = mutableListOf<Ep>()
    private val mounts = mutableListOf<Mount>()

    private fun routes() {
        // Montajes entre enrutadores (y relaciones de registro).
        for ((file, f) in facts) {
            for (m in f.mounts) {
                val localGroup = m.target.style == HandlerRef.Style.BINDING && (f.groups.any { it.name == m.target.name } || f.routes.any { it.group == m.target.name })
                if (localGroup) {
                    mounts += Mount(file, m.group, file, m.target.name, m.prefix)
                    continue
                }
                val found = when (m.target.style) {
                    HandlerRef.Style.MODULE -> resolveModule(file, m.target.name)
                    HandlerRef.Style.BINDING -> resolveBinding(file, m.target.name)
                    HandlerRef.Style.CLASS -> findClass(file, m.target.name)
                } ?: continue
                if (found.file == file) continue
                val targetFacts = facts[found.file]
                val toGroup = m.target.member?.takeIf { g -> targetFacts?.groups?.any { it.name == g } == true || targetFacts?.routes?.any { it.group == g } == true }
                mounts += Mount(file, m.group, found.file, toGroup, m.prefix)
                sink.add(
                    file, m.line, Resolution(found.file, confidence = found.confidence, how = found.how, kind = RelationKind.REGISTRATION, exchanged = listOf("prefijo ${Routes.joinPath(m.prefix)}")),
                    m.target.toString(), RelationKind.REGISTRATION, "Monta las rutas de «${m.target}» en ${Routes.joinPath(m.prefix)} (${m.framework})",
                )
            }
        }
        val globals = facts.flatMap { (file, f) -> f.globalPrefixes.map { file to it } }
        for ((file, f) in facts) {
            for (r in f.routes) {
                val prefixes = prefixes(file, r.group, 0, HashSet())
                for ((prefix, known) in prefixes.ifEmpty { listOf("" to false) }) {
                    val global = globals.firstOrNull { (gf, g) -> (g.framework == r.framework || g.framework == "Spring" && r.framework == "JAX-RS") && underRoot(gf, file) }?.second?.prefix ?: ""
                    val full = Routes.joinPath(global, prefix, r.path)
                    addEndpoint(r, file, full, known, r.framework)
                }
            }
        }
        pathRoutes()
        // Controlador de cada ruta.
        for (ep in eps) {
            val r = ep.route
            val h = r.handler
            if (h == null) {
                if (r.handlerLabel != null || r.kind == EndpointKind.API && ep.framework in IN_FILE_FRAMEWORKS) ep.handlerFile = ep.file
                continue
            }
            val found: Found? = when (h.style) {
                HandlerRef.Style.BINDING -> resolveBinding(ep.file, h.name) ?: if (index.declared[ep.file]?.containsKey(h.name) == true) Found(ep.file, RelationConfidence.VERIFIED, "en el mismo archivo") else null
                HandlerRef.Style.MODULE -> resolveModule(ep.file, h.name)
                HandlerRef.Style.CLASS -> findClass(ep.file, h.name) ?: laravelDefault(ep.file, h.name)
            }
            if (found == null) {
                // Controlador de Laravel en el espacio de nombres del proyecto que no existe: posible error.
                if (h.style == HandlerRef.Style.CLASS && ep.framework == "Laravel" && index.phpNamespaces.keys.any { it.startsWith("App\\Http\\Controllers") }) {
                    ep.routeKey = sink.add(
                        ep.file, r.line, Resolution(confidence = RelationConfidence.BROKEN, how = "no hay ninguna clase «${h.name}» en el proyecto: posible error", kind = RelationKind.ROUTE_HANDLER, exchanged = listOf("${r.method} ${ep.path}")),
                        r.handlerLabel ?: h.toString(), RelationKind.ROUTE_HANDLER, "${r.method} ${ep.path} → ${r.handlerLabel ?: h}",
                    )
                }
                continue
            }
            ep.handlerFile = found.file
            if (found.file == ep.file) continue
            // Método inexistente en el controlador de PHP.
            val methods = facts[found.file]?.methods.orEmpty()
            val member = h.member
            if (h.style == HandlerRef.Style.CLASS && member != null && member != "__invoke" && methods.isNotEmpty() && member !in methods) {
                ep.routeKey = sink.add(
                    ep.file, r.line, Resolution(confidence = RelationConfidence.BROKEN, how = "${found.file.substringAfterLast('/')} no declara el método «$member»: posible error", kind = RelationKind.ROUTE_HANDLER, exchanged = listOf("${r.method} ${ep.path}")),
                    r.handlerLabel ?: h.toString(), RelationKind.ROUTE_HANDLER, "${r.method} ${ep.path} → ${r.handlerLabel ?: h}",
                )
                ep.handlerFile = null
                continue
            }
            val label = if (r.kind == EndpointKind.PAGE) "página ${ep.path}" else "${r.method} ${ep.path}"
            ep.routeKey = sink.add(
                ep.file, r.line,
                Resolution(found.file, confidence = found.confidence, how = found.how, kind = RelationKind.ROUTE_HANDLER, exchanged = listOf("$label → ${r.handlerLabel ?: h}".take(RecordValidator.MAX_NAME_LENGTH))),
                r.handlerLabel ?: h.toString(), RelationKind.ROUTE_HANDLER, "${if (r.kind == EndpointKind.PAGE) "Ruta de la página ${ep.path}" else "Ruta $label"} (${r.framework})",
            )
        }
    }

    /** Laravel: `UserController` sin `use` está en `App\Http\Controllers`. */
    private fun laravelDefault(from: String, name: String): Found? {
        if (!from.endsWith(".php")) return null
        val fq = if (name.contains('\\')) name.trim('\\') else "App\\Http\\Controllers\\$name"
        return index.phpSymbols[fq]?.firstOrNull()?.let { Found(it, RelationConfidence.PROBABLE, "espacio de nombres por defecto de los controladores de Laravel") }
    }

    private fun underRoot(configFile: String, file: String): Boolean {
        val root = configFile.substringBefore("/src/", configFile.substringBeforeLast('/', "")).let { if (it == configFile) "" else it }
        return root.isEmpty() || file.startsWith("$root/")
    }

    /** Prefijos de un grupo de rutas a partir de los montajes (iterativo con límite de profundidad). */
    private fun prefixes(file: String, group: String?, depth: Int, visiting: HashSet<String>): List<Pair<String, Boolean>> {
        val key = "$file|$group"
        if (depth > 8 || !visiting.add(key)) return listOf("" to false)
        val f = facts[file]
        val g = group?.let { n -> f?.groups?.firstOrNull { it.name == n } }
        val local = g?.prefix ?: ""
        if (g?.root == true) return listOf(local to true)
        val incoming = mounts.filter { it.to == file && (it.toGroup == group || it.toGroup == null) && !(it.from == file && it.fromGroup == group) }
        if (incoming.isEmpty()) {
            // Sin montaje: las rutas de Django y Laravel (sin grupo) y las de los frameworks con prefijo de clase son
            // completas; un enrutador de Express, Flask o FastAPI que nadie monta tiene un prefijo desconocido.
            val framework = f?.routes?.firstOrNull { it.group == group }?.framework
            return listOf(local to (group == null || framework in ROOT_GROUP_FRAMEWORKS))
        }
        val out = mutableListOf<Pair<String, Boolean>>()
        for (m in incoming) {
            for ((p, known) in prefixes(m.from, m.fromGroup, depth + 1, visiting)) {
                out += Routes.joinPath(p, m.prefix, local) to known
                if (out.size >= 4) break
            }
            if (out.size >= 4) break
        }
        visiting.remove(key)
        return out.distinct()
    }

    private fun addEndpoint(r: RouteFact, file: String, path: String, known: Boolean, framework: String) {
        if (eps.size >= MAX_ENDPOINTS) return
        val (segs, rest) = segments(path)
        eps += Ep(r, file, path, known && !r.regex, framework, null, segs, rest)
    }

    /** Segmentos de una ruta declarada: los parámetros (`:id`, `{id}`, `<int:id>`, `[id]`) aceptan cualquier valor. */
    private fun segments(path: String): Pair<List<Seg>, Boolean> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        var rest = false
        val segs = parts.mapNotNull { p ->
            when {
                p == "*" || p == "**" || p.startsWith("{*") || p.startsWith("[...") || p.startsWith("[[...") || p.startsWith(":") && p.endsWith("*") || p == "(.*)" -> {
                    rest = true
                    null
                }
                p.startsWith(":") || p.startsWith("{") && p.endsWith("}") || p.startsWith("<") && p.endsWith(">") || p.startsWith("[") && p.endsWith("]") || p.startsWith("$") -> Seg(p, true)
                else -> Seg(p, false)
            }
        }
        return segs to rest
    }

    /** Rutas por archivo: Next.js (pages/, app/), Nuxt (server/api, pages) y SvelteKit (src/routes). */
    private fun pathRoutes() {
        val npm = dependencies.filter { it.ecosystem == Ecosystem.NPM }
        for ((dep, framework) in listOf("next" to "Next.js", "nuxt" to "Nuxt", "@sveltejs/kit" to "SvelteKit")) {
            for (d in npm.filter { it.name == dep }.distinctBy { it.manifest }) {
                val root = d.manifest.substringBeforeLast('/', "")
                val prefix = if (root.isEmpty()) "" else "$root/"
                for (file in index.files.filter { it.startsWith(prefix) && "node_modules/" !in it }.sorted()) {
                    val rel = file.removePrefix(prefix)
                    val ext = rel.substringAfterLast('.', "")
                    when (framework) {
                        "Next.js" -> {
                            val pagesDir = listOf("pages/", "src/pages/").firstOrNull { rel.startsWith(it) }
                            val appDir = listOf("app/", "src/app/").firstOrNull { rel.startsWith(it) }
                            if (pagesDir != null && ext in setOf("js", "jsx", "ts", "tsx", "mdx")) {
                                val route = rel.removePrefix(pagesDir).substringBeforeLast('.')
                                val name = route.substringAfterLast('/')
                                if (name.startsWith("_") || name in setOf("404", "500")) continue
                                val path = Routes.joinPath(route.removeSuffix("index").removeSuffix("/"))
                                val api = route.startsWith("api/")
                                pathEndpoint(file, path, if (api) EndpointKind.API else EndpointKind.PAGE, framework, if (api) exportedMethods(file) else listOf("ANY"))
                            } else if (appDir != null) {
                                val dir = rel.removePrefix(appDir).substringBeforeLast('/', "")
                                val name = rel.substringAfterLast('/').substringBeforeLast('.')
                                val path = Routes.joinPath(*dir.split('/').filter { s -> s.isNotEmpty() && !(s.startsWith("(") && s.endsWith(")")) && !s.startsWith("@") }.toTypedArray())
                                if (name == "page" && ext in setOf("js", "jsx", "ts", "tsx", "mdx")) pathEndpoint(file, path, EndpointKind.PAGE, framework, listOf("ANY"))
                                if (name == "route" && ext in setOf("js", "ts")) pathEndpoint(file, path, EndpointKind.API, framework, exportedMethods(file))
                            }
                        }
                        "Nuxt" -> {
                            if (rel.startsWith("server/api/") || rel.startsWith("server/routes/")) {
                                val route = rel.removePrefix("server/routes/").substringBeforeLast('.')
                                val method = route.substringAfterLast('.', "").uppercase().takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE") }
                                val clean = if (method != null) route.substringBeforeLast('.') else route
                                pathEndpoint(file, Routes.joinPath(clean.removeSuffix("index").removeSuffix("/")), EndpointKind.API, framework, listOf(method ?: "ANY"))
                            } else if (rel.startsWith("pages/") && ext == "vue") {
                                pathEndpoint(file, Routes.joinPath(rel.removePrefix("pages/").substringBeforeLast('.').removeSuffix("index").removeSuffix("/")), EndpointKind.PAGE, framework, listOf("ANY"))
                            }
                        }
                        "SvelteKit" -> {
                            if (!rel.startsWith("src/routes/")) continue
                            val dir = rel.removePrefix("src/routes/").substringBeforeLast('/', "")
                            val name = rel.substringAfterLast('/')
                            val path = Routes.joinPath(*dir.split('/').filter { s -> s.isNotEmpty() && !(s.startsWith("(") && s.endsWith(")")) }.toTypedArray())
                            if (name.startsWith("+server.")) pathEndpoint(file, path, EndpointKind.API, framework, exportedMethods(file))
                            if (name == "+page.svelte") pathEndpoint(file, path, EndpointKind.PAGE, framework, listOf("ANY"))
                        }
                    }
                }
            }
        }
    }

    private fun exportedMethods(file: String): List<String> =
        index.declared[file].orEmpty().keys.filter { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS") }.ifEmpty { listOf("ANY") }

    private fun pathEndpoint(file: String, path: String, kind: EndpointKind, framework: String, methods: List<String>) {
        for (m in methods) {
            val r = RouteFact(kind, m, path, 1, framework, handlerLabel = file.substringAfterLast('/'))
            addEndpoint(r, file, path, true, framework)
        }
    }

    // ================================================================== llamadas HTTP

    private class CallUrl(val host: String?, val local: Boolean, val path: String, val prefixDynamic: Boolean)

    private fun parseUrl(raw: String): CallUrl {
        var url = raw.substringBefore('?').substringBefore('#')
        var prefixDynamic = url.startsWith(DYNAMIC)
        if (url.contains("://")) {
            val afterScheme = url.substringAfter("://")
            val host = afterScheme.substringBefore('/')
            val path = "/" + afterScheme.substringAfter('/', "")
            if (DYNAMIC in host) return CallUrl(null, false, path, true)
            val bare = host.substringBefore(':').lowercase()
            val local = bare in LOCAL_HOSTS || !bare.contains('.') && bare.isNotEmpty()
            return CallUrl(host.lowercase(), local, path, false)
        }
        if (prefixDynamic) url = url.trimStart(DYNAMIC[0])
        if (!url.startsWith("/")) {
            prefixDynamic = true
            url = "/$url"
        }
        return CallUrl(null, true, url, prefixDynamic)
    }

    private enum class Quality { EXACT, WEAK, SUFFIX }

    /** Compara los segmentos de una llamada con los de una ruta. */
    private fun match(call: List<String>, ep: Ep, suffix: Boolean): Quality? {
        val segs = ep.segs
        if (!suffix && !ep.rest && call.size != segs.size) return null
        if (ep.rest && call.size < segs.size) return null
        if (suffix && call.size > segs.size && !ep.rest) return null
        var weak = false
        val offset = if (suffix && !ep.rest) segs.size - call.size else 0
        if (offset < 0) return null
        val caseInsensitive = ep.framework == "ASP.NET Core"
        for (i in call.indices) {
            val s = segs.getOrNull(i + offset) ?: return if (ep.rest) (if (weak) Quality.WEAK else Quality.EXACT) else null
            val c = call[i]
            when {
                s.param -> Unit
                DYNAMIC in c -> {
                    val literal = c.replace(DYNAMIC, "")
                    if (c == DYNAMIC) weak = true else if (!s.text.startsWith(literal.substringBefore(DYNAMIC)) && literal.isNotEmpty()) return null else weak = true
                }
                c == s.text -> Unit
                caseInsensitive && c.equals(s.text, ignoreCase = true) -> Unit
                c.equals(s.text, ignoreCase = true) -> weak = true
                else -> return null
            }
        }
        if (suffix) return Quality.SUFFIX
        return if (weak) Quality.WEAK else Quality.EXACT
    }

    private fun http() {
        val apis = eps.filter { it.route.kind == EndpointKind.API }
        val retrofitBases = facts.values.flatMap { it.baseUrls }.map { it.first }.filter { it.startsWith("retrofit:") }.map { it.removePrefix("retrofit:") }.distinct()
        val axiosDefaults = facts.values.flatMap { it.baseUrls }.map { it.first }.filter { it.startsWith("axios:") }.map { it.removePrefix("axios:") }.distinct()
        for ((file, f) in facts) {
            for (c in f.calls) {
                httpCalls++
                var raw = c.url
                if (c.retrofit && !raw.contains("://")) raw = if (retrofitBases.size == 1) HttpCalls.joinUrl(retrofitBases.single(), raw) else DYNAMIC + "/" + raw.trimStart('/')
                if (c.client == "axios" && !raw.contains("://") && axiosDefaults.size == 1) raw = HttpCalls.joinUrl(axiosDefaults.single(), raw)
                val u = parseUrl(raw)
                val display = (if (u.prefixDynamic) "…" else "") + u.path.replace(DYNAMIC, "{…}")
                val label = "${c.method} $display" + if (c.fields.isNotEmpty()) " {${c.fields.joinToString(", ")}}" else ""
                // Archivo del servidor relativo a la página (formularios y scripts PHP, ASP…).
                if (u.prefixDynamic && Regexes.isScriptPath(c.url) && !c.url.startsWith(DYNAMIC)) {
                    val r = resolver.resolveResource(sf(file), ResourceRef(c.url.substringBefore('?'), c.line, RelationKind.HTTP, c.client))
                    if (r.target != null) {
                        httpMatched++
                        sink.add(file, c.line, Resolution(r.target, confidence = r.confidence, how = "${c.client}: ${r.how}", kind = RelationKind.HTTP, exchanged = listOf(label.take(RecordValidator.MAX_NAME_LENGTH))), c.url, RelationKind.HTTP, "Petición $label")
                        continue
                    }
                }
                val callSegs = u.path.split('/').filter { it.isNotEmpty() }
                if (u.host != null && !u.local) {
                    // Servicio externo: si la ruta coincide exactamente con una del proyecto se indica, sin afirmarlo.
                    val same = apis.filter { match(callSegs, it, false) == Quality.EXACT && methodOk(c.method, it) }
                    if (same.isNotEmpty() && same.distinctBy { it.handlerFile ?: it.file }.size == 1) {
                        linkCall(file, c, label, same, RelationConfidence.UNVERIFIED, "la URL apunta a ${u.host}; coincide con la ruta ${same.first().route.method} ${same.first().path} del proyecto (puede ser el mismo backend desplegado)")
                        continue
                    }
                    sink.add(file, c.line, Resolution(external = u.host, confidence = RelationConfidence.VERIFIED, how = "URL escrita en el código (${c.client})", kind = RelationKind.HTTP, exchanged = listOf(label.take(RecordValidator.MAX_NAME_LENGTH))), raw.replace(DYNAMIC, "{…}").substringBefore('?'), RelationKind.HTTP, "Llama a un servicio externo: $label")
                    continue
                }
                if (callSegs.isEmpty() && !u.prefixDynamic) continue
                val exact = apis.mapNotNull { e -> match(callSegs, e, false)?.let { e to it } }
                val withMethod = exact.filter { (e, _) -> methodOk(c.method, e) }
                when {
                    !u.prefixDynamic && withMethod.any { it.second == Quality.EXACT } -> {
                        val best = withMethod.filter { it.second == Quality.EXACT }.map { it.first }
                        val distinct = best.distinctBy { it.handlerFile ?: it.file }
                        val allKnown = best.all { it.known }
                        val conf = if (distinct.size == 1 && allKnown) RelationConfidence.PROBABLE else RelationConfidence.UNVERIFIED
                        val how = when {
                            distinct.size > 1 -> "coincide con ${distinct.size} rutas del proyecto; no se puede saber cuál atiende la llamada"
                            !allKnown -> "coincide con la ruta, pero no se conoce el prefijo completo con el que se monta"
                            else -> "ruta HTTP literal idéntica en el backend"
                        }
                        linkCall(file, c, label, best, conf, how)
                    }
                    !u.prefixDynamic && exact.any { it.second == Quality.EXACT } -> {
                        val e = exact.first { it.second == Quality.EXACT }.first
                        linkCall(file, c, label, listOf(e), RelationConfidence.UNVERIFIED, "la ruta existe en el backend, pero con el método ${e.route.method}: posible error")
                    }
                    withMethod.isNotEmpty() -> linkCall(file, c, label, withMethod.map { it.first }.take(3), RelationConfidence.UNVERIFIED, "coincidencia parcial: parte de la URL se construye al ejecutar")
                    u.prefixDynamic -> {
                        val suffix = apis.filter { e -> match(callSegs, e, true) != null && methodOk(c.method, e) && callSegs.isNotEmpty() && callSegs.any { DYNAMIC !in it } }
                        if (suffix.isNotEmpty() && suffix.distinctBy { it.handlerFile ?: it.file }.size <= 3) {
                            linkCall(file, c, label, suffix.take(3), RelationConfidence.UNVERIFIED, "coincidencia parcial: la URL empieza por una parte que solo se conoce al ejecutar (URL base)")
                        } else {
                            unmatched(file, c, label, apis.isNotEmpty(), display)
                        }
                    }
                    else -> unmatched(file, c, label, apis.isNotEmpty(), display)
                }
            }
        }
        for (ep in eps) {
            ep.routeKey?.let { sink.affected(it, ep.callers) }
            ep.callerKeys.forEach { sink.affected(it, ep.callers) }
        }
    }

    private fun methodOk(method: String, e: Ep): Boolean = method == "ANY" || e.route.method == "ANY" || e.route.method == method || method == "HEAD" && e.route.method == "GET"

    private fun linkCall(file: String, c: HttpCallFact, label: String, targets: List<Ep>, confidence: RelationConfidence, how: String) {
        httpMatched++
        for (e in targets.distinctBy { it.handlerFile ?: it.file }.take(3)) {
            val target = e.handlerFile ?: e.file
            e.callers += place(file, c.line)
            if (target == file) continue
            val routeAt = place(e.file, e.route.line)
            val handler = e.route.handlerLabel?.let { " → $it" } ?: ""
            val key = sink.add(
                file, c.line,
                Resolution(target, confidence = confidence, how = "$how (${e.route.method} ${e.path}$handler, $routeAt)", kind = RelationKind.HTTP, exchanged = listOf(label.take(RecordValidator.MAX_NAME_LENGTH))),
                c.url.replace(DYNAMIC, "{…}").substringBefore('?'), RelationKind.HTTP, "Petición $label (${c.client})",
                extra = e.file to e.route.line,
            )
            if (key != null) e.callerKeys += key
        }
    }

    private fun unmatched(file: String, c: HttpCallFact, label: String, hasBackend: Boolean, display: String) {
        val how = if (hasBackend) "ninguna ruta del backend analizado coincide: posible error, o una API que no está en el comprimido" else "el servidor de esta ruta no está en el comprimido"
        if (hasBackend && unmatchedCalls.size < 200) unmatchedCalls += "${place(file, c.line)} $label"
        sink.add(file, c.line, Resolution(external = "${c.method} $display".take(RecordValidator.MAX_NAME_LENGTH), confidence = RelationConfidence.UNVERIFIED, how = how, kind = RelationKind.HTTP, exchanged = listOf(label.take(RecordValidator.MAX_NAME_LENGTH))), c.url.replace(DYNAMIC, "{…}"), RelationKind.HTTP, "Petición $label (${c.client})")
    }

    // ================================================================== datos

    private class Entity(val name: String, val kind: DataEntityKind) {
        var def: Pair<String, TableDefFact>? = null
        val readers = LinkedHashSet<String>()
        val places = mutableListOf<String>()
        val operations = LinkedHashSet<String>()
        val keys = mutableListOf<String>()
    }

    private fun data() {
        val byTable = HashMap<String, MutableList<Pair<String, TableDefFact>>>()
        val byModel = HashMap<String, MutableList<Pair<String, TableDefFact>>>()
        for ((file, f) in facts) {
            for (t in f.tables) {
                byTable.getOrPut(Data.normalize(t.name)) { mutableListOf() } += file to t
                t.model?.let { byModel.getOrPut(it.lowercase()) { mutableListOf() } += file to t }
            }
        }
        val entityMap = LinkedHashMap<String, Entity>()
        fun entity(name: String, kind: DataEntityKind): Entity = entityMap.getOrPut("${kind.name}|${Data.normalize(name)}") { Entity(name, kind) }
        byTable.values.forEach { defs -> defs.first().let { (file, t) -> entity(t.name, t.kind).def = file to t } }

        for ((file, f) in facts) {
            for (u in f.dbUses) {
                var def: Pair<String, TableDefFact>? = null
                var confidence = RelationConfidence.VERIFIED
                var how = ""
                if (u.model != null) {
                    val cls = findClass(file, u.model)
                    val viaClass = cls?.let { c -> facts[c.file]?.tables?.firstOrNull { it.model.equals(u.model, ignoreCase = true) }?.let { c.file to it } }
                    if (cls != null && viaClass != null) {
                        def = viaClass
                        confidence = cls.confidence
                        how = "modelo «${u.model}» (${cls.how})"
                    } else {
                        val candidates = byModel[u.model.lowercase()].orEmpty()
                        if (candidates.size == 1) {
                            def = candidates.single()
                            confidence = RelationConfidence.PROBABLE
                            how = "único modelo «${u.model}» del proyecto"
                        }
                    }
                    if (def == null) continue
                } else if (u.table != null) {
                    val candidates = byTable[Data.normalize(u.table)].orEmpty()
                    if (candidates.isNotEmpty()) {
                        def = candidates.firstOrNull { it.first == file } ?: candidates.first()
                        confidence = if (def.second.conventional || candidates.size > 1) RelationConfidence.PROBABLE else RelationConfidence.VERIFIED
                        how = when {
                            candidates.size > 1 -> "la tabla se define en ${candidates.size} archivos"
                            def.second.conventional -> "nombre de la tabla por convención del ORM (${def.second.source})"
                            else -> "la tabla se define con ${def.second.source}"
                        }
                    }
                }
                val name = def?.second?.name ?: u.table ?: continue
                val kind = def?.second?.kind ?: u.kind
                val e = entity(name, kind)
                if (e.def == null && def != null) e.def = def
                e.readers += file
                e.places += place(file, u.line)
                e.operations += u.operation
                val label = "${if (kind == DataEntityKind.COLLECTION) "colección" else "tabla"} $name: ${u.operation}"
                if (def != null) {
                    if (def.first == file) continue
                    sink.add(file, u.line, Resolution(def.first, confidence = confidence, how = "$how (${place(def.first, def.second.line)})", kind = RelationKind.DB_ACCESS, exchanged = listOf(label)), u.model ?: u.table ?: name, RelationKind.DB_ACCESS, "Accede a la $label (${u.via})")
                        ?.let { e.keys += it }
                } else {
                    val external = if (kind == DataEntityKind.COLLECTION) "colección $name" else "tabla $name"
                    val why = if (u.via == "Firestore" || u.via == "MongoDB") "las colecciones de ${u.via} se crean al escribir en ellas: no hay una definición en el código" else "la tabla no se define en el comprimido (se crea fuera, en migraciones no incluidas, o es un error)"
                    sink.add(file, u.line, Resolution(external = external, confidence = RelationConfidence.UNVERIFIED, how = why, kind = RelationKind.DB_ACCESS, exchanged = listOf(label)), name, RelationKind.DB_ACCESS, "Accede a la $label (${u.via})")
                        ?.let { e.keys += it }
                }
            }
        }
        for (e in entityMap.values.take(MAX_ENTITIES)) {
            e.keys.forEach { sink.affected(it, e.places) }
            entities += DataEntity(
                e.name.take(RecordValidator.MAX_NAME_LENGTH), e.kind, e.def?.first, e.def?.second?.line, e.def?.second?.source, e.readers.size,
                e.operations.take(RecordValidator.MAX_OPERATIONS).toList(),
            )
        }
    }

    // ================================================================== configuración

    private fun configKey(kind: ConfigKind, key: String): String = when (kind) {
        ConfigKind.SPRING -> key.lowercase().filter { it != '-' && it != '_' }
        ConfigKind.DOTNET -> key.lowercase()
        else -> key
    }

    private fun config() {
        val defs = HashMap<String, MutableList<Pair<String, ConfigDefFact>>>()
        for ((file, f) in facts) f.configDefs.forEach { d -> defs.getOrPut("${d.kind}|${configKey(d.kind, d.key)}") { mutableListOf() } += file to d }
        class Key(val kind: ConfigKind, val name: String) {
            val defined = LinkedHashSet<String>()
            val readers = mutableListOf<String>()
            val keys = mutableListOf<String>()
        }
        val keys = LinkedHashMap<String, Key>()
        for ((file, f) in facts) {
            for (u in f.configUses) {
                val prefix = u.key.endsWith(".*") || u.key.endsWith(":*")
                val candidates = if (prefix) {
                    val p = configKey(u.kind, u.key.dropLast(1))
                    defs.filterKeys { it.startsWith("${u.kind}|$p") }.values.flatten()
                } else {
                    defs["${u.kind}|${configKey(u.kind, u.key)}"].orEmpty()
                }.filter { it.first != file }
                val k = keys.getOrPut("${u.kind}|${configKey(u.kind, u.key)}") { Key(u.kind, u.key) }
                candidates.forEach { k.defined += it.first }
                k.readers += place(file, u.line)
                val label = when (u.kind) {
                    ConfigKind.ENV -> "variable de entorno ${u.key}"
                    ConfigKind.BUILD_CONFIG -> "BuildConfig.${u.key}"
                    ConfigKind.DJANGO -> "ajuste ${u.key}"
                    else -> "clave ${u.key}"
                }
                if (candidates.isNotEmpty()) {
                    val best = nearest(file, candidates.map { it.first }.distinct())
                    val d = candidates.first { it.first == best }.second
                    val template = best.substringAfterLast('/').lowercase().let { n -> listOf("example", "sample", "template", "dist", "defaults").any { n.contains(it) } }
                    val others = candidates.map { it.first }.distinct().size - 1
                    val how = "se define en ${place(best, d.line)} (${d.source})" + (if (template) "; es una plantilla: el valor real se pone al desplegar" else "") + if (others > 0) "; también en $others archivo${if (others > 1) "s" else ""} más" else ""
                    sink.add(file, u.line, Resolution(best, confidence = RelationConfidence.VERIFIED, how = how, kind = RelationKind.CONFIG_READ, exchanged = listOf(u.key)), u.key, RelationKind.CONFIG_READ, "Lee la $label (${u.via})")?.let { k.keys += it }
                } else {
                    val standard = u.kind == ConfigKind.ENV && u.key in Config.STANDARD_ENV
                    val how = if (standard) "variable estándar que pone el sistema o la plataforma" else "no se define en ningún archivo del comprimido: se configura al desplegar (o falta)"
                    sink.add(file, u.line, Resolution(external = label.take(RecordValidator.MAX_NAME_LENGTH), confidence = RelationConfidence.UNVERIFIED, how = how, kind = RelationKind.CONFIG_READ, exchanged = listOf(u.key)), u.key, RelationKind.CONFIG_READ, "Lee la $label (${u.via})")?.let { k.keys += it }
                }
            }
        }
        for (k in keys.values.take(MAX_CONFIG_KEYS)) {
            k.keys.forEach { sink.affected(it, k.readers) }
            configKeys += ConfigKey(k.name.take(RecordValidator.MAX_NAME_LENGTH), k.defined.take(RecordValidator.MAX_SAMPLES).toList(), k.readers.take(RecordValidator.MAX_SAMPLES), k.readers.size)
        }
    }

    /** El archivo más cercano (carpeta común más larga) a [from]. */
    private fun nearest(from: String, files: List<String>): String = files.maxByOrNull { f ->
        val a = from.split('/')
        val b = f.split('/')
        var n = 0
        while (n < a.size - 1 && n < b.size - 1 && a[n] == b[n]) n++
        n * 10 - (if (f.substringAfterLast('/').lowercase().let { x -> listOf("example", "sample", "template", "dist").any { x.contains(it) } }) 1 else 0)
    } ?: files.first()

    // ================================================================== inyección de dependencias

    private fun injection() {
        val binds = HashMap<String, MutableList<Pair<String, BindFact>>>()
        for ((file, f) in facts) f.binds.forEach { b -> binds.getOrPut(b.contract.substringAfterLast('.')) { mutableListOf() } += file to b }
        val implementors = HashMap<String, MutableList<String>>()
        for (sfacts in index.facts.values) {
            for (d in sfacts.declarations) for (st in d.supertypes) implementors.getOrPut(st.name.substringAfterLast('.').substringAfterLast('\\')) { mutableListOf() } += sfacts.path
        }
        val consumers = HashMap<String, MutableList<String>>()
        val keysByType = HashMap<String, MutableList<String>>()
        for ((file, f) in facts) {
            for (inj in f.injections) {
                val t = inj.type
                if (inj.via == "Depends") {
                    val found = resolveBinding(file, t.substringBefore('.')) ?: continue
                    if (found.file == file) continue
                    sink.add(file, inj.line, Resolution(found.file, confidence = RelationConfidence.PROBABLE, how = "dependencia de FastAPI: ${found.how}", kind = RelationKind.INJECTION, exchanged = listOf(t)), t, RelationKind.INJECTION, "Recibe «$t» por inyección (Depends)")
                    continue
                }
                val candidates = mutableListOf<Pair<String, String>>() // archivo → motivo
                for ((bindFile, b) in binds[t].orEmpty()) {
                    if (b.implementation.isEmpty()) {
                        candidates += bindFile to "se crea en ${place(bindFile, b.line)} (${b.via})"
                    } else {
                        findClass(bindFile, b.implementation)?.let { candidates += it.file to "registrado en ${place(bindFile, b.line)}: ${b.via}<$t, ${b.implementation}>" }
                    }
                }
                if (candidates.isEmpty()) {
                    implementors[t].orEmpty().filter { it != file }.distinct().forEach { candidates += it to "implementa «$t»" }
                }
                if (candidates.isEmpty()) {
                    val cls = findClass(file, t)
                    val kind = cls?.let { index.declared[it.file]?.get(t) }
                    if (cls != null && cls.file != file && kind != SymbolKind.INTERFACE) candidates += cls.file to "el framework entrega una instancia de «$t»"
                }
                val distinct = candidates.filter { it.first != file }.distinctBy { it.first }
                if (distinct.isEmpty()) continue
                val conf = if (distinct.size == 1) RelationConfidence.PROBABLE else RelationConfidence.UNVERIFIED
                consumers.getOrPut(t) { mutableListOf() } += place(file, inj.line)
                for ((target, why) in distinct.take(3)) {
                    val how = if (distinct.size > 1) "$why; hay ${distinct.size} candidatas y se decide al ejecutar" else why
                    sink.add(file, inj.line, Resolution(target, confidence = conf, how = how, kind = RelationKind.INJECTION, exchanged = listOf(t)), t, RelationKind.INJECTION, "Recibe «$t» por inyección de dependencias (${inj.via})")
                        ?.let { keysByType.getOrPut(t) { mutableListOf() } += it }
                }
            }
        }
        keysByType.forEach { (t, keys) -> keys.forEach { sink.affected(it, consumers[t].orEmpty()) } }
    }

    // ================================================================== Android

    private fun android() {
        // Recursos que son archivos: res/<tipo>[-calificadores]/<nombre>.<ext>
        val fileRes = HashMap<String, MutableList<String>>()
        for (f in index.files) {
            val parts = f.split('/')
            val r = parts.lastIndexOf("res")
            if (r < 0 || r + 2 != parts.size - 1) continue
            val type = parts[r + 1].substringBefore('-')
            if (type !in Platforms.FILE_RES) continue
            val name = parts.last().substringBefore('.')
            fileRes.getOrPut("$type|$name") { mutableListOf() } += f
        }
        val valueRes = HashMap<String, MutableList<Pair<String, Int>>>()
        for ((file, f) in facts) f.resDefs.forEach { d -> valueRes.getOrPut("${d.type}|${d.name}") { mutableListOf() } += file to d.line }
        val usesByRes = HashMap<String, MutableList<String>>()
        val keysByRes = HashMap<String, MutableList<String>>()
        for ((file, f) in facts) {
            for (u in f.resUses) {
                val key = "${u.type}|${u.name}"
                val files = fileRes[key].orEmpty().sortedBy { defaultQualifierRank(it) }
                val values = valueRes[key].orEmpty().sortedBy { defaultQualifierRank(it.first) }
                val target = files.firstOrNull() ?: values.firstOrNull()?.first
                val written = if (u.via.startsWith("@")) "@${u.type}/${u.name}" else if (u.via.startsWith("ViewBinding")) u.via.removePrefix("ViewBinding ") else "R.${u.type}.${u.name}"
                usesByRes.getOrPut(key) { mutableListOf() } += place(file, u.line)
                if (target == null) {
                    if (libraryResource(u.name)) continue
                    sink.add(file, u.line, Resolution(external = "recurso $written".take(RecordValidator.MAX_NAME_LENGTH), confidence = RelationConfidence.UNVERIFIED, how = "no está en res/ del proyecto (puede venir de una biblioteca o generarse al compilar)", kind = RelationKind.RESOURCE, exchanged = listOf(written)), written, RelationKind.RESOURCE, "Usa el recurso $written")
                    continue
                }
                if (target == file) continue
                val variants = files.size + values.size - 1
                val how = "recurso de Android en ${place(target, values.firstOrNull { it.first == target }?.second)}" + if (variants > 0) " (y $variants variante${if (variants > 1) "s" else ""} por idioma, tamaño o tema)" else ""
                sink.add(file, u.line, Resolution(target, confidence = RelationConfidence.VERIFIED, how = how, kind = RelationKind.RESOURCE, exchanged = listOf(written)), written, RelationKind.RESOURCE, "Usa el recurso $written")
                    ?.let { keysByRes.getOrPut(key) { mutableListOf() } += it }
            }
        }
        keysByRes.forEach { (k, keys) -> keys.forEach { sink.affected(it, usesByRes[k].orEmpty()) } }
        classRefs()
    }

    private fun defaultQualifierRank(path: String): Int = if (path.split('/').dropLast(1).lastOrNull()?.contains('-') == true) 1 else 0

    private fun libraryResource(name: String): Boolean =
        listOf("Theme", "Widget", "TextAppearance", "Base", "ThemeOverlay", "ShapeAppearance", "abc_", "material_", "mtrl_", "design_", "common_google_", "Animation").any { name.startsWith(it) }

    /** Clases nombradas en el manifiesto, layouts, grafos de navegación, Intents, XAML y FXML. */
    private fun classRefs() {
        for ((file, f) in facts) {
            for (c in f.classRefs) {
                var name = c.name
                if (file.endsWith("AndroidManifest.xml") && (name.startsWith(".") || !name.contains('.'))) {
                    val pkg = f.androidPackage ?: moduleNamespace(file) ?: continue
                    name = pkg + (if (name.startsWith(".")) name else ".$name")
                }
                val found = findClass(file, name) ?: siblingCodeBehind(file)
                if (found == null) {
                    val pkg = name.substringBeforeLast('.', "")
                    if (pkg.isNotEmpty() && pkg in index.jvmPackages) {
                        sink.add(file, c.line, Resolution(confidence = RelationConfidence.BROKEN, how = "${c.via}: la clase $name no existe en el proyecto (posible error)", kind = c.kind, exchanged = listOf(name.substringAfterLast('.'))), c.name, c.kind, "${c.via.replaceFirstChar { it.uppercase() }}: ${c.name}")
                    }
                    continue
                }
                if (found.file == file) continue
                sink.add(file, c.line, Resolution(found.file, confidence = found.confidence, how = "${c.via}; ${found.how}", kind = c.kind, exchanged = listOf(name.substringAfterLast('.'))), c.name, c.kind, "${c.via.replaceFirstChar { it.uppercase() }}: ${name.substringAfterLast('.')}")
            }
        }
    }

    /** Clase de un componente del manifiesto (`.SyncReceiver` → archivo). */
    private fun manifestClass(manifest: String, raw: String): String? {
        val name = if (raw.startsWith(".") || !raw.contains('.')) {
            val pkg = facts[manifest]?.androidPackage ?: moduleNamespace(manifest) ?: return null
            pkg + if (raw.startsWith(".")) raw else ".$raw"
        } else {
            raw
        }
        return findClass(manifest, name)?.file
    }

    private fun moduleNamespace(manifest: String): String? {
        val module = manifest.substringBefore("/src/", "").let { if (manifest.contains("/src/")) it else manifest.substringBeforeLast('/', "") }
        val prefix = if (module.isEmpty()) "" else "$module/"
        return listOf("${prefix}build.gradle.kts", "${prefix}build.gradle").firstNotNullOfOrNull { facts[it]?.androidPackage }
    }

    private fun siblingCodeBehind(file: String): Found? {
        if (!(file.endsWith(".xaml") || file.endsWith(".axaml"))) return null
        val sibling = "$file.cs"
        return if (index.exists(sibling)) Found(sibling, RelationConfidence.VERIFIED, "archivo de código asociado (${sibling.substringAfterLast('/')})") else null
    }

    // ================================================================== Unity

    private fun unity() {
        val guids = HashMap<String, String>()
        for ((file, f) in facts) {
            val g = f.guid ?: continue
            val asset = file.removeSuffix(".meta")
            if (index.exists(asset)) guids[g] = asset
        }
        var missing = 0
        for ((file, f) in facts) {
            val buildSettings = file.substringAfterLast('/').equals("EditorBuildSettings.asset", ignoreCase = true)
            for (u in f.guidUses) {
                val target = guids[u.guid]
                if (target == null) {
                    missing++
                    continue
                }
                if (target == file) continue
                val kind = when {
                    buildSettings -> RelationKind.REGISTRATION
                    target.endsWith(".cs") -> RelationKind.REFERENCE
                    else -> RelationKind.RESOURCE
                }
                val what = when (kind) {
                    RelationKind.REGISTRATION -> "Escena incluida en la compilación"
                    RelationKind.REFERENCE -> "Usa el script ${target.substringAfterLast('/')} como componente (${u.via})"
                    else -> "Usa ${target.substringAfterLast('/')} (${u.via})"
                }
                sink.add(file, u.line, Resolution(target, confidence = RelationConfidence.VERIFIED, how = "GUID ${u.guid} de ${target.substringAfterLast('/')}.meta", kind = kind, exchanged = listOf(target.substringAfterLast('/'))), u.guid, kind, what)
            }
        }
        if (missing > 0) {
            notes += CoverageReason.NOT_ANALYZED to "$missing referencias de Unity por GUID apuntan a recursos que no están en el comprimido: paquetes, recursos integrados o elementos que faltan («Missing»)"
        }
        val unityText = facts.keys.count { it.substringAfterLast('.').lowercase() in setOf("unity", "prefab") }
        val unityAll = index.files.count { it.substringAfterLast('.').lowercase() in setOf("unity", "prefab") }
        if (unityAll > unityText) {
            notes += CoverageReason.UNSUPPORTED to "${unityAll - unityText} escenas o prefabs de Unity no se pudieron leer como texto (serialización binaria o sin referencias): sus scripts no se enlazan"
        }
    }

    // ================================================================== rutas a archivos (Godot, CMake, Qt, Gradle, recursos)

    private fun paths() {
        for ((file, f) in facts) {
            for (p in f.pathRefs) {
                val v = p.value
                val r: Resolution = when {
                    v.startsWith("res://") -> resolver.resolveResource(sf(file), ResourceRef(v, p.line, p.kind, p.via))
                    p.via == "Resources.Load" -> searchFiles(index.files.filter { it.contains("/Resources/$v.") && !it.endsWith(".meta") }, "carpeta Resources de Unity", p.kind)
                    p.via == "LoadScene" -> searchFiles(index.files.filter { it == "$v.unity" || it.endsWith("/$v.unity") }, "escena de Unity por su nombre", p.kind)
                    !p.relative -> {
                        // getResource("/x") se busca desde las carpetas de recursos; "x" junto a la clase.
                        val candidates = if (v.startsWith("/")) index.files.filter { it.endsWith("/resources$v") } else index.files.filter { it.endsWith("/$v") }
                        searchFiles(candidates, "recurso buscado por su ruta en las carpetas del proyecto", p.kind)
                    }
                    v.endsWith("/build.gradle") -> {
                        val dir = file.substringBeforeLast('/', "")
                        val base = if (dir.isEmpty()) v else "$dir/$v"
                        listOf(base, "$base.kts").firstOrNull { index.exists(it) }?.let { Resolution(it, confidence = RelationConfidence.VERIFIED, how = "módulo de Gradle", kind = p.kind) }
                            ?: Resolution(confidence = RelationConfidence.BROKEN, how = "el módulo incluido no tiene build.gradle en el comprimido", kind = p.kind)
                    }
                    else -> resolver.resolveResource(sf(file), ResourceRef(v, p.line, p.kind, p.via)).let { res ->
                        // En CMake y Qt, un archivo que no está puede generarse al compilar: se dice, sin darlo por roto si es generado.
                        when {
                            res.confidence != RelationConfidence.BROKEN -> res
                            generated(v) -> Resolution(external = v, confidence = RelationConfidence.UNVERIFIED, how = "archivo que se genera al compilar", kind = p.kind)
                            !p.certain -> Resolution(external = v, confidence = RelationConfidence.UNVERIFIED, how = "la ruta se construye con variables y no se encontró en el comprimido", kind = p.kind)
                            else -> res
                        }
                    }
                }
                if (r.target == file) continue
                sink.add(file, p.line, Resolution(r.target, r.external, r.confidence, r.how, p.kind, listOf(v.substringAfterLast('/').take(RecordValidator.MAX_NAME_LENGTH))), v, p.kind, "${p.via.replaceFirstChar { it.uppercase() }}: «$v»")
            }
        }
    }

    private fun generated(path: String): Boolean {
        val n = path.substringAfterLast('/').lowercase()
        return n.startsWith("moc_") || n.startsWith("ui_") || n.startsWith("qrc_") || n.endsWith(".moc") || n.contains("generated") || n.contains("config.h")
    }

    private fun searchFiles(candidates: List<String>, how: String, kind: RelationKind): Resolution = when (candidates.size) {
        0 -> Resolution(confidence = RelationConfidence.BROKEN, how = "no se encontró en el comprimido", kind = kind)
        1 -> Resolution(candidates.single(), confidence = RelationConfidence.PROBABLE, how = how, kind = kind)
        else -> Resolution(candidates.first(), confidence = RelationConfidence.UNVERIFIED, how = "$how; hay ${candidates.size} candidatos", kind = kind)
    }

    // ================================================================== canales de mensajes

    private fun channels() {
        class End(val file: String, val fact: ChannelFact)
        val byKey = HashMap<String, MutableList<End>>()
        for ((file, f) in facts) {
            f.channels.forEach { c ->
                // El receptor de un broadcast es su clase, no el manifiesto que lo declara.
                val owner = c.owner?.let { manifestClass(file, it) }
                byKey.getOrPut("${c.bus}|${c.name}") { mutableListOf() } += End(owner ?: file, c)
            }
        }
        val busesWithListeners = byKey.values.flatten().filter { !it.fact.send }.map { it.fact.bus }.toSet()
        for ((_, ends) in byKey) {
            val senders = ends.filter { it.fact.send }
            val listeners = ends.filter { !it.fact.send }
            for (s in senders) {
                val targets = listeners.filter { it.file != s.file }.distinctBy { it.file }
                val busName = BUS_NAMES[s.fact.bus] ?: s.fact.bus
                if (targets.isEmpty()) {
                    // El puente de Electron solo cuenta si el proyecto expone ese nombre.
                    if (s.fact.bus == "bridge") continue
                    if (listeners.any { it.file == s.file }) continue
                    val how = if (s.fact.bus in busesWithListeners) "nadie escucha este canal en el proyecto: posible error" else "el otro extremo no está en el comprimido"
                    sink.add(s.file, s.fact.line, Resolution(external = "canal ${s.fact.name} ($busName)".take(RecordValidator.MAX_NAME_LENGTH), confidence = RelationConfidence.UNVERIFIED, how = how, kind = RelationKind.IPC, exchanged = listOf(s.fact.name)), s.fact.name, RelationKind.IPC, "Envía por el canal «${s.fact.name}» (${s.fact.via})")
                    continue
                }
                val conf = if (targets.size == 1) RelationConfidence.PROBABLE else RelationConfidence.UNVERIFIED
                val keys = mutableListOf<String>()
                for (t in targets.take(3)) {
                    sink.add(
                        s.file, s.fact.line,
                        Resolution(t.file, confidence = conf, how = "mismo nombre de canal en ${place(t.file, t.fact.line)} (${t.fact.via})" + if (targets.size > 1) "; lo escuchan ${targets.size} archivos" else "", kind = RelationKind.IPC, exchanged = listOf(s.fact.name)),
                        s.fact.name, RelationKind.IPC, "Envía por el canal «${s.fact.name}» de $busName (${s.fact.via})",
                    )?.let { keys += it }
                }
                keys.forEach { k -> sink.affected(k, senders.map { place(it.file, it.fact.line) }) }
            }
        }
    }

    companion object {
        private const val MAX_ENDPOINTS = 5_000
        private const val MAX_ENTITIES = 2_000
        private const val MAX_CONFIG_KEYS = 2_000
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "10.0.2.2", "10.0.3.2", "[::1]", "host.docker.internal")

        /** Frameworks con el prefijo de las rutas en la propia clase: sin montaje, la ruta es completa. */
        private val ROOT_GROUP_FRAMEWORKS = setOf("NestJS", "Spring", "JAX-RS", "ASP.NET Core")

        /** Frameworks cuyas rutas se declaran junto a su código (decoradores, anotaciones, atributos). */
        private val IN_FILE_FRAMEWORKS = setOf("NestJS", "Spring", "JAX-RS", "ASP.NET Core", "Flask", "FastAPI", "Laravel", "Django", "Next.js", "Nuxt", "SvelteKit")
        private val BUS_NAMES = mapOf(
            "electron" to "Electron (renderer → main)", "electron-renderer" to "Electron (main → renderer)", "bridge" to "contextBridge de Electron",
            "socket.io" to "socket.io", "flutter-channel" to "canal de plataforma de Flutter", "android-broadcast" to "broadcast de Android", "tauri" to "Tauri",
        )
    }

    /** Puntos de las rutas y sus llamadas, para los registros finales. */
    fun emitEndpoints(): List<Endpoint> {
        if (endpoints.isNotEmpty()) return endpoints
        for (e in eps) {
            if (e.route.kind == EndpointKind.PAGE) pages++
            endpointFiles += e.file
            endpoints += Endpoint(
                kind = e.route.kind,
                method = e.route.method.takeIf { it in RecordValidator.HTTP_METHODS } ?: "ANY",
                path = e.path.take(RecordValidator.MAX_ROUTE_LENGTH),
                framework = e.framework,
                file = e.file,
                line = e.route.line.coerceAtLeast(1),
                handler = e.route.handlerLabel?.take(RecordValidator.MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() },
                handlerFile = e.handlerFile,
                prefixKnown = e.known,
                callers = e.callers.distinct().take(RecordValidator.MAX_SAMPLES),
                callerCount = e.callers.distinct().size,
            )
        }
        return endpoints
    }
}
