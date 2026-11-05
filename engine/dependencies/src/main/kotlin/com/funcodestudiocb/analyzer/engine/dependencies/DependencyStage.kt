package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.core.AttributeKey
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.inventory.rootOf

object DependencyKeys {
    /** Dependencias directas declaradas (ya con subproyecto), para el motor de tecnologías. */
    val DECLARED = AttributeKey<List<DeclaredDependency>>("dependencies.declared")

    /** Archivos de bloqueo leídos (Fase 7): versiones exactas de las dependencias directas e indirectas. */
    val LOCKFILES = AttributeKey<List<ParsedLockfile>>("dependencies.lockfiles")
}

/** Reúne los manifiestos y los archivos de bloqueo durante el recorrido del comprimido (sobre el texto ya redactado). */
class DependencyCollector : RedactedTextListener {
    val manifests = mutableListOf<ParsedManifest>()
    val lockfiles = mutableListOf<ParsedLockfile>()

    override fun onText(context: StageContext, file: RedactedText) {
        if (manifests.size < MAX_MANIFESTS) ManifestParsers.parse(file.info, file.text)?.let { manifests += it }
        if (lockfiles.size < MAX_LOCKFILES) Lockfiles.parse(file.info, file.text)?.let { lockfiles += it }
    }

    companion object {
        const val MAX_MANIFESTS = 5_000
        const val MAX_LOCKFILES = 500
    }
}

/**
 * Dependencias directas declaradas en los manifiestos (Fase 4: son evidencia para el motor de tecnologías) y archivos
 * de bloqueo leídos (Fase 7). Resuelve las referencias a catálogos de versiones de Gradle con el catálogo más cercano.
 * La comparación con las vulnerabilidades conocidas se hace en la auditoría de dependencias.
 */
class DependencyStage(private val collector: DependencyCollector) : Stage {
    override val id = "dependencies"
    override val title = "Dependencias declaradas"
    override val dependsOn = setOf("inventory")

    override suspend fun run(context: StageContext) {
        val roots = context.attributes[InventoryKeys.ROOTS].orEmpty()
        val catalogs = collector.manifests.mapNotNull { it.catalog }
        val out = mutableListOf<DeclaredDependency>()
        var unresolved = 0
        var unresolvedExample: String? = null
        val unreadable = mutableListOf<String>()

        for (manifest in collector.manifests) {
            if (!manifest.readable) unreadable += manifest.path
            val root = roots.rootOf(manifest.path)
            fun add(raw: RawDependency) {
                if (out.size < MAX_DEPENDENCIES) out += DeclaredDependency(manifest.path, manifest.ecosystem, raw.name, raw.version, raw.scope, raw.line, root)
            }
            manifest.dependencies.forEach(::add)
            for (ref in manifest.catalogRefs) {
                val resolved = resolve(ref, manifest.path, catalogs)
                if (resolved.isEmpty()) {
                    // Solo se cuentan las referencias al catálogo por defecto: otro prefijo puede ser una variable.
                    if (ref.catalog == "libs" || catalogs.any { it.name == ref.catalog }) {
                        unresolved++
                        if (unresolvedExample == null) unresolvedExample = manifest.path
                    }
                }
                resolved.forEach(::add)
            }
        }
        out.forEach(context::emit)
        context.attributes[DependencyKeys.DECLARED] = out
        context.attributes[DependencyKeys.LOCKFILES] = collector.lockfiles.toList()
        collector.lockfiles.filter { !it.readable }.let { bad ->
            if (bad.isNotEmpty()) {
                context.coverage(CoverageReason.NOT_ANALYZED, "${bad.size} archivos de bloqueo no se pudieron interpretar (formato inválido o no reconocido)", bad.first().path)
            }
        }
        collector.lockfiles.filter { it.truncated }.forEach {
            context.coverage(CoverageReason.LIMIT_REACHED, "El archivo de bloqueo tiene más de ${Lockfiles.MAX_PACKAGES} paquetes: solo se leyeron los primeros", it.path)
        }

        if (out.size >= MAX_DEPENDENCIES) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se registran como máximo $MAX_DEPENDENCIES dependencias declaradas")
        }
        if (unresolved > 0) {
            context.coverage(
                CoverageReason.NOT_ANALYZED,
                "$unresolved referencias a un catálogo de versiones de Gradle no se pudieron resolver (el catálogo no está en el comprimido o usa una sintaxis no reconocida)",
                unresolvedExample,
            )
        }
        if (unreadable.isNotEmpty()) {
            context.coverage(
                CoverageReason.NOT_ANALYZED,
                "${unreadable.size} manifiestos de dependencias no se pudieron interpretar (formato inválido o demasiado anidado)",
                unreadable.first(),
            )
        }
        if (collector.manifests.size >= DependencyCollector.MAX_MANIFESTS) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se leyeron como máximo ${DependencyCollector.MAX_MANIFESTS} manifiestos de dependencias")
        }
    }

    /** Catálogo con ese nombre cuya carpeta base (la que contiene `gradle/`) es la más cercana al archivo. */
    private fun resolve(ref: CatalogRef, path: String, catalogs: List<VersionCatalog>): List<RawDependency> {
        val catalog = catalogs
            .filter { it.name == ref.catalog }
            .map { it to it.path.substringBeforeLast('/', "").removeSuffix("gradle").trimEnd('/') }
            .filter { (_, base) -> base.isEmpty() || path.startsWith("$base/") }
            .maxByOrNull { (_, base) -> base.length }
            ?.first ?: return emptyList()
        return when (ref.kind) {
            CatalogRefKind.LIBRARY -> listOfNotNull(catalog.libraries[ref.alias]?.let { (module, version) -> RawDependency(module, version, ref.scope, ref.line) })
            CatalogRefKind.PLUGIN -> listOfNotNull(catalog.plugins[ref.alias]?.let { (id, version) -> RawDependency(id, version, DependencyScope.BUILD, ref.line) })
            CatalogRefKind.BUNDLE -> catalog.bundles[ref.alias].orEmpty().mapNotNull { alias ->
                catalog.libraries[alias]?.let { (module, version) -> RawDependency(module, version, ref.scope, ref.line) }
            }
        }.mapNotNull { ManifestParsers.dependency(it.name, it.version, it.scope, it.line) }
    }

    private companion object {
        const val MAX_DEPENDENCIES = 20_000
    }
}

/** Nombre legible del ecosistema. */
fun Ecosystem.label(): String = when (this) {
    Ecosystem.ANDROID -> "Android"
    Ecosystem.GRADLE -> "Gradle"
    Ecosystem.MAVEN -> "Maven"
    Ecosystem.NPM -> "npm"
    Ecosystem.PYTHON -> "Python"
    Ecosystem.PHP_COMPOSER -> "Composer"
    Ecosystem.DART_PUB -> "pub (Dart)"
    Ecosystem.DOTNET -> ".NET / NuGet"
    Ecosystem.GO -> "Go"
    Ecosystem.RUST_CARGO -> "Cargo"
    Ecosystem.RUBY -> "RubyGems"
    Ecosystem.COCOAPODS -> "CocoaPods"
    Ecosystem.CMAKE -> "CMake"
    Ecosystem.UNITY -> "Unity"
    Ecosystem.UNREAL -> "Unreal"
    Ecosystem.GODOT -> "Godot"
}
