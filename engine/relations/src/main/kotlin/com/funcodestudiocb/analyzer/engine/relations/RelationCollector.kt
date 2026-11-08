package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeYaml
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlMap
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlScalar
import com.funcodestudiocb.analyzer.engine.languages.CountMethod
import com.funcodestudiocb.analyzer.engine.languages.Declaration
import com.funcodestudiocb.analyzer.engine.languages.ImportRef
import com.funcodestudiocb.analyzer.engine.relations.framework.FrameworkExtractor
import com.funcodestudiocb.analyzer.engine.relations.framework.FrameworkFacts
import com.google.re2j.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Referencia a otro archivo que no es una importación del lenguaje (script de una página, imagen, `preload`…). */
data class ResourceRef(val value: String, val line: Int, val kind: RelationKind, val label: String)

/** Identificadores usados en un archivo, guardados como huellas (compacto: 4 bytes por nombre). */
class IdentifierSet(private val hashes: IntArray) {
    operator fun contains(name: String): Boolean = hashes.binarySearch(name.hashCode()) >= 0
    fun isEmpty(): Boolean = hashes.isEmpty()
    fun forEach(action: (Int) -> Unit) = hashes.forEach(action)

    companion object {
        val EMPTY = IdentifierSet(IntArray(0))
    }
}

/** Lo que hace falta de un archivo para resolver sus relaciones. El contenido no se conserva. */
class SourceFacts(
    val path: String,
    val language: String,
    val imports: List<ImportRef>,
    val declarations: List<Declaration>,
    val namespaces: List<String>,
    /** Nombres usados fuera de los comentarios (uso sin importación en el mismo paquete). */
    val identifiers: IdentifierSet,
    val resources: List<ResourceRef>,
)

/** Configuración del proyecto que cambia cómo se resuelven las importaciones. */
class ProjectConfigs {
    /** Carpeta de tsconfig/jsconfig → (baseUrl, alias → destinos). */
    val tsconfigs = HashMap<String, Pair<String?, Map<String, List<String>>>>()

    /** Nombre de paquete npm → (carpeta, archivo principal). */
    val npmPackages = HashMap<String, Pair<String, String?>>()

    /** Prefijo PSR-4 de Composer → carpetas. */
    val psr4 = mutableListOf<Pair<String, String>>()

    /** Nombre de paquete de pub → carpeta. */
    val pubPackages = HashMap<String, String>()

    /** Carpetas con project.godot (raíz de `res://`). */
    val godotRoots = mutableListOf<String>()
}

/**
 * Recoge, durante el recorrido del comprimido, las importaciones, declaraciones y referencias de cada archivo y la
 * configuración que afecta a su resolución. Trabaja sobre el texto ya redactado.
 */
class RelationCollector : RedactedTextListener {

    override val timingLabel = "símbolos e importaciones"
    val facts = LinkedHashMap<String, SourceFacts>()
    val configs = ProjectConfigs()
    private var identifierBudget = MAX_IDENTIFIER_CHARS
    var identifierBudgetExhausted = false
        private set

    /**
     * Archivos de código, por lenguaje, cuyas importaciones no se pudieron extraer porque se contaron con el
     * analizador léxico (tree-sitter no disponible, tiempo agotado o presupuesto del proyecto consumido).
     */
    val withoutImports = HashMap<String, Int>()

    /** Hechos de los analizadores de framework por archivo (Fase 5B). */
    internal val framework = HashMap<String, FrameworkFacts>()
    internal val extractor = FrameworkExtractor()
    private var frameworkFacts = 0

    /** Se alcanzó el límite de memoria de los analizadores de framework: los archivos restantes no se analizaron. */
    var frameworkLimitReached = false
        private set

    override fun onText(context: StageContext, file: RedactedText) {
        readConfig(file)
        if (file.state == ContentState.NOT_STORED_GENERATED) return
        if (facts.size >= MAX_FILES) return
        if (frameworkFacts < MAX_FRAMEWORK_FACTS) {
            extractor.extract(file)?.let { f ->
                framework[file.path] = f
                frameworkFacts += f.size()
            }
        } else {
            frameworkLimitReached = true
        }
        val lang = file.language ?: return
        val language = lang.id
        val analysis = file.analysis
        if (lang.grammar != null && lang.importTypes.isNotEmpty() && analysis?.method != CountMethod.SYNTAX) {
            withoutImports.merge(language, 1, Int::plus)
        }
        val imports = when (language) {
            "vue", "svelte" -> scriptImports(file)
            else -> analysis?.imports.orEmpty()
        }
        val resources = when (language) {
            "html" -> htmlRefs(file)
            "css", "scss" -> cssRefs(file)
            "gdscript" -> gdRefs(file)
            "vue", "svelte" -> htmlRefs(file)
            else -> emptyList()
        }
        val scssImports = if (language == "scss") scssImports(file) else emptyList()
        val declarations = analysis?.declarations.orEmpty()
        if (imports.isEmpty() && resources.isEmpty() && declarations.isEmpty() && scssImports.isEmpty() && language !in TRACKED_LANGUAGES) return
        val identifiers = if (language in IDENTIFIER_LANGUAGES) identifiers(file) else IdentifierSet.EMPTY
        facts[file.path] = SourceFacts(file.path, language, imports + scssImports, declarations, analysis?.namespaces.orEmpty(), identifiers, resources)
    }

    // ------------------------------------------------------------------ configuración

    private fun readConfig(file: RedactedText) {
        val name = file.info.lower
        val dir = file.info.directory
        when {
            name == "tsconfig.json" || name == "jsconfig.json" -> {
                val options = (SafeJson.parse(file.text) as? JsonObject)?.get("compilerOptions") as? JsonObject ?: return
                val baseUrl = (options["baseUrl"] as? JsonPrimitive)?.content
                val paths = (options["paths"] as? JsonObject)?.entries?.associate { (alias, targets) ->
                    alias to (targets as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                }.orEmpty()
                if (baseUrl != null || paths.isNotEmpty()) configs.tsconfigs[dir] = baseUrl to paths
            }
            name == "package.json" && !file.info.lowerDirs.contains("node_modules") -> {
                val root = SafeJson.parse(file.text) as? JsonObject ?: return
                val pkg = (root["name"] as? JsonPrimitive)?.content ?: return
                val main = listOf("source", "module", "main").firstNotNullOfOrNull { (root[it] as? JsonPrimitive)?.content }
                configs.npmPackages.putIfAbsent(pkg, dir to main)
            }
            name == "composer.json" -> {
                val root = SafeJson.parse(file.text) as? JsonObject ?: return
                for (section in listOf("autoload", "autoload-dev")) {
                    val map = ((root[section] as? JsonObject)?.get("psr-4") as? JsonObject) ?: continue
                    for ((prefix, value) in map) {
                        val dirs = when (value) {
                            is JsonPrimitive -> listOf(value.content)
                            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                            else -> emptyList()
                        }
                        dirs.forEach { d -> configs.psr4 += prefix.trimEnd('\\') to join(dir, d.trim('/')) }
                    }
                }
            }
            name == "pubspec.yaml" -> {
                val pkg = ((SafeYaml.parse(file.text) as? YamlMap)?.get("name") as? YamlScalar)?.value ?: return
                configs.pubPackages.putIfAbsent(pkg, dir)
            }
            name == "project.godot" -> configs.godotRoots += dir
        }
    }

    // ------------------------------------------------------------------ referencias que no son importaciones

    private fun lineOf(file: RedactedText, offset: Int): Int {
        val i = file.lineStarts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    private fun usable(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_REFERENCE || v.startsWith("#") || v.startsWith("//")) return false
        if (v.contains("://") || v.contains("{{") || v.contains("<%") || v.contains("\${") || v.contains("<?")) return false
        val lower = v.lowercase()
        return listOf("data:", "mailto:", "tel:", "javascript:", "about:", "blob:").none { lower.startsWith(it) }
    }

    private fun clean(value: String): String = value.trim().substringBefore('?').substringBefore('#')

    private fun htmlRefs(file: RedactedText): List<ResourceRef> {
        val out = mutableListOf<ResourceRef>()
        val m = HTML_REF.matcher(file.text)
        while (m.find() && out.size < MAX_REFS) {
            if (file.analysis?.inComment(m.start()) == true) continue
            val tag = m.group(1).lowercase()
            val value = m.group(3)
            if (!usable(value)) continue
            val (kind, label) = when (tag) {
                "script" -> RelationKind.IMPORT to "script"
                "link" -> if (m.group(0).lowercase().contains("stylesheet")) RelationKind.IMPORT to "hoja de estilos" else RelationKind.RESOURCE to "recurso enlazado"
                "a" -> RelationKind.RESOURCE to "enlace"
                "iframe" -> RelationKind.RESOURCE to "página incrustada"
                else -> RelationKind.RESOURCE to "imagen o multimedia"
            }
            out += ResourceRef(clean(value), lineOf(file, m.start()), kind, label)
        }
        return out
    }

    private fun cssRefs(file: RedactedText): List<ResourceRef> {
        val out = mutableListOf<ResourceRef>()
        val m = CSS_URL.matcher(file.text)
        while (m.find() && out.size < MAX_REFS) {
            if (file.analysis?.inComment(m.start()) == true) continue
            val value = m.group(1)
            if (usable(value)) out += ResourceRef(clean(value), lineOf(file, m.start()), RelationKind.RESOURCE, "url() de CSS")
        }
        return out
    }

    private fun scssImports(file: RedactedText): List<ImportRef> {
        val out = mutableListOf<ImportRef>()
        val m = SCSS_IMPORT.matcher(file.text)
        while (m.find() && out.size < MAX_REFS) {
            if (file.analysis?.inComment(m.start()) == true) continue
            m.group(1).split(',').map { it.trim().trim('"', '\'') }.filter { usable(it) && !it.startsWith("sass:") }.forEach {
                out += ImportRef(it, lineOf(file, m.start()))
            }
        }
        return out
    }

    private fun gdRefs(file: RedactedText): List<ResourceRef> {
        val out = mutableListOf<ResourceRef>()
        val m = GD_LOAD.matcher(file.text)
        while (m.find() && out.size < MAX_REFS) {
            if (file.analysis?.inComment(m.start()) == true) continue
            val value = m.group(2)
            val kind = if (value.endsWith(".gd") || value.endsWith(".cs")) RelationKind.IMPORT else RelationKind.RESOURCE
            out += ResourceRef(value, lineOf(file, m.start()), kind, m.group(1))
        }
        return out
    }

    /** `import … from "x"` dentro de los bloques `<script>` de Vue y Svelte (sin gramática propia). */
    private fun scriptImports(file: RedactedText): List<ImportRef> {
        val out = mutableListOf<ImportRef>()
        val m = ES_IMPORT.matcher(file.text)
        while (m.find() && out.size < MAX_REFS) {
            if (file.analysis?.inComment(m.start()) == true) continue
            out += ImportRef(m.group(1), lineOf(file, m.start()))
        }
        return out
    }

    /** Identificadores fuera de los comentarios (sin expresiones: un recorrido de caracteres), como huellas. */
    private fun identifiers(file: RedactedText): IdentifierSet {
        if (identifierBudget <= 0) {
            identifierBudgetExhausted = true
            return IdentifierSet.EMPTY
        }
        val text = file.text
        val out = HashSet<Int>()
        var i = 0
        while (i < text.length && out.size < MAX_IDENTIFIERS_PER_FILE) {
            val ch = text[i]
            if ((ch.isLetter() || ch == '_') && (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '$'))) {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                if (j - i in 2..MAX_IDENTIFIER && file.analysis?.inComment(i) != true) out += text.substring(i, j).hashCode()
                i = j
            } else {
                i++
            }
        }
        identifierBudget -= out.size
        return IdentifierSet(out.toIntArray().also { it.sort() })
    }

    companion object {
        const val MAX_FILES = 50_000
        private const val MAX_FRAMEWORK_FACTS = 200_000
        private const val MAX_REFS = 1_000
        private const val MAX_REFERENCE = 1_000
        private const val MAX_IDENTIFIERS_PER_FILE = 5_000
        private const val MAX_IDENTIFIER = 120
        private const val MAX_IDENTIFIER_CHARS = 6_000_000

        /** Lenguajes con uso por nombre sin importación dentro del mismo paquete o espacio de nombres. */
        val IDENTIFIER_LANGUAGES = setOf("java", "kotlin", "csharp", "php")

        /** Lenguajes cuyos archivos se registran aunque no importen ni declaren nada. */
        val TRACKED_LANGUAGES = setOf(
            "javascript", "typescript", "tsx", "python", "java", "kotlin", "php", "csharp", "dart", "c", "cpp", "css", "scss",
            "html", "gdscript", "vue", "svelte",
        )

        private val HTML_REF = Pattern.compile("""(?i)<(script|link|img|a|source|iframe|video|audio|embed)\b[^>]*?\s(src|href)\s*=\s*["']([^"'<>]*)["'][^>]*>""")
        private val CSS_URL = Pattern.compile("""url\(\s*["']?([^"')\s]+)["']?\s*\)""")
        private val SCSS_IMPORT = Pattern.compile("""@(?:import|use|forward)\s+((?:["'][^"']+["']\s*,?\s*)+)""")
        private val GD_LOAD = Pattern.compile("""\b(preload|load)\s*\(\s*["']([^"']+)["']\s*\)""")
        private val ES_IMPORT = Pattern.compile("""(?m)^\s*(?:import|export)\s+(?:[^'";]*?\s+from\s+)?["']([^"']+)["']""")

        /** Une una carpeta con una ruta relativa, resolviendo `.` y `..`; `null` si se sale del comprimido. */
        fun join(dir: String, relative: String): String {
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts += dir.split('/')
            for (part in relative.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else return "\u0000"
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }
    }
}
