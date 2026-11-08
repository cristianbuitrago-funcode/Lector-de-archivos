package com.funcodestudiocb.analyzer.engine.technologies

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Technology
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.core.StageStoppedException
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyKeys
import com.funcodestudiocb.analyzer.engine.dependencies.label
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.inventory.rootOf
import com.funcodestudiocb.analyzer.engine.languages.LanguageKind

/** Un indicio encontrado para una tecnología. [signal] es `null` para las menciones (no suman). */
internal class Hit(
    val signal: Signal?,
    val kind: EvidenceKind,
    val detail: String,
    val path: String,
    val line: Int?,
    val version: String? = null,
    /** Peso con el que suma; por defecto, el de la señal. */
    val weight: Double = signal?.weight ?: 0.0,
    /** Está en un archivo de pruebas. */
    val inTest: Boolean = false,
)

/** Indicios de una tecnología: se guardan los primeros y se cuentan todos. */
internal class Hits {
    val kept = mutableListOf<Hit>()
    var total = 0
    val signals = HashMap<Int, Double>()
    val versions = LinkedHashSet<String>()
    val paths = LinkedHashSet<String>()

    /** Algún indicio (no una mención) fuera de las pruebas. */
    var outsideTests = false

    fun add(hit: Hit) {
        total++
        if (hit.signal != null && !hit.inTest) outsideTests = true
        hit.signal?.let { signals[it.index] = maxOf(signals[it.index] ?: 0.0, hit.weight) }
        hit.version?.let { if (versions.size < RecordValidator.MAX_TECH_VERSIONS) versions += it }
        if (paths.size < MAX_PATHS) paths += hit.path
        if (kept.size < MAX_KEPT) kept += hit
    }

    companion object {
        const val MAX_KEPT = 64
        const val MAX_PATHS = 500
    }
}

/**
 * Busca, en el texto redactado de cada archivo, las importaciones, los textos y los patrones del catálogo, y las
 * menciones en documentación y comentarios. Las coincidencias dentro de comentarios solo cuentan como mención.
 */
class TechnologySignalCollector(internal val catalog: TechnologyCatalog = TechnologyCatalog.default) : RedactedTextListener {

    override val timingLabel = "señales de tecnologías"

    internal val hits = HashMap<String, Hits>()
    var scannedFiles = 0
        private set

    /** Archivos que enumeran muchas tecnologías (catálogos, listas): sus textos cuentan como menciones, no como uso. */
    internal val listFiles = ArrayList<Pair<String, Int>>()

    private val importSignals = catalog.rules.flatMap { rule -> rule.signals.filter { it.kind == SignalKind.IMPORT }.map { rule to it } }
    private val contentSignals = catalog.rules.flatMap { rule ->
        rule.signals.filter { it.kind == SignalKind.CONTENT || it.kind == SignalKind.PATTERN }.map { rule to it }
    }

    /** Textos literales (o avisos de patrón) distintos, buscados todos a la vez en una sola pasada por archivo. */
    private val literals: List<String> = contentSignals.map { it.second.value }.distinct()
    private val signalsByLiteral: List<List<Pair<TechnologyRule, Signal>>> = literals.map { literal -> contentSignals.filter { it.second.value == literal } }
    private val literalSearch = AhoCorasick(literals)

    private val mentionTerms = catalog.rules.flatMap { rule -> rule.mentions.map { rule to it } }
    private val mentionSearch = AhoCorasick(mentionTerms.map { it.second }, ignoreCase = true)

    internal fun hitsOf(rule: TechnologyRule): Hits = hits.getOrPut(rule.id) { Hits() }

    override fun onText(context: StageContext, file: RedactedText) {
        if (file.state == ContentState.NOT_STORED_GENERATED) return
        scannedFiles++
        val prose = file.language?.kind == LanguageKind.PROSE || file.info.ext in setOf("md", "txt", "rst", "adoc")
        if (prose) {
            mentions(file, everywhere = true)
            return
        }
        imports(file)
        content(file)
        if ((file.analysis?.comments?.size ?: 0) > 0) mentions(file, everywhere = false)
    }

    private fun imports(file: RedactedText) {
        val analysis = file.analysis ?: return
        if (analysis.imports.isEmpty()) return
        val language = analysis.language.id
        val seen = HashSet<Pair<String, Int>>()
        for (ref in analysis.imports) {
            // `#include <SDL2/SDL.h>` se compara sin los <>.
            val module = ref.module.removePrefix("<").removeSuffix(">")
            for ((rule, signal) in importSignals) {
                if (signal.languages != null && language !in signal.languages) continue
                if (!importMatches(module, signal.value)) continue
                if (!seen.add(rule.id to signal.index)) continue
                hitsOf(rule).add(Hit(signal, EvidenceKind.IMPORT, "Importa ${shorten(module)}", file.path, ref.line, inTest = isTestPath(file.info)))
            }
        }
    }

    private fun content(file: RedactedText) {
        val text = file.text
        // Primera aparición de cada texto fuera de los comentarios y dentro de ellos.
        val inCode = IntArray(literals.size) { -1 }
        val inComments = IntArray(literals.size) { -1 }
        literalSearch.scan(text) { index, start ->
            if (file.analysis?.inComment(start) == true) {
                if (inComments[index] < 0) inComments[index] = start
            } else if (inCode[index] < 0) {
                inCode[index] = start
            }
            true
        }
        // Un archivo con textos de muchas tecnologías distintas es un catálogo o una lista (de reglas, de dominios, de
        // ejemplos), no un proyecto que las use todas: sus coincidencias cuentan como menciones, nunca como uso.
        val inCodeRules = HashSet<String>()
        for (index in literals.indices) if (inCode[index] >= 0) signalsByLiteral[index].forEach { inCodeRules += it.first.id }
        val listing = inCodeRules.size >= LIST_THRESHOLD
        if (listing && listFiles.size < MAX_LIST_FILES) listFiles += file.path to inCodeRules.size
        for (index in literals.indices) {
            if (inCode[index] < 0 && inComments[index] < 0) continue
            for ((rule, signal) in signalsByLiteral[index]) {
                if (signal.extensions != null && file.info.ext !in signal.extensions) continue
                if (signal.fileGlob != null && !signal.fileGlob.matches(file.path)) continue
                var codeAt = inCode[index]
                var commentAt = inComments[index]
                if (signal.pattern != null) {
                    // El aviso apareció: se evalúa el patrón (RE2, tiempo lineal).
                    codeAt = -1
                    commentAt = -1
                    val m = signal.pattern.matcher(text)
                    while (m.find()) {
                        if (file.analysis?.inComment(m.start()) == true) {
                            if (commentAt < 0) commentAt = m.start()
                        } else {
                            codeAt = m.start()
                            break
                        }
                    }
                }
                val shown = if (signal.pattern != null) "un patrón de ${rule.name}" else "«${shorten(signal.value)}»"
                when {
                    codeAt >= 0 && listing -> hitsOf(rule).add(
                        Hit(null, EvidenceKind.MENTION, "Un archivo que enumera ${inCodeRules.size} tecnologías (catálogo o lista) contiene $shown", file.path, lineOf(file.lineStarts, codeAt)),
                    )
                    codeAt >= 0 && isTestPath(file.info) -> hitsOf(rule).add(
                        // Un texto en una prueba suele ser un dato de ejemplo: indica menos que en el código del producto.
                        Hit(signal, EvidenceKind.CONTENT, "Una prueba contiene $shown", file.path, lineOf(file.lineStarts, codeAt), weight = signal.weight * TEST_FACTOR, inTest = true),
                    )
                    codeAt >= 0 -> hitsOf(rule).add(Hit(signal, EvidenceKind.CONTENT, "El código contiene $shown", file.path, lineOf(file.lineStarts, codeAt)))
                    commentAt >= 0 -> hitsOf(rule).add(Hit(null, EvidenceKind.MENTION, "Comentario que menciona $shown", file.path, lineOf(file.lineStarts, commentAt)))
                }
            }
        }
    }

    /** Menciones de una tecnología por su nombre: en documentación (todo el texto) o en comentarios del código. */
    private fun mentions(file: RedactedText, everywhere: Boolean) {
        if (mentionTerms.isEmpty()) return
        val text = file.text
        val found = HashSet<String>()
        mentionSearch.scan(text) { index, at ->
            val (rule, term) = mentionTerms[index]
            if (rule.id in found) return@scan true
            val end = at + term.length
            val word = (at == 0 || !text[at - 1].isLetterOrDigit()) && (end >= text.length || !text[end].isLetterOrDigit())
            if (word && (everywhere || file.analysis?.inComment(at) == true)) {
                val where = if (everywhere) "La documentación menciona" else "Un comentario menciona"
                hitsOf(rule).add(Hit(null, EvidenceKind.MENTION, "$where «$term»", file.path, lineOf(file.lineStarts, at)))
                found += rule.id
            }
            true
        }
    }

    internal companion object {
        const val TEST_FACTOR = 0.5

        /** Tecnologías distintas a partir de las cuales un archivo se considera un catálogo o una lista. */
        const val LIST_THRESHOLD = 12
        private const val MAX_LIST_FILES = 20
        private val TEST_DIRS = setOf("test", "tests", "__tests__", "androidtest", "spec", "specs", "testing", "testdata", "test-data", "fixtures", "e2e", "cypress")

        /** Archivo de pruebas por su carpeta o su nombre (`FooTest.kt`, `test_foo.py`, `foo.spec.ts`). */
        fun isTestPath(info: com.funcodestudiocb.analyzer.engine.inventory.PathInfo): Boolean =
            info.lowerDirs.any { it in TEST_DIRS } ||
                info.stem.endsWith("test") || info.stem.endsWith("tests") || info.stem.startsWith("test_") ||
                info.lower.contains(".test.") || info.lower.contains(".spec.") || info.stem.endsWith("_spec")

        /** `firebase/firestore` coincide con `firebase`; `fsevents` no coincide con `fs`. */
        fun importMatches(module: String, value: String): Boolean {
            if (module == value) return true
            if (!module.startsWith(value)) return false
            if (value.last() in SEPARATORS) return true
            return module[value.length] in SEPARATORS
        }

        private const val SEPARATORS = "/.:\\"

        fun lineOf(starts: IntArray, offset: Int): Int {
            val i = starts.binarySearch(offset)
            return (if (i >= 0) i else -i - 2) + 1
        }

        fun shorten(text: String): String = text.filterNot { it.isISOControl() }.let { if (it.length > 120) it.take(117) + "…" else it }
    }
}

/**
 * Fase 4: tecnologías, bases de datos y servicios externos. Junta las dependencias declaradas, los archivos
 * característicos y los indicios del código, puntúa cada tecnología del catálogo y emite las que tienen alguna
 * evidencia, con dónde se encontró y su confianza.
 */
class TechnologyStage(private val signals: TechnologySignalCollector?) : Stage {
    /** Sin catálogo (no se pudo cargar), la etapa se detiene y la cobertura lo dice; el resto del análisis sigue. */
    private val collector: TechnologySignalCollector
        get() = signals ?: throw StageStoppedException("No se pudo cargar el catálogo de tecnologías: no se buscaron tecnologías")
    private val catalog: TechnologyCatalog get() = collector.catalog

    override val id = "technologies"
    override val title = "Tecnologías, bases de datos y servicios externos"
    override val dependsOn = setOf("dependencies")

    override suspend fun run(context: StageContext) {
        val dependencies = context.attributes[DependencyKeys.DECLARED].orEmpty()
        val files: List<FileEntry> = context.attributes[IngestKeys.FILES].orEmpty()
        val roots = context.attributes[InventoryKeys.ROOTS].orEmpty()

        context.progress(0.1f, "Comparando las dependencias con el catálogo")
        dependencySignals(dependencies)
        context.checkActive()
        context.progress(0.4f, "Buscando archivos característicos")
        fileSignals(files)
        context.checkActive()

        context.progress(0.8f, "Calculando la confianza de cada tecnología")
        val technologies = catalog.rules.mapNotNull { rule -> collector.hits[rule.id]?.let { build(rule, it, roots) } }
            .sortedWith(compareBy<Technology>({ it.confidence.ordinal }, { it.category.ordinal }, { it.name }))
        technologies.forEach(context::emit)

        context.coverage(
            CoverageReason.UNSUPPORTED,
            "Tecnologías reconocidas con el catálogo v${catalog.version} (${catalog.rules.size} tecnologías): una tecnología que no está en el catálogo no se detecta. " +
                "Las menciones en documentación o comentarios se listan como no concluyentes y nunca cuentan como uso.",
        )
        if (collector.listFiles.isNotEmpty()) {
            val shown = collector.listFiles.take(3).joinToString(", ") { (path, n) -> "${path.substringAfterLast('/')} ($n)" }
            context.coverage(
                CoverageReason.UNSUPPORTED,
                "${collector.listFiles.size} archivo(s) enumeran ${TechnologySignalCollector.LIST_THRESHOLD} o más tecnologías distintas y parecen un catálogo " +
                    "o una lista: sus textos se tratan como menciones, no como uso ($shown).",
            )
        }
    }

    private fun dependencySignals(dependencies: List<DeclaredDependency>) {
        if (dependencies.isEmpty()) return
        val normalized = dependencies.map { it to normalize(it.name, it.ecosystem) }
        for (rule in catalog.rules) {
            for (signal in rule.signals) {
                if (signal.kind != SignalKind.DEPENDENCY) continue
                val prefix = signal.value.endsWith("*")
                val target = signal.value.removeSuffix("*")
                val generic = normalize(target, Ecosystem.NPM)
                val python = normalize(target, Ecosystem.PYTHON)
                for ((d, name) in normalized) {
                    if (signal.ecosystems != null && d.ecosystem !in signal.ecosystems) continue
                    val wanted = if (d.ecosystem == Ecosystem.PYTHON) python else generic
                    if (if (prefix) !name.startsWith(wanted) else name != wanted) continue
                    val version = d.version?.let { " $it" }.orEmpty()
                    collector.hitsOf(rule).add(
                        Hit(signal, EvidenceKind.DEPENDENCY, "Dependencia declarada: ${d.name}$version (${d.ecosystem.label()})", d.manifest, d.line, d.version?.takeIf { signal.versioned }),
                    )
                }
            }
        }
    }

    private fun fileSignals(files: List<FileEntry>) {
        for (rule in catalog.rules) {
            for (signal in rule.signals) {
                if (signal.kind != SignalKind.FILE) continue
                val glob = signal.fileGlob ?: continue
                var found = 0
                for (file in files) {
                    if (!glob.matches(file.path)) continue
                    collector.hitsOf(rule).add(Hit(signal, EvidenceKind.FILE_NAME, "Archivo característico: ${glob.text}", file.path, null))
                    if (++found >= MAX_FILE_HITS) break
                }
            }
        }
    }

    private fun build(rule: TechnologyRule, hits: Hits, roots: List<com.funcodestudiocb.analyzer.engine.api.ProjectRoot>): Technology? {
        if (hits.kept.isEmpty()) return null
        val score = hits.signals.values.sum().coerceAtMost(1.0)
        val confidence = when {
            // Solo aparece en pruebas (datos de ejemplo, proyectos de prueba): no demuestra que el producto la use. Las
            // herramientas de pruebas son la excepción: es ahí donde se usan.
            !hits.outsideTests && rule.category != TechnologyCategory.TESTING -> Confidence.POSSIBLE
            score >= rule.confirmed - EPSILON -> Confidence.CONFIRMED
            score >= rule.probable - EPSILON -> Confidence.PROBABLE
            else -> Confidence.POSSIBLE
        }
        // Las evidencias más fuertes primero; las menciones al final.
        val order = listOf(EvidenceKind.DEPENDENCY, EvidenceKind.FILE_NAME, EvidenceKind.IMPORT, EvidenceKind.CONTENT, EvidenceKind.MENTION)
        val evidence = hits.kept
            .sortedWith(
                compareBy<Hit>(
                    { order.indexOf(it.kind) },
                    { -it.weight },
                    // A igual fuerza, primero el código del producto que el de las pruebas.
                    { TechnologySignalCollector.isTestPath(com.funcodestudiocb.analyzer.engine.inventory.PathInfo(it.path)) },
                ),
            )
            .distinctBy { Triple(it.kind, it.path, it.detail) }
            .take(RecordValidator.MAX_EVIDENCE)
            .map { Evidence(it.kind, it.detail.take(RecordValidator.MAX_EVIDENCE_DETAIL), it.path, it.line?.takeIf { l -> l >= 1 }) }
        val projectRoots = hits.paths.mapNotNull { roots.rootOf(it) }.distinct().take(RecordValidator.MAX_TECH_ROOTS)
        return Technology(
            id = rule.id,
            name = rule.name,
            category = rule.category,
            confidence = confidence,
            versions = hits.versions.toList(),
            evidence = evidence,
            evidenceCount = maxOf(hits.total, evidence.size),
            projectRoots = projectRoots,
        )
    }

    private companion object {
        const val MAX_FILE_HITS = 20
        const val EPSILON = 1e-9

        /** PyPI no distingue mayúsculas ni separadores (`Flask_SQLAlchemy` = `flask-sqlalchemy`); NuGet, mayúsculas. */
        fun normalize(name: String, ecosystem: Ecosystem): String = when (ecosystem) {
            Ecosystem.PYTHON -> name.lowercase().replace('_', '-').replace('.', '-')
            else -> name.lowercase()
        }
    }
}
