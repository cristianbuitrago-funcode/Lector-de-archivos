package com.funcodestudiocb.analyzer.engine.technologies

import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeYaml
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlList
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlMap
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlNode
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlScalar
import com.google.re2j.Pattern

enum class SignalKind { DEPENDENCY, FILE, IMPORT, CONTENT, PATTERN }

/** Una señal de una regla del catálogo. [index] es su posición dentro de la tecnología (cada señal suma una vez). */
class Signal(
    val index: Int,
    val kind: SignalKind,
    val value: String,
    val weight: Double,
    val ecosystems: Set<Ecosystem>? = null,
    val languages: Set<String>? = null,
    val extensions: Set<String>? = null,
    /** Para contenido: limita los archivos por nombre o final de ruta. */
    val fileGlob: Glob? = null,
    val pattern: Pattern? = null,
    /** La versión declarada es la de la tecnología (no la de un complemento de compilación, por ejemplo). */
    val versioned: Boolean = true,
)

class TechnologyRule(
    val id: String,
    val name: String,
    val category: TechnologyCategory,
    val signals: List<Signal>,
    val mentions: List<String>,
    val confirmed: Double,
    val probable: Double,
)

/** Comodín `*` (cualquier cosa salvo `/`) sobre el nombre del archivo o, si lleva `/`, sobre el final de la ruta. */
class Glob(val text: String) {
    private val regex: Pattern = Pattern.compile(
        "^" + text.split('*').joinToString("[^/]*") { Pattern.quote(it) } + "$",
    )
    private val onPath = text.contains('/')

    fun matches(path: String): Boolean {
        if (!onPath) return regex.matcher(path.substringAfterLast('/')).matches()
        if (regex.matcher(path).matches()) return true
        // Final de la ruta a partir de cualquier carpeta.
        var i = path.indexOf('/')
        while (i >= 0) {
            if (regex.matcher(path.substring(i + 1)).matches()) return true
            i = path.indexOf('/', i + 1)
        }
        return false
    }
}

/**
 * Catálogo declarativo de tecnologías (YAML empaquetado con el motor). Se valida al cargarlo: un error en el catálogo
 * es un fallo del programa y lo detectan las pruebas.
 */
class TechnologyCatalog(val version: Int, val rules: List<TechnologyRule>) {

    companion object {
        private const val RESOURCE = "catalog.yaml"
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        /** Repeticiones acotadas grandes: RE2/J las compila con recursión profunda (ver Fase 3). */
        private val LARGE_REPETITION = Regex("""\{\s*\d*\s*,?\s*(\d{2,})\s*}""")

        private val ECOSYSTEMS = mapOf(
            "npm" to setOf(Ecosystem.NPM),
            "composer" to setOf(Ecosystem.PHP_COMPOSER),
            "jvm" to setOf(Ecosystem.GRADLE, Ecosystem.MAVEN),
            "gradle" to setOf(Ecosystem.GRADLE),
            "maven" to setOf(Ecosystem.MAVEN),
            "python" to setOf(Ecosystem.PYTHON),
            "pub" to setOf(Ecosystem.DART_PUB),
            "nuget" to setOf(Ecosystem.DOTNET),
            "go" to setOf(Ecosystem.GO),
            "cargo" to setOf(Ecosystem.RUST_CARGO),
            "rubygems" to setOf(Ecosystem.RUBY),
            "cocoapods" to setOf(Ecosystem.COCOAPODS),
            "cmake" to setOf(Ecosystem.CMAKE),
            "unity" to setOf(Ecosystem.UNITY),
            "unreal" to setOf(Ecosystem.UNREAL),
        )

        private val DEFAULT_WEIGHTS = mapOf(
            SignalKind.DEPENDENCY to 0.8,
            SignalKind.FILE to 0.5,
            SignalKind.IMPORT to 0.6,
            SignalKind.CONTENT to 0.4,
            SignalKind.PATTERN to 0.4,
        )

        val default: TechnologyCatalog by lazy {
            val text = TechnologyCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("Falta el catálogo de tecnologías")
            parse(text)
        }

        fun parse(text: String): TechnologyCatalog {
            val root = SafeYaml.parse(text) as? YamlMap ?: throw IllegalStateException("Catálogo de tecnologías ilegible")
            val version = (root["version"] as? YamlScalar)?.value?.toIntOrNull() ?: fail("sin versión")
            val list = root["technologies"] as? YamlList ?: fail("sin tecnologías")
            val ids = HashSet<String>()
            val rules = list.items.map { node ->
                val tech = node as? YamlMap ?: fail("entrada inválida en la línea ${node.line}")
                val id = tech.string("id") ?: fail("tecnología sin id (línea ${tech.line})")
                if (!ID.matches(id) || !ids.add(id)) fail("id inválido o repetido: $id")
                val name = tech.string("name")?.takeIf { it.length in 1..100 } ?: fail("$id: sin nombre")
                val categoryName = tech.string("category") ?: fail("$id: sin categoría")
                val category = TechnologyCategory.entries.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
                    ?: fail("$id: categoría desconocida $categoryName")
                val signalNodes = (tech["signals"] as? YamlList)?.items ?: fail("$id: sin señales")
                val signals = signalNodes.mapIndexed { i, s -> signal(id, i, s) }
                if (signals.isEmpty()) fail("$id: sin señales")
                val mentions = (tech["mentions"] as? YamlList)?.items?.map { (it as? YamlScalar)?.value ?: fail("$id: mención inválida") }.orEmpty()
                mentions.forEach { if (it.length < 3) fail("$id: mención demasiado corta: $it") }
                val thresholds = tech["thresholds"] as? YamlMap
                val confirmed = thresholds?.string("confirmed")?.toDoubleOrNull() ?: 0.8
                val probable = thresholds?.string("probable")?.toDoubleOrNull() ?: 0.5
                if (probable <= 0 || confirmed < probable || confirmed > 1) fail("$id: umbrales inválidos")
                TechnologyRule(id, name, category, signals, mentions, confirmed, probable)
            }
            return TechnologyCatalog(version, rules)
        }

        private fun signal(tech: String, index: Int, node: YamlNode): Signal {
            val map = node as? YamlMap ?: fail("$tech: señal inválida en la línea ${node.line}")
            val kind = when {
                map["dependency"] != null -> SignalKind.DEPENDENCY
                map["import"] != null -> SignalKind.IMPORT
                map["content"] != null -> SignalKind.CONTENT
                map["pattern"] != null -> SignalKind.PATTERN
                map["file"] != null -> SignalKind.FILE
                else -> fail("$tech: señal sin tipo en la línea ${map.line}")
            }
            val value = map.string(kind.name.lowercase())?.takeIf { it.isNotEmpty() } ?: fail("$tech: señal vacía en la línea ${map.line}")
            val weight = map.string("weight")?.toDoubleOrNull() ?: DEFAULT_WEIGHTS.getValue(kind)
            if (weight <= 0 || weight > 1) fail("$tech: peso fuera de rango en la línea ${map.line}")
            val ecosystems = map.string("ecosystem")?.let { ECOSYSTEMS[it] ?: fail("$tech: ecosistema desconocido $it") }
            val languages = map.strings("languages")?.toSet()
            val extensions = map.strings("in")?.map { it.lowercase() }?.toSet()
            val fileGlob = when (kind) {
                SignalKind.FILE -> Glob(value)
                SignalKind.CONTENT, SignalKind.PATTERN -> map.string("file")?.let(::Glob)
                else -> null
            }
            val pattern = if (kind == SignalKind.PATTERN) {
                val hint = map.string("hint") ?: fail("$tech: patrón sin texto de aviso (hint) en la línea ${map.line}")
                if (hint.isEmpty()) fail("$tech: hint vacío")
                if (LARGE_REPETITION.containsMatchIn(value)) fail("$tech: repetición acotada grande en un patrón (desborda la pila en Android)")
                Pattern.compile(value, Pattern.MULTILINE)
            } else {
                null
            }
            val stored = if (kind == SignalKind.PATTERN) map.string("hint")!! else value
            val versioned = map.string("version")?.let { it != "false" } ?: true
            return Signal(index, kind, stored, weight, ecosystems, languages, extensions, fileGlob, pattern, versioned)
        }

        private fun YamlMap.string(key: String): String? = (this[key] as? YamlScalar)?.value

        private fun YamlMap.strings(key: String): List<String>? = (this[key] as? YamlList)?.items?.mapNotNull { (it as? YamlScalar)?.value }

        private fun fail(message: String): Nothing = throw IllegalStateException("Catálogo de tecnologías: $message")
    }
}
