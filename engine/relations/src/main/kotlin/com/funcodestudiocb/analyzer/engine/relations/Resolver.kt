package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.languages.ImportRef
import com.funcodestudiocb.analyzer.engine.languages.SymbolKind
import com.funcodestudiocb.analyzer.engine.relations.RelationCollector.Companion.join

/** Resultado de resolver una referencia: un archivo del proyecto, un paquete externo o nada (rota). */
class Resolution(
    val target: String? = null,
    val external: String? = null,
    val confidence: RelationConfidence,
    /** Cómo se resolvió, para la evidencia. */
    val how: String,
    val kind: RelationKind = RelationKind.IMPORT,
    val exchanged: List<String> = emptyList(),
    val exchangedNote: String? = null,
)

/** Índices del proyecto para resolver referencias: archivos, tipos por paquete y espacio de nombres, clases de Godot. */
class ProjectIndex(
    val files: Set<String>,
    val facts: Map<String, SourceFacts>,
    val configs: ProjectConfigs,
    dependencies: List<DeclaredDependency>,
) {
    private val lowerFiles: Map<String, String> = HashMap<String, String>().also { map -> files.forEach { map.putIfAbsent(it.lowercase(), it) } }
    private val byName: Map<String, List<String>> = files.groupBy { it.substringAfterLast('/') }
    val dirs: Set<String> = HashSet<String>().also { set ->
        files.forEach { f ->
            var d = f.substringBeforeLast('/', "")
            while (d.isNotEmpty() && set.add(d)) d = d.substringBeforeLast('/', "")
        }
    }

    /** Tipo o función de nivel superior: "paquete.Nombre" → archivos (Java y Kotlin). */
    val jvmSymbols = HashMap<String, MutableList<String>>()
    val jvmPackages = HashMap<String, MutableList<String>>()
    val csSymbols = HashMap<String, MutableList<String>>()
    val csNamespaces = HashMap<String, MutableList<String>>()
    val phpSymbols = HashMap<String, MutableList<String>>()
    val phpNamespaces = HashMap<String, MutableList<String>>()
    val gdClasses = HashMap<String, String>()

    /** Nombres declarados en cada archivo, con su tipo. */
    val declared: Map<String, Map<String, SymbolKind>>

    /** Nombres de dependencias declaradas, por ecosistema (en minúsculas). */
    val declaredNames: Map<Ecosystem, Set<String>> = dependencies.groupBy { it.ecosystem }.mapValues { (_, list) -> list.mapTo(HashSet()) { it.name.lowercase() } }

    init {
        val decl = HashMap<String, Map<String, SymbolKind>>()
        for (f in facts.values) {
            decl[f.path] = f.declarations.associate { it.name to it.kind }
            val names = f.declarations.map { it.name }
            when (f.language) {
                "java", "kotlin" -> {
                    val pkg = f.namespaces.firstOrNull() ?: ""
                    jvmPackages.getOrPut(pkg) { mutableListOf() } += f.path
                    names.forEach { jvmSymbols.getOrPut(if (pkg.isEmpty()) it else "$pkg.$it") { mutableListOf() } += f.path }
                }
                "csharp" -> {
                    val namespaces = f.namespaces.ifEmpty { listOf("") }
                    namespaces.forEach { ns ->
                        csNamespaces.getOrPut(ns) { mutableListOf() } += f.path
                        names.forEach { csSymbols.getOrPut(if (ns.isEmpty()) it else "$ns.$it") { mutableListOf() } += f.path }
                    }
                }
                "php" -> {
                    val ns = f.namespaces.firstOrNull()?.trim('\\') ?: ""
                    phpNamespaces.getOrPut(ns) { mutableListOf() } += f.path
                    names.forEach { phpSymbols.getOrPut(if (ns.isEmpty()) it else "$ns\\$it") { mutableListOf() } += f.path }
                }
                "gdscript" -> f.declarations.filter { it.kind == SymbolKind.CLASS }.forEach { gdClasses.putIfAbsent(it.name, f.path) }
            }
        }
        declared = decl
    }

    fun exists(path: String): Boolean = path in files

    /** El archivo, o el mismo con otras mayúsculas (proyectos hechos en Windows). */
    fun find(path: String): Pair<String, Boolean>? {
        if (path in files) return path to true
        return lowerFiles[path.lowercase()]?.let { it to false }
    }

    fun withName(name: String): List<String> = byName[name].orEmpty()
}

/**
 * Resuelve las importaciones y referencias de cada lenguaje con las reglas de su ecosistema. Nunca inventa un
 * destino: lo que no se encuentra es una relación rota (si debería estar en el proyecto) o un paquete externo.
 */
class Resolver(private val index: ProjectIndex) {

    fun resolve(file: SourceFacts, ref: ImportRef): List<Resolution> = when (file.language) {
        "javascript", "typescript", "tsx", "vue", "svelte" -> listOf(javascript(file, ref))
        "python" -> python(file, ref)
        "java", "kotlin" -> jvm(file, ref)
        "php" -> php(file, ref)
        "csharp" -> csharp(file, ref)
        "dart" -> listOf(dart(file, ref))
        "c", "cpp" -> listOfNotNull(include(file, ref))
        "css", "scss" -> listOf(relativeFile(file, ref.module, cssCandidates(ref.module, file.language == "scss")))
        "gdscript" -> listOfNotNull(gdscript(file, ref))
        else -> emptyList()
    }

    fun resolveResource(file: SourceFacts, ref: ResourceRef): Resolution {
        val value = ref.value
        if (file.language == "gdscript" || value.startsWith("res://")) return godotPath(file, value, ref.kind) ?: broken(value, "ruta res:// sin proyecto de Godot")
        if (value.startsWith("/")) {
            // Ruta absoluta del sitio: se busca desde las carpetas habituales de publicación.
            val dir = file.path.substringBeforeLast('/', "")
            val bases = generateSequence(dir) { if (it.isEmpty()) null else it.substringBeforeLast('/', "") }.toList()
            for (base in bases) {
                for (pub in listOf("", "public", "static", "www", "wwwroot", "dist")) {
                    val candidate = join(join(base, pub), value.trimStart('/'))
                    if (index.exists(candidate)) return Resolution(candidate, confidence = RelationConfidence.PROBABLE, how = "ruta absoluta del sitio, buscada desde ${pub.ifEmpty { "la carpeta" }} del proyecto", kind = ref.kind)
                }
            }
            return Resolution(external = value, confidence = RelationConfidence.UNVERIFIED, how = "ruta absoluta del servidor que no está en el comprimido", kind = ref.kind)
        }
        return relativeFile(file, value, listOf(value), ref.kind)
    }

    // ------------------------------------------------------------------ utilidades

    private fun broken(reference: String, why: String, kind: RelationKind = RelationKind.IMPORT) =
        Resolution(confidence = RelationConfidence.BROKEN, how = why, kind = kind)

    private fun dirOf(path: String) = path.substringBeforeLast('/', "")

    /** Prueba los candidatos relativos a la carpeta del archivo; si ninguno existe, la relación está rota. */
    private fun relativeFile(file: SourceFacts, reference: String, candidates: List<String>, kind: RelationKind = RelationKind.IMPORT): Resolution {
        val dir = dirOf(file.path)
        for (c in candidates) {
            val path = join(dir, c)
            val found = index.find(path) ?: continue
            return Resolution(
                found.first,
                confidence = if (found.second) RelationConfidence.VERIFIED else RelationConfidence.PROBABLE,
                how = if (found.second) "ruta relativa a la carpeta del archivo" else "ruta relativa con otras mayúsculas (funciona en Windows, falla en Linux y Android)",
                kind = kind,
            )
        }
        return broken(reference, "la ruta relativa no lleva a ningún archivo del comprimido", kind)
    }

    /** En SCSS, `@import "base"` puede ser `base.scss`, el parcial `_base.scss` o `base/_index.scss`. */
    private fun cssCandidates(spec: String, scss: Boolean): List<String> {
        if (!scss) return listOf(spec)
        val name = spec.substringAfterLast('/')
        val dir = if (spec.contains('/')) spec.substringBeforeLast('/') + "/" else ""
        return listOf(spec, "$spec.scss", "$spec.sass", "${dir}_$name.scss", "${dir}_$name", "$spec.css", "$spec/_index.scss", "$spec/index.scss")
    }

    // ------------------------------------------------------------------ JavaScript / TypeScript

    private val jsExtensions = listOf("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".vue", ".svelte", ".json", ".d.ts")

    private fun jsProbe(base: String): Pair<String, Boolean>? {
        for (ext in jsExtensions) index.find(base + ext)?.let { return it }
        // ESM de TypeScript: `./x.js` se refiere a `./x.ts`.
        for ((from, to) in listOf(".js" to ".ts", ".js" to ".tsx", ".jsx" to ".tsx", ".mjs" to ".mts")) {
            if (base.endsWith(from)) index.find(base.removeSuffix(from) + to)?.let { return it }
        }
        for (ext in jsExtensions.drop(1)) index.find("$base/index$ext")?.let { return it }
        return null
    }

    private fun nearestTsconfig(path: String): Pair<String, Pair<String?, Map<String, List<String>>>>? {
        var dir = dirOf(path)
        while (true) {
            index.configs.tsconfigs[dir]?.let { return dir to it }
            if (dir.isEmpty()) return null
            dir = dir.substringBeforeLast('/', "")
        }
    }

    private fun javascript(file: SourceFacts, ref: ImportRef): Resolution {
        val spec = ref.module
        val (exchanged, note) = exchanged(ref)
        fun found(path: Pair<String, Boolean>, how: String, base: RelationConfidence = RelationConfidence.VERIFIED) =
            Resolution(path.first, confidence = if (path.second) base else RelationConfidence.PROBABLE, how = how, exchanged = exchanged, exchangedNote = note)

        if (spec.startsWith("./") || spec.startsWith("../") || spec == "." || spec == "..") {
            val base = join(dirOf(file.path), spec)
            return jsProbe(base)?.let { found(it, "ruta relativa (con las extensiones e index que prueba el empaquetador)") }
                ?: Resolution(confidence = RelationConfidence.BROKEN, how = "la ruta relativa no lleva a ningún archivo del comprimido", exchanged = exchanged, exchangedNote = note)
        }
        // Alias de tsconfig/jsconfig (paths y baseUrl).
        nearestTsconfig(file.path)?.let { (dir, config) ->
            val (baseUrl, paths) = config
            val baseDir = join(dir, baseUrl ?: ".")
            for ((alias, targets) in paths) {
                val prefix = alias.removeSuffix("*")
                val matches = if (alias.endsWith("*")) spec.startsWith(prefix) else spec == alias
                if (!matches) continue
                val rest = spec.removePrefix(prefix)
                for (t in targets) {
                    val target = join(baseDir, t.replace("*", rest))
                    jsProbe(target)?.let { return found(it, "alias «$alias» de ${if (dir.isEmpty()) "" else "$dir/"}tsconfig") }
                }
            }
            if (baseUrl != null) jsProbe(join(baseDir, spec))?.let { return found(it, "baseUrl de ${if (dir.isEmpty()) "" else "$dir/"}tsconfig") }
        }
        // Convención de Vite/Vue CLI/Nuxt: @/ y ~/ apuntan a src/ (o a la raíz).
        if (spec.startsWith("@/") || spec.startsWith("~/")) {
            var dir = dirOf(file.path)
            while (true) {
                for (root in listOf("$dir/src", dir)) {
                    jsProbe(join(root.trimStart('/'), spec.substring(2)))?.let {
                        return found(it, "alias ${spec.take(2)} habitual (apunta a ${root.trimStart('/').ifEmpty { "la raíz" }})", RelationConfidence.PROBABLE)
                    }
                }
                if (dir.isEmpty()) break
                dir = dir.substringBeforeLast('/', "")
            }
            return Resolution(confidence = RelationConfidence.BROKEN, how = "el alias ${spec.take(2)} no lleva a ningún archivo", exchanged = exchanged, exchangedNote = note)
        }
        val pkg = if (spec.startsWith("@")) spec.split('/').take(2).joinToString("/") else spec.substringBefore('/')
        // Paquete de este mismo repositorio (monorepo).
        index.configs.npmPackages[pkg]?.let { (dir, main) ->
            val rest = spec.removePrefix(pkg).trimStart('/')
            val target = if (rest.isEmpty()) jsProbe(join(dir, main ?: "index")) ?: jsProbe(join(dir, "src/index")) else jsProbe(join(dir, rest)) ?: jsProbe(join(dir, "src/$rest"))
            target?.let { return found(it, "paquete «$pkg» de este mismo repositorio") }
        }
        val name = pkg.removePrefix("node:")
        if (spec.startsWith("node:") || name in NODE_BUILTINS) {
            return Resolution(external = "node:$name", confidence = RelationConfidence.VERIFIED, how = "módulo incluido en Node.js", exchanged = exchanged, exchangedNote = note)
        }
        val declared = name.lowercase() in index.declaredNames[Ecosystem.NPM].orEmpty()
        return Resolution(
            external = name,
            confidence = if (declared) RelationConfidence.VERIFIED else RelationConfidence.UNVERIFIED,
            how = if (declared) "paquete npm declarado en package.json" else "paquete npm que no está declarado en ningún package.json del comprimido",
            exchanged = exchanged,
            exchangedNote = note,
        )
    }

    private fun exchanged(ref: ImportRef): Pair<List<String>, String?> = when {
        ref.symbols.isNotEmpty() -> ref.symbols to null
        ref.wildcard -> emptyList<String>() to "todo lo que exporta"
        else -> emptyList<String>() to "el módulo completo"
    }

    // ------------------------------------------------------------------ Python

    private fun pythonRoots(file: SourceFacts): List<String> {
        val out = LinkedHashSet<String>()
        // Raíz de su paquete: se sube mientras la carpeta tenga __init__.py.
        var dir = dirOf(file.path)
        while (dir.isNotEmpty() && index.exists("$dir/__init__.py")) dir = dirOf(dir)
        out += dir
        var d = dirOf(file.path)
        while (true) {
            out += d
            if (index.dirs.contains(join(d, "src"))) out += join(d, "src")
            if (d.isEmpty()) break
            d = dirOf(d)
        }
        return out.toList()
    }

    private fun pyModule(base: String, module: String): String? {
        val rel = module.replace('.', '/')
        val candidates = if (rel.isEmpty()) listOf("$base/__init__.py".trimStart('/')) else listOf(join(base, "$rel.py"), join(base, "$rel/__init__.py"), join(base, "$rel.pyi"))
        return candidates.firstOrNull { index.exists(it) }
    }

    private fun python(file: SourceFacts, ref: ImportRef): List<Resolution> {
        val module = ref.module
        val (exchanged, note) = exchanged(ref)
        if (module.startsWith(".")) {
            val level = module.takeWhile { it == '.' }.length
            var base = dirOf(file.path)
            repeat(level - 1) { base = dirOf(base) }
            val rest = module.drop(level)
            if (rest.isEmpty()) {
                // `from . import vistas`: cada nombre puede ser un submódulo o algo definido en __init__.py.
                return ref.symbols.ifEmpty { listOf("") }.map { symbol ->
                    val sub = if (symbol.isEmpty()) null else pyModule(base, symbol)
                    val init = pyModule(base, "")
                    when {
                        sub != null -> Resolution(sub, confidence = RelationConfidence.VERIFIED, how = "submódulo del mismo paquete (importación relativa)", exchanged = listOf(symbol), exchangedNote = "el módulo completo")
                        init != null -> Resolution(init, confidence = RelationConfidence.VERIFIED, how = "__init__.py del paquete (importación relativa)", exchanged = listOfNotNull(symbol.ifEmpty { null }))
                        else -> Resolution(confidence = RelationConfidence.BROKEN, how = "el paquete de la importación relativa no tiene ese módulo", exchanged = listOfNotNull(symbol.ifEmpty { null }))
                    }
                }
            }
            val target = pyModule(base, rest)
            return listOf(
                target?.let { Resolution(it, confidence = RelationConfidence.VERIFIED, how = "importación relativa", exchanged = exchanged, exchangedNote = note) }
                    ?: Resolution(confidence = RelationConfidence.BROKEN, how = "la importación relativa no lleva a ningún módulo del comprimido", exchanged = exchanged, exchangedNote = note),
            ) + submodules(base, rest, ref)
        }
        for (root in pythonRoots(file)) {
            val target = pyModule(root, module) ?: continue
            if (target == file.path) continue
            return listOf(Resolution(target, confidence = RelationConfidence.VERIFIED, how = "módulo del proyecto (desde ${root.ifEmpty { "la raíz" }})", exchanged = exchanged, exchangedNote = note)) +
                submodules(root, module, ref)
        }
        val top = module.substringBefore('.')
        if (top in PYTHON_STDLIB) return listOf(Resolution(external = top, confidence = RelationConfidence.VERIFIED, how = "biblioteca estándar de Python", exchanged = exchanged, exchangedNote = note))
        // El paquete es del proyecto pero el submódulo no está.
        val localTop = pythonRoots(file).firstOrNull { root -> index.dirs.contains(join(root, top)) && index.exists(join(root, "$top/__init__.py")) }
        if (localTop != null) {
            return listOf(Resolution(confidence = RelationConfidence.BROKEN, how = "el paquete «$top» es del proyecto, pero no tiene «$module»", exchanged = exchanged, exchangedNote = note))
        }
        val distribution = PYTHON_ALIASES[top] ?: top
        val declared = index.declaredNames[Ecosystem.PYTHON].orEmpty().any { norm(it) == norm(distribution) || norm(it) == norm(top) }
        return listOf(
            Resolution(
                external = top,
                confidence = if (declared) RelationConfidence.VERIFIED else RelationConfidence.UNVERIFIED,
                how = if (declared) "paquete declarado en las dependencias" else "paquete que no está declarado en las dependencias del comprimido (o se declara con otro nombre)",
                exchanged = exchanged,
                exchangedNote = note,
            ),
        )
    }

    private fun norm(name: String) = name.lowercase().replace('_', '-').replace('.', '-')

    /** `from paquete import submodulo`: si el nombre es un archivo del paquete, también se enlaza. */
    private fun submodules(base: String, module: String, ref: ImportRef): List<Resolution> = ref.symbols.mapNotNull { symbol ->
        pyModule(base, if (module.isEmpty()) symbol else "$module.$symbol")?.let {
            Resolution(it, confidence = RelationConfidence.VERIFIED, how = "submódulo importado por su nombre", exchanged = listOf(symbol), exchangedNote = "el módulo completo")
        }
    }

    // ------------------------------------------------------------------ Java y Kotlin

    private fun jvm(file: SourceFacts, ref: ImportRef): List<Resolution> {
        val module = ref.module
        if (ref.wildcard) {
            val files = index.jvmPackages[module].orEmpty().filter { it != file.path }
            if (files.isNotEmpty()) {
                // Solo los archivos del paquete cuyos tipos se nombran en este archivo.
                val used = files.filter { f -> index.declared[f].orEmpty().keys.any { it in file.identifiers } }
                return used.take(MAX_FAN).map { f ->
                    Resolution(
                        f, confidence = RelationConfidence.PROBABLE, how = "importación de todo el paquete «$module»; se usa por nombre",
                        exchanged = index.declared[f].orEmpty().keys.filter { it in file.identifiers }.take(20), exchangedNote = null,
                    )
                }
            }
            return listOf(jvmExternal(module, ref, "todo el paquete"))
        }
        index.jvmSymbols[module]?.firstOrNull { it != file.path }?.let {
            return listOf(Resolution(it, confidence = RelationConfidence.VERIFIED, how = "clase o función del proyecto por su paquete", exchanged = ref.symbols))
        }
        // Clase anidada o miembro: paquete.Externa.Interna, paquete.Clase.Companion.funcion
        var outer = module
        repeat(MAX_NESTING) {
            if ('.' !in outer) return@repeat
            outer = outer.substringBeforeLast('.')
            index.jvmSymbols[outer]?.firstOrNull { it != file.path }?.let {
                return listOf(Resolution(it, confidence = RelationConfidence.VERIFIED, how = "clase anidada o miembro de una clase del proyecto", exchanged = ref.symbols))
            }
        }
        val simple = module.substringAfterLast('.')
        // Interfaz que se genera a partir de su archivo .aidl del proyecto.
        index.withName("$simple.aidl").firstOrNull()?.let {
            return listOf(Resolution(it, confidence = RelationConfidence.VERIFIED, how = "interfaz generada al compilar a partir de su archivo .aidl", exchanged = ref.symbols))
        }
        // El paquete es del proyecto pero la clase no está: código generado o un error.
        if (module.contains('.') && module.substringBeforeLast('.') in index.jvmPackages) {
            return if (isGenerated(simple)) {
                listOf(Resolution(external = module, confidence = RelationConfidence.UNVERIFIED, how = "clase que se genera al compilar (R, BuildConfig, ViewBinding, Room, Dagger…)", exchanged = ref.symbols))
            } else {
                listOf(Resolution(confidence = RelationConfidence.BROKEN, how = "el paquete es del proyecto pero no contiene «$simple» (posible error o código generado)", exchanged = ref.symbols))
            }
        }
        return listOf(jvmExternal(module, ref, null))
    }

    private fun jvmExternal(module: String, ref: ImportRef, note: String?): Resolution {
        val parts = module.split('.')
        if (parts.first() in JVM_PLATFORM) {
            return Resolution(external = parts.take(2).joinToString("."), confidence = RelationConfidence.VERIFIED, how = "biblioteca de la plataforma (Java, Kotlin o Android)", exchanged = ref.symbols, exchangedNote = note)
        }
        val key = parts.take(if (parts.first() in TLDS) 3 else 2).joinToString(".")
        val groups = index.declaredNames[Ecosystem.GRADLE].orEmpty() + index.declaredNames[Ecosystem.MAVEN].orEmpty()
        val match = groups.firstOrNull { dep ->
            val group = dep.substringBefore(':')
            group.isNotEmpty() && (module.startsWith("$group.") || module == group || group.endsWith(".${parts.first()}") || (parts.size > 1 && group.endsWith(".${parts[1]}")))
        }
        return Resolution(
            external = key,
            confidence = if (match != null) RelationConfidence.PROBABLE else RelationConfidence.UNVERIFIED,
            how = if (match != null) "biblioteca externa (probablemente la dependencia $match)" else "biblioteca externa que no se puede asociar a una dependencia declarada",
            exchanged = ref.symbols,
            exchangedNote = note,
        )
    }

    /** Por paquete o espacio de nombres: huella del nombre → (nombre, archivo que lo declara, tipo). */
    private val packageNames = HashMap<String, HashMap<Int, MutableList<Triple<String, String, SymbolKind>>>>()

    private fun namesOf(key: String, files: List<String>): HashMap<Int, MutableList<Triple<String, String, SymbolKind>>> =
        packageNames.getOrPut(key) {
            val map = HashMap<Int, MutableList<Triple<String, String, SymbolKind>>>()
            for (f in files.distinct()) {
                if (f.endsWith(".kts")) continue
                for ((name, kind) in index.declared[f].orEmpty()) {
                    map.getOrPut(name.hashCode()) { mutableListOf() } += Triple(name, f, kind)
                }
            }
            map
        }

    /**
     * Uso por nombre sin importación (mismo paquete o espacio de nombres): se recorren los identificadores del archivo
     * y se buscan entre los nombres declarados en el resto del paquete (coste proporcional al archivo, no al paquete).
     */
    fun samePackage(file: SourceFacts, imported: Set<String>): List<Resolution> {
        if (file.identifiers.isEmpty()) return emptyList()
        // Cada script de Kotlin (.kts, como build.gradle.kts) se compila aparte: no comparte paquete con otros.
        if (file.path.endsWith(".kts")) return emptyList()
        val (keys, label) = when (file.language) {
            "java", "kotlin" -> listOf("jvm:" + (file.namespaces.firstOrNull() ?: "")) to "paquete"
            "csharp" -> file.namespaces.map { "cs:$it" } to "espacio de nombres"
            "php" -> listOf("php:" + (file.namespaces.firstOrNull()?.trim('\\') ?: "")) to "espacio de nombres"
            else -> return emptyList()
        }
        val own = index.declared[file.path].orEmpty()
        val found = LinkedHashMap<String, MutableList<String>>()
        for (key in keys) {
            val files = when {
                key.startsWith("jvm:") -> index.jvmPackages[key.removePrefix("jvm:")].orEmpty()
                key.startsWith("cs:") -> index.csNamespaces[key.removePrefix("cs:")].orEmpty()
                else -> index.phpNamespaces[key.removePrefix("php:")].orEmpty()
            }
            if (files.size < 2) continue
            val names = namesOf(key, files)
            file.identifiers.forEach { hash ->
                val candidates = names[hash] ?: return@forEach
                // Un nombre declarado en muchos archivos del paquete es ambiguo: no se puede saber cuál se usa.
                if (candidates.size > MAX_AMBIGUOUS) return@forEach
                for ((name, other, kind) in candidates) {
                    if (other == file.path || name in own || name in imported) continue
                    // Las funciones y constantes de nivel superior (Kotlin, PHP) también se usan sin importar; los nombres
                    // muy cortos se descartan para no confundirlos con variables locales.
                    if ((kind == SymbolKind.FUNCTION || kind == SymbolKind.CONSTANT) && name.length < 4) continue
                    val list = found.getOrPut(other) { mutableListOf() }
                    if (name !in list) list += name
                }
            }
        }
        return found.entries.take(MAX_FAN).map { (other, names) ->
            Resolution(other, confidence = RelationConfidence.PROBABLE, how = "se usa por nombre en el mismo $label, sin importación", kind = RelationKind.REFERENCE, exchanged = names.sorted().take(20))
        }
    }

    // ------------------------------------------------------------------ PHP

    private fun php(file: SourceFacts, ref: ImportRef): List<Resolution> {
        val module = ref.module
        val isUse = !module.contains('/') && !module.endsWith(".php")
        if (!isUse) {
            val path = module.trimStart('/')
            val candidates = listOf(join(dirOf(file.path), path), path)
            candidates.firstNotNullOfOrNull { index.find(it) }?.let {
                return listOf(Resolution(it.first, confidence = if (it.second) RelationConfidence.VERIFIED else RelationConfidence.PROBABLE, how = "ruta del archivo incluido"))
            }
            if (path.endsWith("vendor/autoload.php")) {
                return listOf(Resolution(external = "composer/autoload", confidence = RelationConfidence.VERIFIED, how = "cargador automático de Composer (la carpeta vendor/ no forma parte del código del proyecto)"))
            }
            return listOf(broken(module, "el archivo incluido no está en el comprimido"))
        }
        val fqcn = module.trim('\\')
        index.phpSymbols[fqcn]?.firstOrNull { it != file.path }?.let {
            return listOf(Resolution(it, confidence = RelationConfidence.VERIFIED, how = "clase del proyecto por su espacio de nombres", exchanged = ref.symbols))
        }
        // PSR-4: prefijo del espacio de nombres → carpeta.
        for ((prefix, dir) in index.configs.psr4.sortedByDescending { it.first.length }) {
            if (prefix.isNotEmpty() && fqcn != prefix && !fqcn.startsWith("$prefix\\")) continue
            val rest = fqcn.removePrefix(prefix).trimStart('\\').replace('\\', '/')
            val path = join(dir, "$rest.php")
            index.find(path)?.let { return listOf(Resolution(it.first, confidence = RelationConfidence.VERIFIED, how = "autocarga PSR-4 de composer.json («$prefix» → $dir)", exchanged = ref.symbols)) }
            return listOf(Resolution(confidence = RelationConfidence.BROKEN, how = "según la autocarga PSR-4 debería estar en $path, que no existe", exchanged = ref.symbols))
        }
        val vendor = fqcn.split('\\').take(2).joinToString("\\")
        val known = PHP_VENDORS.entries.firstOrNull { fqcn.startsWith(it.key) }
        val declared = known != null && index.declaredNames[Ecosystem.PHP_COMPOSER].orEmpty().any { it.startsWith(known.value) }
        return listOf(
            Resolution(
                external = vendor,
                confidence = if (declared) RelationConfidence.VERIFIED else RelationConfidence.UNVERIFIED,
                how = if (declared) "biblioteca de Composer declarada (${known.value})" else "biblioteca externa que no se puede asociar a una dependencia declarada",
                exchanged = ref.symbols,
            ),
        )
    }

    // ------------------------------------------------------------------ C#

    private fun csharp(file: SourceFacts, ref: ImportRef): List<Resolution> {
        val module = ref.module
        if (!ref.wildcard) {
            index.csSymbols[module]?.firstOrNull { it != file.path }?.let {
                return listOf(Resolution(it, confidence = RelationConfidence.VERIFIED, how = "tipo del proyecto con alias (using X = …)", exchanged = ref.symbols))
            }
        }
        val files = index.csNamespaces[module].orEmpty().filter { it != file.path }
        if (files.isNotEmpty()) {
            val used = files.filter { f -> index.declared[f].orEmpty().keys.any { it in file.identifiers } }
            return used.take(MAX_FAN).map { f ->
                Resolution(
                    f, confidence = RelationConfidence.PROBABLE, how = "espacio de nombres «$module» importado; el tipo se usa por nombre",
                    exchanged = index.declared[f].orEmpty().keys.filter { it in file.identifiers }.take(20),
                )
            }
        }
        val top = module.substringBefore('.')
        if (top == "System" || module.startsWith("Microsoft.Extensions") || module.startsWith("Microsoft.AspNetCore") || top == "UnityEngine" || top == "UnityEditor") {
            return listOf(Resolution(external = module.split('.').take(2).joinToString("."), confidence = RelationConfidence.VERIFIED, how = "biblioteca de la plataforma (.NET o Unity)", exchangedNote = "todo el espacio de nombres"))
        }
        val nuget = index.declaredNames[Ecosystem.DOTNET].orEmpty()
        val match = nuget.firstOrNull { module.lowercase().startsWith(it) || it.startsWith(module.lowercase()) }
        return listOf(
            Resolution(
                external = module.split('.').take(2).joinToString("."),
                confidence = if (match != null) RelationConfidence.VERIFIED else RelationConfidence.UNVERIFIED,
                how = if (match != null) "paquete NuGet declarado ($match)" else "espacio de nombres externo que no se puede asociar a un paquete declarado",
                exchangedNote = "todo el espacio de nombres",
            ),
        )
    }

    // ------------------------------------------------------------------ Dart

    private fun dart(file: SourceFacts, ref: ImportRef): Resolution {
        val spec = ref.module
        val (exchanged, note) = exchanged(ref)
        if (spec.startsWith("dart:")) return Resolution(external = spec, confidence = RelationConfidence.VERIFIED, how = "biblioteca de Dart", exchanged = exchanged, exchangedNote = note)
        if (spec.startsWith("package:")) {
            val pkg = spec.removePrefix("package:").substringBefore('/')
            val rest = spec.removePrefix("package:$pkg/")
            index.configs.pubPackages[pkg]?.let { dir ->
                val path = join(dir, "lib/$rest")
                return index.find(path)?.let { Resolution(it.first, confidence = RelationConfidence.VERIFIED, how = "paquete «$pkg» de este proyecto (carpeta lib/)", exchanged = exchanged, exchangedNote = note) }
                    ?: Resolution(confidence = RelationConfidence.BROKEN, how = "el paquete «$pkg» es de este proyecto pero no tiene lib/$rest", exchanged = exchanged, exchangedNote = note)
            }
            val platform = pkg == "flutter" || pkg == "flutter_test" || pkg == "flutter_localizations"
            val declared = platform || pkg.lowercase() in index.declaredNames[Ecosystem.DART_PUB].orEmpty()
            return Resolution(
                external = pkg,
                confidence = if (declared) RelationConfidence.VERIFIED else RelationConfidence.UNVERIFIED,
                how = if (platform) "SDK de Flutter" else if (declared) "paquete declarado en pubspec.yaml" else "paquete que no está declarado en pubspec.yaml",
                exchanged = exchanged,
                exchangedNote = note,
            )
        }
        val r = relativeFile(file, spec, listOf(spec))
        return Resolution(r.target, r.external, r.confidence, r.how, r.kind, exchanged, note)
    }

    // ------------------------------------------------------------------ C y C++

    private fun include(file: SourceFacts, ref: ImportRef): Resolution? {
        val system = ref.module.startsWith("<")
        val spec = ref.module.removePrefix("<").removeSuffix(">")
        if (!system) index.find(join(dirOf(file.path), spec))?.let {
            return Resolution(it.first, confidence = if (it.second) RelationConfidence.VERIFIED else RelationConfidence.PROBABLE, how = "cabecera junto al archivo")
        }
        // Rutas de inclusión del proyecto: un único archivo cuya ruta termina igual.
        val name = spec.substringAfterLast('/')
        val candidates = index.withName(name).filter { it == spec || it.endsWith("/$spec") }
        if (candidates.size == 1) return Resolution(candidates.single(), confidence = RelationConfidence.PROBABLE, how = "cabecera del proyecto encontrada por su ruta (carpetas de inclusión)")
        if (candidates.size > 1) {
            val closest = candidates.maxBy { commonPrefix(it, file.path) }
            return Resolution(closest, confidence = RelationConfidence.UNVERIFIED, how = "hay ${candidates.size} cabeceras con esa ruta; se toma la más cercana")
        }
        // Las de sistema o de bibliotecas que no están en el comprimido no se registran como relación.
        if (system) return null
        // Qt: ui_x.h la genera uic a partir de x.ui; moc_*.cpp y *.moc los genera moc.
        if (name.startsWith("ui_") && name.endsWith(".h")) {
            val ui = name.removePrefix("ui_").removeSuffix(".h") + ".ui"
            val forms = index.withName(ui)
            if (forms.isNotEmpty()) {
                return Resolution(forms.maxBy { commonPrefix(it, file.path) }, confidence = RelationConfidence.PROBABLE, how = "cabecera que Qt (uic) genera al compilar a partir de $ui", kind = RelationKind.RESOURCE)
            }
            return Resolution(external = spec, confidence = RelationConfidence.UNVERIFIED, how = "cabecera que Qt (uic) genera al compilar")
        }
        if (name.startsWith("moc_") || name.endsWith(".moc")) {
            return Resolution(external = spec, confidence = RelationConfidence.UNVERIFIED, how = "archivo que Qt (moc) genera al compilar")
        }
        val dirPart = spec.substringBeforeLast('/', "")
        if (dirPart.isNotEmpty() && index.dirs.none { it == dirPart || it.endsWith("/$dirPart") }) {
            return Resolution(external = spec, confidence = RelationConfidence.UNVERIFIED, how = "cabecera de una biblioteca cuya carpeta no está en el comprimido")
        }
        if (ref.conditional) {
            return Resolution(external = spec, confidence = RelationConfidence.UNVERIFIED, how = "inclusión condicional (#if/#ifdef) de una cabecera que no está en el comprimido: solo se compila con ciertas opciones")
        }
        return broken(ref.module, "la cabecera no está en el comprimido (posible error, o se genera al compilar)")
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    // ------------------------------------------------------------------ GDScript

    private fun godotPath(file: SourceFacts, value: String, kind: RelationKind): Resolution? {
        if (!value.startsWith("res://")) {
            return relativeFile(file, value, listOf(value), kind)
        }
        val root = index.configs.godotRoots.filter { it.isEmpty() || file.path.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
        val path = join(root, value.removePrefix("res://"))
        return index.find(path)?.let { Resolution(it.first, confidence = RelationConfidence.VERIFIED, how = "ruta res:// del proyecto de Godot", kind = kind) }
            ?: Resolution(confidence = RelationConfidence.BROKEN, how = "la ruta res:// no lleva a ningún archivo del proyecto", kind = kind)
    }

    private fun gdscript(file: SourceFacts, ref: ImportRef): Resolution? {
        val kind = if (ref.extends) RelationKind.INHERITANCE else RelationKind.IMPORT
        val spec = ref.module
        if (spec.startsWith("res://") || spec.endsWith(".gd")) return godotPath(file, spec, kind) ?: broken(spec, "ruta res:// sin proyecto de Godot", kind)
        index.gdClasses[spec]?.takeIf { it != file.path }?.let {
            return Resolution(it, confidence = RelationConfidence.VERIFIED, how = "clase declarada con class_name en el proyecto", kind = kind, exchanged = listOf(spec))
        }
        return Resolution(external = "Godot", confidence = RelationConfidence.PROBABLE, how = "clase del motor Godot (no está declarada en el proyecto)", kind = kind, exchanged = listOf(spec))
    }

    companion object {
        const val MAX_FAN = 40
        private const val MAX_AMBIGUOUS = 8
        private const val MAX_NESTING = 3

        /** Clases que genera la compilación (R, BuildConfig, ViewBinding, Room, Dagger/Hilt, Navigation, gRPC…). */
        fun isGenerated(name: String): Boolean =
            name in setOf("R", "BuildConfig", "Manifest") ||
                listOf("Binding", "_Impl", "_Factory", "Directions", "Args", "_HiltModules", "Grpc", "OuterClass").any { name.endsWith(it) } ||
                listOf("Dagger", "Hilt_").any { name.startsWith(it) }
        private val JVM_PLATFORM = setOf("java", "javax", "jdk", "kotlin", "android", "dalvik", "sun")
        private val TLDS = setOf("com", "org", "io", "net", "dev", "me", "de", "es", "co", "uk", "fr", "app", "edu", "gov")
        private val PHP_VENDORS = mapOf(
            "Illuminate\\" to "laravel/", "Symfony\\" to "symfony/", "Doctrine\\" to "doctrine/", "GuzzleHttp\\" to "guzzlehttp/",
            "Monolog\\" to "monolog/", "PHPUnit\\" to "phpunit/", "Carbon\\" to "nesbot/carbon", "Laravel\\" to "laravel/",
        )
        private val PYTHON_ALIASES = mapOf(
            "yaml" to "pyyaml", "cv2" to "opencv-python", "sklearn" to "scikit-learn", "PIL" to "pillow", "bs4" to "beautifulsoup4",
            "dotenv" to "python-dotenv", "jwt" to "pyjwt", "dateutil" to "python-dateutil", "google" to "protobuf", "MySQLdb" to "mysqlclient",
            "psycopg2" to "psycopg2-binary", "attr" to "attrs", "magic" to "python-magic", "serial" to "pyserial", "Crypto" to "pycryptodome",
            "telegram" to "python-telegram-bot", "rest_framework" to "djangorestframework", "corsheaders" to "django-cors-headers",
        )
        val NODE_BUILTINS = setOf(
            "assert", "buffer", "child_process", "cluster", "console", "crypto", "dgram", "dns", "events", "fs", "http", "http2", "https",
            "module", "net", "os", "path", "perf_hooks", "process", "querystring", "readline", "stream", "string_decoder", "timers",
            "tls", "tty", "url", "util", "v8", "vm", "worker_threads", "zlib", "async_hooks", "inspector", "test",
        )
        val PYTHON_STDLIB = setOf(
            "abc", "argparse", "array", "ast", "asyncio", "base64", "binascii", "bisect", "builtins", "bz2", "calendar", "cmath", "codecs",
            "collections", "concurrent", "configparser", "contextlib", "contextvars", "copy", "csv", "ctypes", "dataclasses", "datetime",
            "decimal", "difflib", "dis", "email", "enum", "errno", "fnmatch", "fractions", "functools", "gc", "getpass", "gettext", "glob",
            "gzip", "hashlib", "heapq", "hmac", "html", "http", "imaplib", "importlib", "inspect", "io", "ipaddress", "itertools", "json",
            "keyword", "linecache", "locale", "logging", "lzma", "mailbox", "math", "mimetypes", "multiprocessing", "numbers", "operator",
            "os", "pathlib", "pickle", "platform", "pprint", "queue", "random", "re", "secrets", "select", "shlex", "shutil", "signal",
            "smtplib", "socket", "sqlite3", "ssl", "stat", "statistics", "string", "struct", "subprocess", "sys", "tarfile", "tempfile",
            "textwrap", "threading", "time", "timeit", "tkinter", "token", "tokenize", "traceback", "types", "typing", "unicodedata",
            "unittest", "urllib", "uuid", "venv", "warnings", "weakref", "webbrowser", "xml", "zipfile", "zlib", "zoneinfo", "__future__",
        )
    }
}
