package com.funcodestudiocb.analyzer.engine.audit.deps

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.Criticality
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.EndOfLife
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import com.funcodestudiocb.analyzer.engine.api.VersionSource
import com.funcodestudiocb.analyzer.engine.api.VulnDatabaseState
import com.funcodestudiocb.analyzer.engine.api.VulnerabilityRef
import com.funcodestudiocb.analyzer.engine.audit.Finding
import com.funcodestudiocb.analyzer.engine.core.AttributeKey
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyKeys
import com.funcodestudiocb.analyzer.engine.dependencies.ParsedLockfile
import com.funcodestudiocb.analyzer.engine.dependencies.label
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.inventory.rootOf
import com.funcodestudiocb.analyzer.engine.relations.RelationKeys
import com.funcodestudiocb.analyzer.engine.relations.Resolver
import com.funcodestudiocb.analyzer.engine.security.SecurityRules
import com.funcodestudiocb.analyzer.engine.vulndb.Advisory
import com.funcodestudiocb.analyzer.engine.vulndb.Affected
import com.funcodestudiocb.analyzer.engine.vulndb.EolProduct
import com.funcodestudiocb.analyzer.engine.vulndb.InvalidIndexException
import com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems
import com.funcodestudiocb.analyzer.engine.vulndb.VersionScheme
import com.funcodestudiocb.analyzer.engine.vulndb.Versions
import com.funcodestudiocb.analyzer.engine.vulndb.VulnDatabase
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Lo que la auditoría de dependencias recibe y deja a la auditoría de seguridad. */
object AuditKeys {
    /** Base de vulnerabilidades local abierta (la pone el servicio o el CLI); sin ella no se evalúan vulnerabilidades. */
    val DATABASE = AttributeKey<VulnDatabase>("audit.vulnDatabase")

    /** Motivo por el que la base no se pudo abrir (dañada, versión incompatible…). */
    val DATABASE_PROBLEM = AttributeKey<String>("audit.vulnDatabaseProblem")

    /** Fecha de referencia para el fin de soporte (por defecto, hoy). */
    val TODAY = AttributeKey<LocalDate>("audit.today")

    internal val DEPENDENCY_FINDINGS = AttributeKey<List<Finding>>("audit.dependencyFindings")

    /** Lo que no se pudo evaluar de las dependencias, para el resumen de seguridad. */
    val DEPENDENCY_NOTES = AttributeKey<List<String>>("audit.dependencyNotes")

    /** Dependencias resueltas con sus vulnerabilidades (para el diagrama de proyectos de la Fase 8). */
    val RESOLVED = AttributeKey<List<ResolvedDependency>>("audit.resolved")

    /** Resumen de dependencias, vulnerabilidades y fin de soporte (lo usa el índice de mantenimiento, Fase 9). */
    val SUMMARY = AttributeKey<DependencySummary>("audit.summary")
}

/**
 * Fase 7: dependencias directas e indirectas con su versión exacta (archivos de bloqueo) o la mínima de su rango,
 * de dónde vienen, si el código las usa, su criticidad, sus vulnerabilidades conocidas (base OSV local) y el fin de
 * soporte de los entornos y frameworks (endoflife.date). Nada se consulta en línea.
 */
class DependencyAuditStage(private val runtimes: RuntimeCollector) : Stage {
    override val id = "dependency-audit"
    override val title = "Dependencias y vulnerabilidades"

    /** No depende de las relaciones: sin ellas, el uso queda "no analizado" y se dice. */
    override val dependsOn = setOf("dependencies")

    private class Dep(
        val ecosystem: Ecosystem,
        val name: String,
        val version: String?,
        val declared: String?,
        val source: VersionSource,
        val direct: Boolean,
        val scope: DependencyScope,
        val root: String?,
        val manifest: String?,
        val manifestLine: Int?,
        val lockfile: String?,
        val lockfileLine: Int?,
        val parents: Set<String>,
        val lock: ParsedLockfile?,
    ) {
        val alsoIn = mutableListOf<String>()
        var alsoInCount = 0
        var chains: List<String> = emptyList()
        var chainCount = 0
        var usage = UsageStatus.NOT_ANALYZED
        var usedIn: List<String> = emptyList()
        var usedCount = 0
        var vulns: List<VulnerabilityRef> = emptyList()
        var criticality = Criticality.LOW
        var reasons: List<String> = emptyList()
    }

    override suspend fun run(context: StageContext) {
        val attrs = context.attributes
        val declared = attrs[DependencyKeys.DECLARED].orEmpty()
        val lockfiles = attrs[DependencyKeys.LOCKFILES].orEmpty().filter { it.readable }
        val roots = attrs[InventoryKeys.ROOTS].orEmpty()
        val db = attrs[AuditKeys.DATABASE]
        val today = attrs[AuditKeys.TODAY] ?: LocalDate.now(ZoneOffset.UTC)
        val notes = mutableListOf<String>()

        context.progress(0.1f, "Dependencias directas e indirectas")
        val deps = merge(build(declared, lockfiles) { path -> roots.rootOf(path) })
        context.checkActive()
        chains(deps)

        context.progress(0.3f, "Uso real en el código")
        val uses = attrs[RelationKeys.EXTERNAL_USES]
        val importLanguages = attrs[RelationKeys.IMPORT_LANGUAGES].orEmpty()
        if (uses == null) notes += "Uso real de las dependencias: no se analizaron las relaciones entre archivos."
        deps.forEach { usage(it, uses, importLanguages) }

        context.progress(0.5f, "Vulnerabilidades conocidas")
        attrs[AuditKeys.DATABASE_PROBLEM]?.let { notes += "Base de vulnerabilidades no utilizable: $it" }
        val ecosystemsInProject = deps.mapNotNull { OsvEcosystems.of(it.ecosystem) }.toSet()
        val available = db?.info?.ecosystems?.associateBy { it.name }.orEmpty()
        var unknownMatches = 0
        if (db == null) {
            if (deps.isNotEmpty()) notes += "Vulnerabilidades de dependencias: no hay base de vulnerabilidades descargada en el teléfono (Ajustes → Bases de vulnerabilidades)."
        } else {
            val missing = ecosystemsInProject - available.keys
            if (missing.isNotEmpty()) notes += "Vulnerabilidades de dependencias de ${missing.sorted().joinToString()}: ese ecosistema no está en la base descargada."
            val cache = HashMap<String, List<Advisory>>()
            for ((i, dep) in deps.withIndex()) {
                if (i % 200 == 0) context.checkActive()
                val osv = OsvEcosystems.of(dep.ecosystem) ?: continue
                if (osv !in available || dep.version == null) continue
                if (osv == "Maven" && !dep.name.contains(':')) continue // identificadores de plugins de Gradle
                val advisories = try {
                    cache.getOrPut("$osv\u0000${OsvEcosystems.normalize(osv, dep.name)}") { db.advisories(osv, dep.name) }
                } catch (e: InvalidIndexException) {
                    notes += "La base de vulnerabilidades está dañada: ${e.message}"
                    break
                }
                val (refs, unknown) = match(osv, dep, advisories)
                dep.vulns = refs
                unknownMatches += unknown
            }
        }
        val unsupported = deps.filter { OsvEcosystems.of(it.ecosystem) == null }.map { it.ecosystem.label() }.distinct()
        if (unsupported.isNotEmpty()) notes += "Vulnerabilidades de ${unsupported.joinToString()}: OSV no tiene base para este ecosistema."
        if (unknownMatches > 0) context.coverage(CoverageReason.NOT_ANALYZED, "En $unknownMatches casos no se pudo comparar la versión con los rangos de un aviso (formato de versión no reconocido): no se marcaron como vulnerables.")
        val ranges = deps.count { it.source == VersionSource.RANGE_IN_MANIFEST }
        if (ranges > 0) notes += (if (ranges == 1) "1 dependencia sin archivo de bloqueo se evaluó" else "$ranges dependencias sin archivo de bloqueo se evaluaron") +
            " con la versión mínima que permite su rango: la instalada puede ser otra."
        val noVersion = deps.count { it.source == VersionSource.UNKNOWN }
        if (noVersion > 0) {
            notes += if (noVersion == 1) "1 dependencia sin versión conocida (catálogo, BOM o variable): no se pudo comparar."
            else "$noVersion dependencias sin versión conocida (catálogo, BOM o variable): no se pudieron comparar."
        }

        deps.forEach(::criticality)

        context.progress(0.7f, "Fin de soporte")
        val eolProducts = db?.eol.orEmpty().associateBy { it.name }
        val eolMissing = sortedSetOf<String>()
        val eol = endOfLife(deps, eolProducts, today, eolMissing)
        if (eolProducts.isEmpty() && (runtimes.hints.isNotEmpty() || deps.isNotEmpty())) notes += "Fin de soporte: no hay datos de endoflife.date descargados."
        if (eolMissing.isNotEmpty()) notes += "Fin de soporte de ${eolMissing.joinToString()}: la base descargada no tiene sus datos."

        // ---------------------------------------------------------------- registros
        val ordered = deps.sortedWith(
            compareBy<Dep>({ d -> d.vulns.minOfOrNull { it.severity.ordinal } ?: Int.MAX_VALUE }, { !it.direct }, { it.root ?: "" }, { it.name.lowercase() }),
        )
        val resolved = ordered.take(MAX_RECORDS).map { record(it) }
        resolved.forEach { context.emit(it) }
        context.attributes[AuditKeys.RESOLVED] = resolved
        if (ordered.size > MAX_RECORDS) context.coverage(CoverageReason.LIMIT_REACHED, "Se registran como máximo $MAX_RECORDS dependencias (hay ${ordered.size}).")
        eol.forEach { context.emit(it) }

        val findings = mutableListOf<Finding>()
        ordered.filter { it.vulns.isNotEmpty() }.forEach { findings += finding(it) }
        eol.filter { it.status == EolStatus.UNSUPPORTED || it.status == EolStatus.ENDING_SOON }.forEach { findings += eolFinding(it) }
        attrs[AuditKeys.DEPENDENCY_FINDINGS] = findings

        val all = ordered.flatMap { it.vulns }
        val databases = ecosystemsInProject.sorted().map { e -> available[e]?.let { VulnDatabaseState(e, it.sourceDate, it.advisories) } ?: VulnDatabaseState(e) }
        val oldest = databases.mapNotNull { it.date }.minOrNull()
        val summary = DependencySummary(
            direct = deps.count { it.direct },
            indirect = deps.count { !it.direct },
            lockfiles = lockfiles.size,
            exactVersions = deps.count { it.source == VersionSource.LOCKFILE || it.source == VersionSource.EXACT_IN_MANIFEST },
            vulnerablePackages = deps.count { it.vulns.isNotEmpty() },
            vulnerabilities = all.size,
            bySeverity = Severity.entries.associateWith { s -> all.count { it.severity == s } }.filterValues { it > 0 },
            malicious = deps.count { d -> d.vulns.any { it.malicious } },
            notSeen = deps.count { it.usage == UsageStatus.NOT_SEEN },
            endOfLife = eol.count { it.status == EolStatus.UNSUPPORTED },
            endingSoon = eol.count { it.status == EolStatus.ENDING_SOON },
            databases = databases.take(RecordValidator.MAX_DATABASES),
            databaseAgeDays = oldest?.let { ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(), today).toInt().coerceAtLeast(0) },
            eolDataDate = db?.info?.eolDate?.takeIf { it > 0 },
            notEvaluated = notes.map { it.take(RecordValidator.MAX_EVIDENCE_DETAIL) }.take(RecordValidator.MAX_DYNAMIC_CHECKS),
        )
        context.emit(summary)
        attrs[AuditKeys.SUMMARY] = summary
        attrs[AuditKeys.DEPENDENCY_NOTES] = notes
    }

    // ------------------------------------------------------------------ dependencias

    private fun build(declared: List<DeclaredDependency>, lockfiles: List<ParsedLockfile>, rootOf: (String) -> String?): List<Dep> {
        val out = LinkedHashMap<String, Dep>()
        fun key(e: Ecosystem, name: String, version: String?, root: String?) = "${family(e)}|${normName(e, name)}|${version.orEmpty()}|${root.orEmpty()}"
        val covered = HashSet<String>() // (familia, carpeta) con archivo de bloqueo
        for (lock in lockfiles) {
            val dir = lock.path.substringBeforeLast('/', "")
            covered += "${family(lock.ecosystem)}|$dir"
            val root = rootOf(lock.path)
            val manifestDeps = declared.filter { d -> d.manifest.substringBeforeLast('/', "") == dir && family(d.ecosystem) == family(lock.ecosystem) }
                .associateBy { normName(it.ecosystem, it.name) }
            for (p in lock.packages) {
                val m = manifestDeps[normName(lock.ecosystem, p.name)]
                val direct = p.direct ?: (m != null)
                val scope = when {
                    m != null -> m.scope
                    p.dev -> DependencyScope.DEVELOPMENT
                    else -> DependencyScope.RUNTIME
                }
                val k = key(lock.ecosystem, p.name, p.version, root)
                if (k in out) continue
                out[k] = Dep(lock.ecosystem, p.name, p.version, m?.version, VersionSource.LOCKFILE, direct, scope, root, m?.manifest, m?.line, lock.path, p.line, p.parents, lock)
            }
            // Declaradas que el archivo de bloqueo no tiene (otro grupo, opcional): con la versión del manifiesto.
            for ((n, m) in manifestDeps) {
                if (lock.packages.any { normName(lock.ecosystem, it.name) == n }) continue
                addDeclared(out, m, rootOf, ::key)
            }
        }
        for (d in declared) {
            val dir = d.manifest.substringBeforeLast('/', "")
            if ("${family(d.ecosystem)}|$dir" in covered) continue
            if (d.ecosystem in setOf(Ecosystem.CMAKE, Ecosystem.UNREAL)) continue
            addDeclared(out, d, rootOf, ::key)
        }
        return out.values.toList()
    }

    /**
     * Una misma dependencia con la misma versión en varios módulos (cada `build.gradle` de un proyecto Gradle, cada
     * `package.json` de un monorepo) se registra una vez, con los demás lugares en [Dep.alsoIn].
     */
    private fun merge(all: List<Dep>): List<Dep> {
        val out = LinkedHashMap<String, Dep>()
        for (d in all.sortedBy { if (it.direct) 0 else 1 }) {
            val k = "${family(d.ecosystem)}|${normName(d.ecosystem, d.name)}|${d.version.orEmpty()}|${d.direct}"
            val first = out[k]
            if (first == null) {
                out[k] = d
                continue
            }
            first.alsoInCount++
            val where = (if (d.direct) d.manifest ?: d.lockfile else d.lockfile ?: d.manifest)?.let { p -> p + ((if (p == d.manifest) d.manifestLine else d.lockfileLine)?.let { ":$it" } ?: "") }
            if (where != null && first.alsoIn.size < RecordValidator.MAX_SAMPLES) first.alsoIn += where
        }
        return out.values.toList()
    }

    private fun addDeclared(out: LinkedHashMap<String, Dep>, d: DeclaredDependency, rootOf: (String) -> String?, key: (Ecosystem, String, String?, String?) -> String) {
        val (version, source) = classify(d.ecosystem, d.version)
        val root = d.projectRoot ?: rootOf(d.manifest)
        val k = key(d.ecosystem, d.name, version, root)
        if (k in out) return
        out[k] = Dep(d.ecosystem, d.name, version, d.version, source, true, d.scope, root, d.manifest, d.line, null, null, emptySet(), null)
    }

    /** Ecosistemas que comparten paquetes (Gradle, Android y Maven usan Maven Central). */
    private fun family(e: Ecosystem): String = when (e) {
        Ecosystem.GRADLE, Ecosystem.ANDROID, Ecosystem.MAVEN -> "maven"
        else -> e.name
    }

    private fun normName(e: Ecosystem, name: String): String = OsvEcosystems.of(e)?.let { OsvEcosystems.normalize(it, name) } ?: name.lowercase()

    /** Versión que se evalúa y de dónde sale, a partir de lo que declara el manifiesto. */
    internal fun classify(e: Ecosystem, raw: String?): Pair<String?, VersionSource> {
        val v = raw?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null to VersionSource.UNKNOWN
        if (v.startsWith("$") || v.contains("\${") || v == "*" || v == "latest" || v == "any" || v.contains("://") || v.startsWith("git") ||
            v.startsWith("file:") || v.startsWith("workspace:") || v.startsWith("link:") || v.startsWith("dev-")
        ) return null to VersionSource.UNKNOWN
        val plain = v.removePrefix("==").removePrefix("=").removePrefix("v")
        val exact = plain.isNotEmpty() && plain.first().isDigit() && plain.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' || it == '_' } &&
            !plain.endsWith(".x") && !plain.contains(".x.") && !plain.endsWith("+") && !plain.contains(".+")
        // En Cargo "1.2.3" es "^1.2.3"; en pip sin operador no hay versión exacta.
        if (exact && e != Ecosystem.RUST_CARGO && !(e == Ecosystem.PYTHON && !v.startsWith("=="))) return plain to VersionSource.EXACT_IN_MANIFEST
        val min = runtimes.minimumOf(v.substringBefore("||").substringBefore(',').let { if (it.trim().startsWith("<")) "" else it }) ?: return null to VersionSource.UNKNOWN
        return min to VersionSource.RANGE_IN_MANIFEST
    }

    /** Hasta 3 cadenas `directa › … › paquete` por las que llega una dependencia indirecta. */
    private fun chains(deps: List<Dep>) {
        val byLock = deps.filter { it.lock != null }.groupBy { it.lock!!.path }
        for ((_, list) in byLock) {
            val parents = HashMap<String, Set<String>>()
            list.forEach { d -> parents[d.name] = (parents[d.name].orEmpty() + d.parents) }
            val directNames = list.filter { it.direct }.mapTo(HashSet()) { it.name }
            for (d in list) {
                if (d.direct) continue
                val found = mutableListOf<List<String>>()
                val roots = HashSet<String>()
                val queue = ArrayDeque<List<String>>()
                queue += listOf(d.name)
                var steps = 0
                while (queue.isNotEmpty() && steps < 3_000) {
                    steps++
                    val path = queue.removeFirst()
                    val last = path.last()
                    for (p in parents[last].orEmpty()) {
                        if (p in path) continue
                        val next = path + p
                        if (p in directNames) {
                            roots += p
                            if (found.size < RecordValidator.MAX_INTRODUCED_BY) found += next
                        } else if (next.size < 8) {
                            queue += next
                        }
                    }
                }
                d.chains = found.map { it.asReversed().joinToString(" › ").take(RecordValidator.MAX_REFERENCE_LENGTH) }
                d.chainCount = maxOf(roots.size, d.chains.size)
            }
        }
    }

    // ------------------------------------------------------------------ uso real

    private fun languagesOf(e: Ecosystem): Set<String> = when (e) {
        Ecosystem.NPM -> setOf("javascript", "typescript", "tsx", "vue", "svelte")
        Ecosystem.PYTHON -> setOf("python")
        Ecosystem.MAVEN, Ecosystem.GRADLE, Ecosystem.ANDROID -> setOf("java", "kotlin")
        Ecosystem.PHP_COMPOSER -> setOf("php")
        Ecosystem.DOTNET -> setOf("csharp")
        Ecosystem.DART_PUB -> setOf("dart")
        else -> emptySet()
    }

    private val pythonModules: Map<String, List<String>> by lazy {
        Resolver.PYTHON_ALIASES.entries.groupBy({ it.value.lowercase() }, { it.key.lowercase() })
    }

    private fun usage(d: Dep, uses: Map<String, List<String>>?, importLanguages: Set<String>) {
        if (!d.direct) {
            d.usage = UsageStatus.INDIRECT
            return
        }
        val dev = d.scope in setOf(DependencyScope.DEVELOPMENT, DependencyScope.TEST, DependencyScope.BUILD)
        if (uses == null || languagesOf(d.ecosystem).none { it in importLanguages }) {
            d.usage = if (dev) UsageStatus.DEVELOPMENT_TOOL else UsageStatus.NOT_ANALYZED
            return
        }
        val name = d.name.lowercase()
        val files = LinkedHashSet<String>()
        for ((key, list) in uses) {
            val k = key.lowercase()
            val match = when (d.ecosystem) {
                Ecosystem.NPM, Ecosystem.DART_PUB -> k == name
                Ecosystem.PYTHON -> {
                    val n = name.replace('-', '_').replace('.', '_')
                    val module = k.replace('-', '_')
                    module == n || module in pythonModules[name].orEmpty() || n.startsWith("python_") && module == n.removePrefix("python_") || n.endsWith("_python") && module == n.removeSuffix("_python")
                }
                Ecosystem.MAVEN, Ecosystem.GRADLE, Ecosystem.ANDROID -> name.contains(':') && javaPackages(name).any { pkg -> pkg == k || pkg.startsWith("$k.") || k.startsWith("$pkg.") }
                Ecosystem.PHP_COMPOSER -> {
                    val vendor = name.substringBefore('/').replace("-", "").replace("_", "")
                    k.substringBefore('\\').replace("_", "") == vendor
                }
                Ecosystem.DOTNET -> k == name || name.startsWith("$k.") || k.startsWith("$name.")
                else -> false
            }
            if (match) files += list
        }
        when {
            files.isNotEmpty() -> {
                d.usage = UsageStatus.USED
                d.usedCount = files.size
                d.usedIn = files.take(RecordValidator.MAX_SAMPLES)
            }
            dev || name.startsWith("@types/") -> d.usage = UsageStatus.DEVELOPMENT_TOOL
            else -> d.usage = UsageStatus.NOT_SEEN
        }
    }

    /**
     * Paquetes Java/Kotlin que suele traer un artefacto de Maven: el grupo, salvo en bibliotecas conocidas cuyo paquete
     * no coincide (`org.jetbrains.kotlinx:kotlinx-coroutines-core` → `kotlinx.coroutines`, `com.squareup.okhttp3` → `okhttp3`).
     */
    private fun javaPackages(coordinates: String): List<String> {
        val group = coordinates.substringBefore(':')
        val artifact = coordinates.substringAfter(':')
        val known = when {
            group == "org.jetbrains.kotlinx" && artifact.startsWith("kotlinx-") -> listOf(artifact.split('-').take(2).joinToString("."))
            group == "org.jetbrains.kotlin" -> listOf("kotlin")
            else -> JAVA_PACKAGES[group]
        }
        return known ?: listOf(group)
    }

    // ------------------------------------------------------------------ vulnerabilidades

    /** Avisos que afectan a la versión de la dependencia, unidos por alias; y cuántos no se pudieron decidir. */
    private fun match(osv: String, d: Dep, advisories: List<Advisory>): Pair<List<VulnerabilityRef>, Int> {
        if (advisories.isEmpty()) return emptyList<VulnerabilityRef>() to 0
        val scheme = Versions.schemeOf(osv)
        val version = d.version ?: return emptyList<VulnerabilityRef>() to 0
        val exact = d.source == VersionSource.LOCKFILE || d.source == VersionSource.EXACT_IN_MANIFEST
        var unknown = 0
        val hits = mutableListOf<Advisory>()
        for (a in advisories) {
            when (Affected.check(scheme, version, a.ranges, a.versions)) {
                true -> hits += a
                null -> unknown++
                false -> {}
            }
        }
        // Un mismo fallo publicado por varias bases (GHSA, PYSEC, CVE): se une por sus identificadores.
        val groups = mutableListOf<MutableList<Advisory>>()
        for (a in hits) {
            val ids = (a.aliases + a.id).toSet()
            val g = groups.firstOrNull { grp -> grp.any { b -> (b.aliases + b.id).any { it in ids } } }
            if (g != null) g += a else groups += mutableListOf(a)
        }
        val refs = groups.map { g ->
            val best = g.sortedWith(compareBy<Advisory>({ it.severity == null }, { !it.id.startsWith("GHSA") })).first()
            val ids = g.flatMap { it.aliases + it.id }.distinct()
            VulnerabilityRef(
                id = best.id,
                aliases = (ids - best.id).sortedBy { if (it.startsWith("CVE")) 0 else 1 }.take(RecordValidator.MAX_ALIASES),
                summary = g.firstNotNullOfOrNull { it.summary.ifBlank { null } }?.take(RecordValidator.MAX_EVIDENCE_DETAIL),
                severity = best.severity ?: if (g.any { it.malicious }) Severity.CRITICAL else Severity.MEDIUM,
                score = best.score,
                fixedIn = g.flatMap { it.fixed }.distinct().take(RecordValidator.MAX_FIXED_IN),
                malicious = g.any { it.malicious },
                certainty = if (exact) Certainty.CONFIRMED else Certainty.POSSIBLE,
                published = best.published,
            )
        }.sortedWith(compareBy({ it.severity.ordinal }, { it.id })).take(RecordValidator.MAX_VULNERABILITIES)
        return refs to unknown
    }

    // ------------------------------------------------------------------ criticidad

    private fun criticality(d: Dep) {
        var points = 0
        val reasons = mutableListOf<String>()
        val worst = d.vulns.minOfOrNull { it.severity.ordinal }?.let { Severity.entries[it] }
        when {
            d.vulns.any { it.malicious } -> {
                points += 6
                reasons += "Figura como paquete malicioso"
            }
            worst == Severity.CRITICAL || worst == Severity.HIGH -> {
                points += 3
                reasons += "Tiene vulnerabilidades de severidad ${if (worst == Severity.CRITICAL) "crítica" else "alta"}"
            }
            worst != null -> {
                points += 1
                reasons += "Tiene vulnerabilidades conocidas"
            }
        }
        val runtime = d.scope == DependencyScope.RUNTIME || d.scope == DependencyScope.PEER || d.scope == DependencyScope.OPTIONAL
        if (runtime) points += 1 else reasons += "Solo para desarrollo, pruebas o compilación"
        if (d.direct) points += 1
        when {
            d.usedCount >= 10 -> {
                points += 2
                reasons += "Se usa en ${d.usedCount} archivos"
            }
            d.usedCount >= 3 -> {
                points += 1
                reasons += "Se usa en ${d.usedCount} archivos"
            }
        }
        val lower = d.name.lowercase()
        SENSITIVE.firstOrNull { lower.contains(it) }?.let {
            points += 2
            reasons += "Interviene en seguridad, sesiones o acceso a datos («$it»)"
        }
        if (FRAMEWORKS.any { lower == it || lower.endsWith(":$it") || lower.endsWith("/$it") }) {
            points += 1
            reasons += "Es el framework sobre el que se construye la aplicación"
        }
        if (!d.direct && d.chainCount > 0) {
            reasons += if (d.chainCount == 1) "Llega a través de 1 dependencia directa" else "Llega a través de ${d.chainCount} dependencias directas"
        }
        d.criticality = when {
            points >= 5 -> Criticality.HIGH
            points >= 3 -> Criticality.MEDIUM
            else -> Criticality.LOW
        }
        d.reasons = reasons.take(RecordValidator.MAX_REASONS)
    }

    private fun record(d: Dep) = ResolvedDependency(
        ecosystem = d.ecosystem,
        name = d.name,
        version = d.version,
        declared = d.declared?.take(RecordValidator.MAX_VERSION_LENGTH),
        versionSource = if (d.version == null) VersionSource.UNKNOWN else d.source,
        direct = d.direct,
        scope = d.scope,
        projectRoot = d.root,
        manifest = d.manifest,
        manifestLine = d.manifestLine,
        lockfile = d.lockfile,
        lockfileLine = d.lockfileLine,
        alsoIn = d.alsoIn.toList(),
        alsoInCount = maxOf(d.alsoInCount, d.alsoIn.size),
        introducedBy = d.chains,
        introducedByCount = d.chainCount,
        usage = d.usage,
        usedIn = d.usedIn,
        usedCount = d.usedCount,
        criticality = d.criticality,
        criticalityReasons = d.reasons,
        vulnerabilities = d.vulns,
        vulnerabilityCount = d.vulns.size,
    )

    // ------------------------------------------------------------------ hallazgos

    private fun finding(d: Dep): Finding {
        val malicious = d.vulns.any { it.malicious }
        val rule = SecurityRules.get(if (malicious) "FC-DEP-02" else "FC-DEP-01")
        val severity = d.vulns.minOf { it.severity.ordinal }.let { Severity.entries[it] }
        val certainty = if (d.vulns.all { it.certainty == Certainty.CONFIRMED }) Certainty.CONFIRMED else Certainty.POSSIBLE
        val path = if (d.direct) d.manifest ?: d.lockfile else d.lockfile ?: d.manifest
        val line = if (path == d.manifest) d.manifestLine else d.lockfileLine
        val osv = OsvEcosystems.of(d.ecosystem)
        val scheme = osv?.let { Versions.schemeOf(it) }
        val fix = scheme?.let { neededFix(it, d) }
        val list = d.vulns.take(5).joinToString("; ") { v ->
            val cve = v.aliases.firstOrNull { it.startsWith("CVE-") }
            "${v.id}${cve?.let { " ($it)" } ?: ""}: ${v.summary ?: "sin resumen"}".take(220)
        }
        val more = if (d.vulns.size > 5) " Y ${d.vulns.size - 5} más." else ""
        val versionText = when (d.source) {
            VersionSource.LOCKFILE -> "versión ${d.version} (de ${d.lockfile?.substringAfterLast('/')})"
            VersionSource.EXACT_IN_MANIFEST -> "versión ${d.version} (fijada en el manifiesto)"
            else -> "versión mínima ${d.version} del rango «${d.declared}» (sin archivo de bloqueo: la instalada puede ser otra)"
        }
        val how = if (d.direct) "dependencia directa" else "dependencia indirecta" + (d.chains.firstOrNull()?.let { ", llega por $it" } ?: "")
        val detail = "«${d.name}» ${versionText}, $how. ${d.vulns.size} ${if (d.vulns.size == 1) "aviso" else "avisos"}: $list.$more" +
            (fix?.let { " Primera versión que los corrige todos: $it." } ?: if (d.vulns.any { it.fixedIn.isEmpty() }) " Alguno no tiene corrección publicada." else "")
        val flow = listOfNotNull(
            d.manifestLine?.let { FlowStep(it, "Se declara «${d.name}»" + (d.declared?.let { " ($it)" } ?: "")) }.takeIf { d.direct && path == d.manifest },
            d.lockfileLine?.let { FlowStep(it, "El archivo de bloqueo instala la versión ${d.version}") }.takeIf { path == d.lockfile },
        )
        return Finding(
            rule, path, line, certainty, severity, detail.take(RecordValidator.MAX_FINDING_TEXT - rule.explanation.length - 2), flow,
            language = null, example = fix?.let { example(d, it) },
            extraReferences = d.vulns.take(4).map { "https://osv.dev/vulnerability/${it.id}" },
            downgraded = if (d.scope != DependencyScope.RUNTIME && d.scope != DependencyScope.PEER && !malicious && severity.ordinal < Severity.LOW.ordinal) "Solo para desarrollo, pruebas o compilación" else null,
        ).also { f ->
            if (f.downgraded != null) f.severity = com.funcodestudiocb.analyzer.engine.audit.Context.lower(f.severity)
        }
    }

    /** La versión más baja que corrige todos los avisos (o `null` si alguno no tiene corrección). */
    private fun neededFix(scheme: VersionScheme, d: Dep): String? {
        val current = d.version ?: return null
        var needed: String? = null
        for (v in d.vulns) {
            val candidates = v.fixedIn.filter { (Versions.compare(scheme, it, current) ?: 0) > 0 }
            val best = candidates.minWithOrNull { a, b -> Versions.compare(scheme, a, b) ?: 0 } ?: return null
            if (needed == null || (Versions.compare(scheme, best, needed) ?: 0) > 0) needed = best
        }
        return needed
    }

    private fun example(d: Dep, fix: String): String {
        val n = d.name
        val direct = when (d.ecosystem) {
            Ecosystem.NPM -> "\"$n\": \"^$fix\"\n# npm install $n@$fix"
            Ecosystem.PYTHON -> "$n==$fix"
            Ecosystem.MAVEN -> "<dependency>\n  <groupId>${n.substringBefore(':')}</groupId>\n  <artifactId>${n.substringAfter(':')}</artifactId>\n  <version>$fix</version>\n</dependency>"
            Ecosystem.GRADLE, Ecosystem.ANDROID -> "implementation(\"$n:$fix\")"
            Ecosystem.PHP_COMPOSER -> "\"$n\": \"^$fix\"\n# composer require $n:^$fix"
            Ecosystem.DOTNET -> "<PackageReference Include=\"$n\" Version=\"$fix\" />"
            Ecosystem.DART_PUB -> "$n: ^$fix"
            Ecosystem.GO -> "go get $n@${if (fix.startsWith("v")) fix else "v$fix"}"
            Ecosystem.RUST_CARGO -> "$n = \"$fix\""
            Ecosystem.RUBY -> "gem \"$n\", \">= $fix\""
            else -> "$n $fix"
        }
        if (d.direct) return direct
        val force = when (d.ecosystem) {
            Ecosystem.NPM -> "// package.json: fuerza la versión de la dependencia indirecta\n\"overrides\": { \"$n\": \"$fix\" }"
            Ecosystem.PHP_COMPOSER -> "composer update $n --with-dependencies"
            Ecosystem.PYTHON -> "# Añade la restricción como dependencia directa:\n$n>=$fix"
            Ecosystem.GRADLE, Ecosystem.ANDROID, Ecosystem.MAVEN -> "constraints {\n  implementation(\"$n:$fix\")\n}"
            else -> direct
        }
        return "# Actualiza la dependencia directa que la introduce" + (d.chains.firstOrNull()?.substringBefore(" ›")?.let { " ($it)" } ?: "") + " o fuerza la versión:\n$force"
    }

    // ------------------------------------------------------------------ fin de soporte

    private fun endOfLife(deps: List<Dep>, products: Map<String, EolProduct>, today: LocalDate, missing: MutableSet<String>): List<EndOfLife> {
        val hints = runtimes.hints.toMutableList()
        for (d in deps) {
            if (!d.direct || d.version == null) continue
            val product = frameworkProduct(d.ecosystem, d.name) ?: continue
            val where = d.manifest ?: d.lockfile ?: continue
            hints += RuntimeHint(product, d.version, d.source == VersionSource.RANGE_IN_MANIFEST, "dependencia «${d.name}»", where, d.manifestLine ?: d.lockfileLine)
        }
        val seen = LinkedHashMap<String, Int>()
        val out = mutableListOf<EndOfLife>()
        for (h in hints) {
            val dedup = "${h.product}|${h.version}|${h.minimum}"
            seen[dedup]?.let { idx ->
                val e = out[idx]
                val where = h.path + (h.line?.let { ":$it" } ?: "")
                if (where != e.path + (e.line?.let { ":$it" } ?: "")) {
                    out[idx] = e.copy(alsoIn = if (e.alsoIn.size < RecordValidator.MAX_SAMPLES) e.alsoIn + where else e.alsoIn, alsoInCount = e.alsoInCount + 1)
                }
                continue
            }
            val product = products[h.product]
            val label = product?.label ?: com.funcodestudiocb.analyzer.engine.vulndb.EolData.PRODUCTS[h.product] ?: h.product
            if (product == null) {
                // Sin datos de ese producto en la base: se dice, en lugar de darlo por soportado.
                if (products.isNotEmpty()) missing += label
                continue
            }
            val cycle = product.cycles.filter { c -> matches(c.cycle, h.version) }.maxByOrNull { it.cycle.split('.').size }
            val status: EolStatus
            var eolDate: String? = null
            if (cycle == null) {
                status = EolStatus.UNKNOWN_CYCLE
            } else {
                val eol = cycle.eol
                val date = eol?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                eolDate = date?.toString()
                status = when {
                    date != null && !date.isAfter(today) -> EolStatus.UNSUPPORTED
                    date != null && ChronoUnit.DAYS.between(today, date) <= 183 -> EolStatus.ENDING_SOON
                    date != null -> EolStatus.SUPPORTED
                    eol == "true" -> EolStatus.UNSUPPORTED
                    else -> EolStatus.SUPPORTED
                }
            }
            seen[dedup] = out.size
            out += EndOfLife(
                product = h.product, label = label, version = h.version.take(RecordValidator.MAX_VERSION_LENGTH), source = h.source.take(RecordValidator.MAX_EVIDENCE_DETAIL),
                path = h.path, line = h.line, cycle = cycle?.cycle, status = status, eolDate = eolDate, latestInCycle = cycle?.latest, minimumOfRange = h.minimum,
            )
            if (out.size >= MAX_EOL) break
        }
        return out.sortedWith(compareBy({ it.status.ordinal }, { it.label }))
    }

    /** La versión pertenece al ciclo: `18.17.0` → `18`; `3.8.10` → `3.8`; `2021.3.5` → `2021.3`. */
    private fun matches(cycle: String, version: String): Boolean {
        val c = cycle.split('.')
        val v = version.split('.')
        if (c.isEmpty() || v.size < c.size) return c.size == v.size + 1 && c.last() == "0" && c.dropLast(1) == v
        return c.indices.all { i -> c[i] == v[i] || c[i].toLongOrNull() != null && c[i].toLongOrNull() == v[i].takeWhile { it.isDigit() }.toLongOrNull() }
    }

    private fun eolFinding(e: EndOfLife): Finding {
        val ending = e.status == EolStatus.ENDING_SOON
        val rule = SecurityRules.get(if (ending) "FC-EOL-02" else "FC-EOL-01")
        val detail = "${e.label} ${e.version} (${e.source}): el ciclo ${e.cycle} " +
            (if (ending) "deja de tener soporte de seguridad el ${e.eolDate}." else "no tiene soporte de seguridad" + (e.eolDate?.let { " desde el $it" } ?: "") + ".") +
            (e.latestInCycle?.let { " Última versión del ciclo: $it." } ?: "") +
            (if (e.minimumOfRange) " Es la versión mínima que admite el proyecto; puede ejecutarse con otra más nueva." else "")
        return Finding(
            rule, e.path, e.line, if (e.minimumOfRange) Certainty.POSSIBLE else Certainty.CONFIRMED,
            if (e.minimumOfRange) Severity.LOW else rule.severity, detail,
            listOfNotNull(e.line?.let { FlowStep(it, "${e.label} ${e.version}") }),
            extraReferences = listOf("https://endoflife.date/${e.product}"),
        )
    }

    private fun frameworkProduct(e: Ecosystem, name: String): String? {
        val n = name.lowercase()
        return when (e) {
            Ecosystem.NPM -> NPM_PRODUCTS[n]
            Ecosystem.PYTHON -> mapOf("django" to "django", "numpy" to "numpy")[n]
            Ecosystem.PHP_COMPOSER -> mapOf("laravel/framework" to "laravel", "symfony/http-kernel" to "symfony", "symfony/framework-bundle" to "symfony", "drupal/core" to "drupal", "cakephp/cakephp" to "cakephp", "magento/product-community-edition" to "magento")[n]
            Ecosystem.RUBY -> if (n == "rails") "rails" else null
            Ecosystem.MAVEN, Ecosystem.GRADLE, Ecosystem.ANDROID -> when {
                n.startsWith("org.springframework.boot:spring-boot") || n == "org.springframework.boot" -> "spring-boot"
                n == "org.springframework:spring-core" -> "spring-framework"
                n == "org.apache.logging.log4j:log4j-core" -> "log4j"
                n == "org.apache.tomcat.embed:tomcat-embed-core" -> "tomcat"
                n == "org.jetbrains.kotlin:kotlin-stdlib" || n == "org.jetbrains.kotlin.jvm" || n == "org.jetbrains.kotlin.android" || n == "org.jetbrains.kotlin.multiplatform" -> "kotlin"
                else -> null
            }
            else -> null
        }
    }

    private companion object {
        const val MAX_RECORDS = 20_000
        const val MAX_EOL = 500
        val SENSITIVE = listOf(
            "auth", "jwt", "jose", "passport", "oauth", "bcrypt", "argon", "crypto", "security", "session", "cookie", "ssl", "tls", "sql", "orm",
            "hibernate", "sequelize", "prisma", "mongoose", "typeorm", "jackson", "gson", "serializ", "xml", "yaml", "log4j", "okhttp", "axios", "request",
        )
        val FRAMEWORKS = listOf(
            "express", "django", "flask", "fastapi", "react", "vue", "@angular/core", "next", "nuxt", "laravel/framework", "spring-boot", "spring-core",
            "rails", "symfony/http-kernel", "electron", "@nestjs/core", "koa", "fastify",
        )
        val JAVA_PACKAGES = mapOf(
            "com.google.code.gson" to listOf("com.google.gson"), "com.squareup.okhttp3" to listOf("okhttp3"), "com.squareup.okhttp" to listOf("com.squareup.okhttp"),
            "com.squareup.retrofit2" to listOf("retrofit2"), "com.squareup.okio" to listOf("okio"), "com.google.dagger" to listOf("dagger"),
            "io.insert-koin" to listOf("org.koin"), "com.fasterxml.jackson.core" to listOf("com.fasterxml.jackson"), "junit" to listOf("org.junit"),
            "org.junit.jupiter" to listOf("org.junit"), "net.zetetic" to listOf("net.zetetic"), "com.jakewharton.timber" to listOf("timber"),
            "io.coil-kt" to listOf("coil"), "com.github.bumptech.glide" to listOf("com.bumptech.glide"), "org.xerial" to listOf("org.sqlite"),
            "mysql" to listOf("com.mysql"), "org.postgresql" to listOf("org.postgresql"), "io.ktor" to listOf("io.ktor"), "org.snakeyaml" to listOf("org.snakeyaml"),
        )
        val NPM_PRODUCTS = mapOf(
            "react" to "react", "vue" to "vue", "@angular/core" to "angular", "next" to "nextjs", "nuxt" to "nuxt", "electron" to "electron",
            "express" to "express", "@nestjs/core" to "nestjs", "react-native" to "react-native", "svelte" to "svelte", "@ionic/core" to "ionic",
            "@ionic/angular" to "ionic", "@ionic/react" to "ionic", "jquery" to "jquery", "bootstrap" to "bootstrap", "tailwindcss" to "tailwind-css",
            "@strapi/strapi" to "strapi",
        )
    }
}
