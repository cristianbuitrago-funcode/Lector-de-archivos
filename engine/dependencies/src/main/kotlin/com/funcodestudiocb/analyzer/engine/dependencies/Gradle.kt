package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.dependencies.ManifestParsers.addDependency
import com.google.re2j.Pattern

/**
 * Dependencias y complementos declarados en build.gradle(.kts) y settings.gradle(.kts), línea a línea.
 * Las referencias a catálogos de versiones (`libs.x.y`) se guardan para resolverlas cuando se haya leído el catálogo.
 * Las dependencias entre módulos del propio proyecto (`project(":app")`) no son externas y se dejan para la Fase 5.
 */
internal object Gradle {
    private const val CONFIGURATIONS =
        "implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|androidTestImplementation|" +
            "debugImplementation|releaseImplementation|kapt|ksp|annotationProcessor|classpath|compile|testCompile|" +
            "coreLibraryDesugaring|lintChecks|detektPlugins|developmentOnly|testFixturesImplementation|kaptTest|kspTest|wearApp"

    private val CONFIGURATION = Pattern.compile("""(?:^|[^A-Za-z0-9_."])($CONFIGURATIONS)\b\s*\(?\s*(.*)$""")
    private val COORDINATE = Pattern.compile("""["']([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^"'@\s:]+))?(?::[^"'@\s]*)?(?:@[A-Za-z]+)?["']""")
    private val MAP_NOTATION = Pattern.compile("""group\s*[:=]\s*["']([^"']+)["']\s*,\s*name\s*[:=]\s*["']([^"']+)["'](?:\s*,\s*version\s*[:=]\s*["']([^"']+)["'])?""")
    private val CATALOG_REF = Pattern.compile("""\b([a-z][A-Za-z0-9]*)\.((?:bundles\.)?[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)""")
    private val PLUGIN_ID = Pattern.compile("""^\s*id\s*\(?\s*["']([A-Za-z0-9_.\-]+)["']\s*\)?(?:\s*version\s*\(?\s*["']([^"']+)["'])?""")
    private val PLUGIN_KOTLIN = Pattern.compile("""^\s*kotlin\s*\(\s*["']([A-Za-z0-9_.\-]+)["']\s*\)(?:\s*version\s*\(?\s*["']([^"']+)["'])?""")
    private val PLUGIN_ALIAS = Pattern.compile("""^\s*alias\s*\(\s*([a-z][A-Za-z0-9]*)\.plugins\.([A-Za-z0-9_.]+)\s*\)""")
    private val APPLY_PLUGIN = Pattern.compile("""apply\s*\(?\s*plugin\s*[:=]\s*["']([A-Za-z0-9_.\-]+)["']""")

    fun scopeOf(configuration: String): DependencyScope = when {
        configuration.startsWith("test") || configuration.startsWith("androidTest") || configuration.endsWith("Test") -> DependencyScope.TEST
        configuration in setOf("kapt", "ksp", "annotationProcessor", "classpath", "compileOnly", "lintChecks", "detektPlugins", "coreLibraryDesugaring") -> DependencyScope.BUILD
        configuration.startsWith("debug") || configuration == "developmentOnly" -> DependencyScope.DEVELOPMENT
        else -> DependencyScope.RUNTIME
    }

    fun parse(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        val refs = mutableListOf<CatalogRef>()
        var pluginsDepth = -1
        var depth = 0
        var inBlockComment = false
        text.lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
                line = line.substring(end + 2)
                inBlockComment = false
            }
            val blockStart = line.indexOf("/*")
            if (blockStart >= 0 && line.indexOf("*/", blockStart) < 0) {
                inBlockComment = true
                line = line.substring(0, blockStart)
            }
            line = line.substringBefore("//")
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            if (pluginsDepth < 0 && (trimmed.startsWith("plugins {") || trimmed == "plugins{" || trimmed == "plugins")) pluginsDepth = depth
            val inPlugins = pluginsDepth >= 0 && depth > pluginsDepth || (pluginsDepth >= 0 && trimmed.startsWith("plugins"))
            if (inPlugins) plugin(trimmed, lineNumber, out, refs)
            APPLY_PLUGIN.matcher(line).let { if (it.find()) out.addDependency(it.group(1), null, DependencyScope.BUILD, lineNumber) }

            val conf = CONFIGURATION.matcher(line)
            if (conf.find()) {
                val configuration = conf.group(1)
                val rest = conf.group(2)
                val platform = rest.startsWith("platform") || rest.startsWith("enforcedPlatform")
                val scope = if (platform) DependencyScope.PLATFORM else scopeOf(configuration)
                val coordinate = COORDINATE.matcher(rest)
                val map = MAP_NOTATION.matcher(rest)
                when {
                    rest.startsWith("project") || rest.startsWith("projects.") || rest.startsWith("files") || rest.startsWith("fileTree") -> Unit
                    coordinate.find() -> out.addDependency("${coordinate.group(1)}:${coordinate.group(2)}", coordinate.group(3), scope, lineNumber)
                    map.find() -> out.addDependency("${map.group(1)}:${map.group(2)}", map.group(3), scope, lineNumber)
                    else -> {
                        val ref = CATALOG_REF.matcher(rest)
                        if (ref.find() && ref.group(1) != "project" && ref.group(1) != "projects") {
                            val accessor = ref.group(2)
                            val kind = if (accessor.startsWith("bundles.")) CatalogRefKind.BUNDLE else CatalogRefKind.LIBRARY
                            refs += CatalogRef(ref.group(1), CatalogAlias.normalize(accessor.removePrefix("bundles.")), kind, scope, lineNumber)
                        }
                    }
                }
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (pluginsDepth >= 0 && depth <= pluginsDepth && !trimmed.startsWith("plugins")) pluginsDepth = -1
        }
        return ParsedManifest(path, Ecosystem.GRADLE, out.take(ManifestParsers.MAX_PER_MANIFEST), refs.take(ManifestParsers.MAX_PER_MANIFEST))
    }

    private fun plugin(line: String, lineNumber: Int, out: MutableList<RawDependency>, refs: MutableList<CatalogRef>) {
        val body = if (line.startsWith("plugins")) line.removePrefix("plugins").trimStart('{', ' ') else line
        val id = PLUGIN_ID.matcher(body)
        val kotlin = PLUGIN_KOTLIN.matcher(body)
        val alias = PLUGIN_ALIAS.matcher(body)
        when {
            id.find() -> out.addDependency(id.group(1), id.group(2), DependencyScope.BUILD, lineNumber)
            kotlin.find() -> out.addDependency("org.jetbrains.kotlin.${kotlin.group(1)}", kotlin.group(2), DependencyScope.BUILD, lineNumber)
            alias.find() -> refs += CatalogRef(alias.group(1), CatalogAlias.normalize(alias.group(2)), CatalogRefKind.PLUGIN, DependencyScope.BUILD, lineNumber)
        }
    }
}
