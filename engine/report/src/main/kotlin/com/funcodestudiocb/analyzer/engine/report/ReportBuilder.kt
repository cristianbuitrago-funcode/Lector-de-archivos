package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.LanguageCategory
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind
import com.funcodestudiocb.analyzer.engine.api.RESULT_SCHEMA_VERSION
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.report.Labels.count
import com.funcodestudiocb.analyzer.engine.report.Labels.number
import com.funcodestudiocb.analyzer.engine.report.Labels.percent

/**
 * Construye el informe (15 secciones y 2 anexos, §10.1 de la arquitectura) a partir de un análisis guardado.
 *
 * El resumen ejecutivo, los problemas, las recomendaciones y las conclusiones salen de plantillas deterministas: cada
 * frase se basa en un dato del análisis y cita la sección donde se muestra. No se usa IA generativa ni se inventa nada:
 * si una parte no se analizó, la sección lo dice.
 */
object ReportBuilder {

    const val TITLE = "Diagnóstico técnico del proyecto"
    private const val MAX_ROWS = 30

    /** Problema encontrado, con su prioridad (0 = la más alta) y la sección donde está el detalle. */
    data class Problem(val priority: Int, val text: String, val where: String, val ref: String)

    fun build(r: AnalysisReport, meta: ReportMeta): ReportDocument {
        val problems = problems(r)
        val sections = listOf(
            section(1, "Resumen ejecutivo", summary(r, meta, problems)),
            section(2, "Descripción general", overview(r, meta)),
            section(3, "Tecnologías", technologies(r)),
            section(4, "Arquitectura", architecture(r)),
            section(5, "Estructura de carpetas", structure(r)),
            section(6, "Archivos más importantes", importantFiles(r)),
            section(7, "Relaciones entre archivos", relations(r)),
            section(8, "Bases de datos y conexiones", databases(r)),
            section(9, "APIs y servicios externos", apis(r)),
            section(10, "Dependencias", dependencies(r)),
            section(11, "Auditoría de seguridad", security(r)),
            section(12, "Problemas encontrados", problemsSection(problems)),
            section(13, "Recomendaciones", recommendations(r)),
            section(14, "Nivel general de mantenimiento", maintenance(r)),
            section(15, "Conclusiones", conclusions(r, problems)),
            ReportSection("anexo-a", "A", "Cobertura y limitaciones del análisis", coverage(r)),
            ReportSection("anexo-b", "B", "Metadatos", metadata(r, meta)),
        )
        return ReportDocument(TITLE, meta.archiveName, meta, sections)
    }

    private fun section(n: Int, title: String, blocks: List<Block>) = ReportSection("s$n", "$n", title, blocks)

    private fun notAnalyzed(what: String) = Block.Callout(Tone.INFO, "$what no se analizó en este análisis (ver Anexo A).")

    private fun more(total: Int, shown: Int, what: String): String? = if (total > shown) "Se muestran $shown de ${number(total)} $what." else null

    private fun place(path: String?, line: Int?): String = when {
        path == null -> "—"
        line != null -> "$path:$line"
        else -> path
    }

    // ------------------------------------------------------------------ 1. resumen ejecutivo

    private fun summary(r: AnalysisReport, meta: ReportMeta, problems: List<Problem>): List<Block> {
        val out = mutableListOf<Statement>()
        val s = r.summary
        if (s != null) {
            out += Statement(
                "Se analizaron ${number(s.analyzedFiles)} de ${count(s.files, "archivo", "archivos")} de «${meta.archiveName}» (${Labels.bytes(meta.archiveSize)}).",
                "§2",
            )
        }
        val outcome = r.outcome()
        if (outcome != AnalysisOutcome.COMPLETED) out += Statement("El análisis es ${Labels.outcome(outcome).lowercase()}: lo que no se pudo analizar se detalla en el anexo.", "Anexo A")
        val code = r.languages.filter { it.category == LanguageCategory.PROGRAMMING }.sortedByDescending { it.code }
        val totalCode = code.sumOf { it.code }
        if (totalCode > 0) {
            val top = code.take(2).joinToString(" y ") { "${it.name} (${percent(it.code.toDouble() / totalCode)})" }
            out += Statement("El código suma ${count(totalCode, "línea", "líneas")} y está escrito sobre todo en $top.", "§3")
        }
        val techs = r.technologies.filter { it.category != TechnologyCategory.LANGUAGE_RUNTIME }.sortedBy { it.confidence.ordinal }
        if (techs.isNotEmpty()) out += Statement("Se reconocen ${count(techs.size, "tecnología", "tecnologías")}, entre ellas ${techs.take(3).joinToString { it.name }}.", "§3")
        r.architecture?.let { a ->
            val pattern = a.pattern?.let { "La arquitectura aparente es «$it»" } ?: "No se reconoce un patrón de arquitectura claro"
            val flows = if (a.flows > 0) "; ${a.completeFlows} de ${count(a.flows, "flujo", "flujos")} de la API llegan hasta la base de datos" else ""
            out += Statement("$pattern$flows.", "§4")
        }
        r.securitySummary?.let { sec ->
            val critical = (sec.bySeverity[Severity.CRITICAL] ?: 0) + (sec.bySeverity[Severity.HIGH] ?: 0)
            out += if (sec.findings == 0) {
                Statement("La auditoría de seguridad no encontró hallazgos con las ${number(sec.rules)} reglas aplicadas.", "§11")
            } else {
                val severe = if (critical == 0) "ninguno de severidad crítica o alta" else "$critical de severidad crítica o alta"
                Statement("La auditoría de seguridad encontró ${count(sec.findings, "hallazgo", "hallazgos")}, $severe.", "§11")
            }
        }
        r.dependencySummary?.let { d ->
            val total = d.direct + d.indirect
            if (total > 0) {
                val vulns = when {
                    d.databases.isNotEmpty() && d.databases.none { it.date != null } -> "; sus vulnerabilidades no se comprobaron porque falta la base de vulnerabilidades"
                    d.vulnerablePackages > 0 -> "; ${count(d.vulnerablePackages, "tiene", "tienen")} vulnerabilidades conocidas (${count(d.vulnerabilities, "aviso", "avisos")})"
                    else -> "; ninguna con vulnerabilidades conocidas en la base descargada"
                }
                out += Statement("El proyecto usa ${count(d.direct, "dependencia directa", "dependencias directas")} y ${number(d.indirect)} indirectas$vulns.", "§10")
            }
        }
        r.maintenance?.let { m ->
            val score = m.score
            if (score != null) {
                val lowest = m.parts.filter { it.score != null }.sortedBy { it.score }.take(2).joinToString(" y ") { it.name.lowercase() }
                out += Statement("El índice de mantenimiento es $score sobre 100 (${Labels.maintenance(m.level!!).lowercase()}); lo que más lo baja: $lowest.", "§14")
            }
        }
        if (problems.isNotEmpty()) {
            val first = problems.first().text.replaceFirstChar { it.lowercase() }
            out += Statement(if (problems.size == 1) "Se señala 1 problema: $first." else "Se señalan ${number(problems.size)} problemas; el más prioritario: $first.", "§12")
        }
        if (out.isEmpty()) return listOf(Block.Callout(Tone.INFO, "El análisis no tiene datos suficientes para un resumen (ver Anexo A)."))
        return listOf(
            Block.Paragraph("Cada afirmación indica la sección del informe donde está el dato en que se basa."),
            Block.Statements(out),
        )
    }

    // ------------------------------------------------------------------ 2. descripción general

    private fun overview(r: AnalysisReport, meta: ReportMeta): List<Block> {
        val facts = mutableListOf("Archivo" to meta.archiveName, "Tamaño" to Labels.bytes(meta.archiveSize))
        r.archive?.let { facts += "Formato" to it.format.name }
        meta.analyzedAtEpochMs?.let { facts += "Fecha del análisis" to Labels.dateTime(it, meta.zone) }
        facts += "Resultado" to Labels.outcome(r.outcome())
        r.summary?.let { s ->
            facts += "Archivos" to "${number(s.files)} (${number(s.analyzedFiles)} analizados, ${number(s.notAnalyzedFiles)} sin analizar)"
            facts += "Excluidos" to "${count(s.excludedFiles, "archivo", "archivos")} y ${count(s.excludedDirectories, "carpeta", "carpetas")} (dependencias descargadas, compilados…)"
            if (s.duplicateGroups > 0) facts += "Duplicados" to "${count(s.duplicateFiles, "archivo idéntico", "archivos idénticos")} en ${count(s.duplicateGroups, "grupo", "grupos")}"
        }
        val blocks = mutableListOf<Block>(Block.Facts(facts))
        if (r.projectRoots.isNotEmpty()) {
            blocks += Block.Heading("Subproyectos")
            blocks += Block.Table(
                listOf("Carpeta", "Tipo", "Reconocido por"),
                r.projectRoots.take(MAX_ROWS).map { listOf(it.directory.ifEmpty { "(raíz)" }, it.ecosystem.name.lowercase().replaceFirstChar { c -> c.uppercase() }, it.marker) },
                note = more(r.projectRoots.size, MAX_ROWS, "subproyectos"),
            )
        }
        if (r.entryPoints.isNotEmpty()) {
            blocks += Block.Heading("Puntos de entrada")
            val shown = r.entryPoints.sortedBy { it.confidence.ordinal }.take(15)
            blocks += Block.Table(
                listOf("Tipo", "Archivo", "Certeza"),
                shown.map { listOf(Labels.entryPoint(it.kind), it.path ?: it.target ?: "—", Labels.confidence(it.confidence)) },
                note = more(r.entryPoints.size, shown.size, "puntos de entrada"),
            )
        }
        val warnings = r.findings.filter { it.severity.ordinal <= Severity.MEDIUM.ordinal }
        if (warnings.isNotEmpty()) {
            blocks += Block.Heading("Avisos del comprimido")
            blocks += Block.Table(
                listOf("Severidad", "Aviso", "Archivo"),
                warnings.take(15).map { listOf(Labels.severity(it.severity), it.detail, it.path ?: "—") },
                tones = warnings.take(15).map { Labels.severityTone(it.severity) },
                note = more(warnings.size, 15, "avisos"),
            )
        }
        return blocks
    }

    // ------------------------------------------------------------------ 3. tecnologías

    private fun technologies(r: AnalysisReport): List<Block> {
        if (!r.hasTechnologies) return listOf(notAnalyzed("La detección de lenguajes y tecnologías"))
        val blocks = mutableListOf<Block>()
        val code = r.languages.filter { it.category == LanguageCategory.PROGRAMMING || it.category == LanguageCategory.MARKUP }.sortedByDescending { it.code }
        val total = code.sumOf { it.code }
        if (code.isNotEmpty() && total > 0) {
            blocks += Block.Heading("Lenguajes")
            blocks += Block.Bars(code.take(8).map { Bar(it.name, it.code, total, Tone.INFO, "${number(it.code)} líneas · ${percent(it.code.toDouble() / total)}") })
            blocks += Block.Table(
                listOf("Lenguaje", "Archivos", "Código", "Comentarios", "Análisis"),
                code.take(MAX_ROWS).map {
                    listOf(it.name, number(it.files), number(it.code), number(it.comment), if (it.syntaxFiles > 0) "Sintáctico" else "Léxico")
                },
                note = more(code.size, MAX_ROWS, "lenguajes"),
            )
        }
        val techs = r.technologies.sortedWith(compareBy({ it.category.ordinal }, { it.confidence.ordinal }, { it.name }))
        blocks += Block.Heading("Frameworks, bibliotecas y servicios")
        blocks += if (techs.isEmpty()) {
            Block.Paragraph("No se reconoció ninguna tecnología con el catálogo de reglas.")
        } else {
            Block.Table(
                listOf("Tecnología", "Categoría", "Versión", "Confianza", "Dónde se encontró"),
                techs.take(MAX_ROWS * 2).map { t ->
                    val ev = t.evidence.firstOrNull()
                    listOf(t.name, Labels.technologyCategory(t.category), t.versions.joinToString().ifEmpty { "—" }, Labels.confidence(t.confidence), place(ev?.path, ev?.line))
                },
                note = more(techs.size, MAX_ROWS * 2, "tecnologías"),
            )
        }
        return blocks
    }

    // ------------------------------------------------------------------ 4. arquitectura

    private fun architecture(r: AnalysisReport): List<Block> {
        val a = r.architecture ?: return listOf(notAnalyzed("La arquitectura"))
        val blocks = mutableListOf<Block>(
            Block.Facts(
                listOf(
                    "Patrón aparente" to (a.pattern ?: "No reconocido"),
                    "Archivos con capa clara" to "${number(a.classified)} de ${number(a.files)}",
                    "Dependencias inversas" to number(a.inverted),
                    "Flujos de la API" to "${number(a.flows)} (${number(a.completeFlows)} llegan a la base de datos)",
                ),
            ),
        )
        r.diagrams.firstOrNull { it.kind == DiagramKind.LAYERS }?.let { blocks += Block.Figure(it, "Capas de la arquitectura y conexiones entre ellas. En naranja, dependencias inversas.") }
        val byLayer = a.byLayer.entries.filter { it.value > 0 }.sortedBy { it.key.ordinal }
        if (byLayer.isNotEmpty()) blocks += Block.Table(listOf("Capa", "Archivos"), byLayer.map { listOf(Labels.layer(it.key), number(it.value)) })
        if (r.flows.isNotEmpty()) {
            blocks += Block.Heading("Flujos de información")
            blocks += Block.Paragraph("Cada paso es una conexión encontrada en el código; si falta una, el flujo termina ahí en lugar de suponerla.")
            val shown = r.flows.take(15)
            blocks += Block.Table(
                listOf("Ruta", "Recorrido", "Certeza"),
                shown.map { f -> listOf("${f.method} ${f.path}", f.steps.joinToString(" → ") { "${Labels.layer(it.layer)}: ${it.label}" }, Labels.relationConfidence(f.confidence)) },
                tones = shown.map { if (it.reachesDatabase) Tone.GOOD else Tone.NEUTRAL },
                note = more(r.flows.size, shown.size, "flujos"),
            )
        }
        if (a.invertedSamples.isNotEmpty()) {
            blocks += Block.Heading("Dependencias inversas")
            blocks += Block.Bullets(a.invertedSamples)
        }
        if (a.notes.isNotEmpty()) blocks += Block.Bullets(a.notes)
        return blocks
    }

    // ------------------------------------------------------------------ 5. estructura de carpetas

    private fun structure(r: AnalysisReport): List<Block> {
        val analyzed = r.files.filter { it.status == FileStatus.ANALYZED }.map { it.path }
        if (analyzed.isEmpty()) return listOf(Block.Paragraph("No hay archivos analizados."))
        val firstSegments = analyzed.map { it.substringBefore('/', "") }.toSet()
        // Si todo cuelga de una carpeta (el nombre del proyecto), se agrupa un nivel más abajo.
        val depth = if (firstSegments.size == 1 && firstSegments.first().isNotEmpty()) 2 else 1
        val linesByPath = r.profiles.associate { it.path to (it.language?.code ?: 0) }
        val rolesByPath = r.profiles.associate { it.path to it.roles.map { a -> a.role } }
        val groups = analyzed.groupBy { path ->
            val parts = path.split('/')
            if (parts.size <= depth) "(archivos sueltos)" else parts.take(depth).joinToString("/")
        }
        val rows = groups.entries.sortedByDescending { it.value.size }.take(MAX_ROWS).map { (folder, paths) ->
            val main = paths.flatMap { rolesByPath[it].orEmpty() }.filter { it != FileRole.SOURCE && it != FileRole.POSSIBLY_UNNECESSARY }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            listOf(folder, number(paths.size), number(paths.sumOf { linesByPath[it] ?: 0 }), main?.let(Labels::role) ?: "Código fuente")
        }
        val blocks = mutableListOf<Block>(
            Block.Table(listOf("Carpeta", "Archivos", "Líneas de código", "Función principal"), rows, note = more(groups.size, rows.size, "carpetas")),
        )
        r.inventory?.let { inv ->
            val roles = inv.roleCounts.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            if (roles.isNotEmpty()) {
                blocks += Block.Heading("Funciones de los archivos")
                blocks += Block.Table(listOf("Función", "Archivos"), roles.map { listOf(Labels.role(it.key), number(it.value)) })
            }
        }
        r.diagrams.firstOrNull { it.kind == DiagramKind.MODULES }?.let { blocks += Block.Figure(it, "Módulos (carpetas) y sus dependencias. En amarillo, ciclos.") }
        return blocks
    }

    // ------------------------------------------------------------------ 6. archivos más importantes

    private fun importantFiles(r: AnalysisReport): List<Block> {
        if (r.fileGraphs.isEmpty()) return listOf(notAnalyzed("La importancia de los archivos (relaciones)"))
        val entry = r.entryPoints.mapNotNull { e -> e.path?.let { it to e.kind } }.toMap()
        val roles = r.profiles.associate { it.path to it.roles.map { a -> a.role }.toSet() }
        val layers = r.fileLayers.associateBy { it.path }
        val findings = r.security.mapNotNull { it.path }.groupingBy { it }.eachCount()
        val metrics = r.fileMetrics.associateBy { it.path }
        val top = r.fileGraphs.sortedBy { it.rank }.take(15)
        val rows = top.map { g ->
            val why = mutableListOf<String>()
            entry[g.path]?.let { why += "punto de entrada (${Labels.entryPoint(it).lowercase()})" }
            if (g.incoming > 0) why += "lo usan ${count(g.incoming, "archivo", "archivos")}"
            if (g.transitiveDependents > g.incoming) why += "un cambio puede afectar a ${number(g.transitiveDependents)}${if (g.transitiveCapped) " o más" else ""}"
            roles[g.path].orEmpty().filter { it in KEY_ROLES }.forEach { why += Labels.role(it).lowercase() }
            layers[g.path]?.let { why += "capa ${Labels.layer(it.layer)}" }
            findings[g.path]?.let { why += count(it, "hallazgo de seguridad", "hallazgos de seguridad") }
            metrics[g.path]?.takeIf { it.maxComplexity > 20 }?.let { why += "función con complejidad ${it.maxComplexity}" }
            listOf("${g.rank}", g.path, why.joinToString("; ").ifEmpty { "posición en el grafo de dependencias" })
        }
        return listOf(
            Block.Paragraph(
                "Ordenados por su importancia en el grafo de dependencias (los archivos de los que dependen muchos otros pesan más), " +
                    "con los motivos que los hacen importantes.",
            ),
            Block.Table(listOf("Puesto", "Archivo", "Por qué es importante"), rows),
        )
    }

    private val KEY_ROLES = setOf(FileRole.CONFIGURATION, FileRole.DATABASE, FileRole.AUTHENTICATION, FileRole.SERVER, FileRole.BUILD)

    // ------------------------------------------------------------------ 7. relaciones

    private fun relations(r: AnalysisReport): List<Block> {
        val s = r.relationsSummary ?: return listOf(notAnalyzed("Las relaciones entre archivos"))
        val blocks = mutableListOf<Block>(
            Block.Facts(
                listOf(
                    "Conexiones" to number(s.relations),
                    "Entre archivos del proyecto" to number(s.internal),
                    "Con paquetes o servicios externos" to number(s.external),
                    "Rotas (el destino no existe)" to number(s.broken),
                    "Ciclos de dependencias" to number(s.cycles),
                    "Archivos que nadie usa" to number(s.unreferencedFiles),
                ),
            ),
        )
        val kinds = s.byKind.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        if (kinds.isNotEmpty()) {
            val max = kinds.maxOf { it.value }.toLong()
            blocks += Block.Heading("Por tipo")
            blocks += Block.Bars(kinds.map { Bar(Labels.relationKind(it.key), it.value.toLong(), max, Tone.INFO, number(it.value)) })
        }
        val conf = s.byConfidence.entries.filter { it.value > 0 }.sortedBy { it.key.ordinal }
        if (conf.isNotEmpty()) blocks += Block.Table(listOf("Certeza", "Conexiones"), conf.map { listOf(Labels.relationConfidence(it.key), number(it.value)) })
        if (r.cycles.isNotEmpty()) {
            blocks += Block.Heading("Ciclos")
            blocks += Block.Bullets(r.cycles.take(10).map { c -> c.paths.take(6).joinToString(" → ") + if (c.size > 6) " … (${c.size} archivos)" else "" })
            more(r.cycles.size, 10, "ciclos")?.let { blocks += Block.Paragraph(it) }
        }
        return blocks
    }

    // ------------------------------------------------------------------ 8. bases de datos

    private fun databases(r: AnalysisReport): List<Block> {
        val techs = r.technologies.filter { it.category == TechnologyCategory.DATABASE || it.category == TechnologyCategory.DATA_ACCESS }
        val blocks = mutableListOf<Block>()
        if (techs.isNotEmpty()) {
            blocks += Block.Table(
                listOf("Base de datos o acceso a datos", "Confianza", "Dónde se encontró"),
                techs.map { t -> listOf(t.name, Labels.confidence(t.confidence), place(t.evidence.firstOrNull()?.path, t.evidence.firstOrNull()?.line)) },
            )
        }
        if (r.dataEntities.isNotEmpty()) {
            blocks += Block.Heading("Tablas y colecciones")
            val shown = r.dataEntities.sortedByDescending { it.accessedBy }.take(MAX_ROWS)
            blocks += Block.Table(
                listOf("Nombre", "Tipo", "Definida en", "Archivos que la usan", "Operaciones"),
                shown.map { e -> listOf(e.name, Labels.dataEntity(e.kind), place(e.file, e.line), number(e.accessedBy), e.operations.joinToString().ifEmpty { "—" }) },
                note = more(r.dataEntities.size, shown.size, "tablas"),
            )
        }
        val access = r.relations.count { it.kind == RelationKind.DB_ACCESS }
        if (access > 0) blocks += Block.Paragraph("${count(access, "conexión", "conexiones")} de acceso a datos en el código (detalle en §7).")
        if (blocks.isEmpty()) blocks += Block.Paragraph("No se encontraron bases de datos, tablas ni accesos a datos.")
        return blocks
    }

    // ------------------------------------------------------------------ 9. APIs y servicios externos

    private fun apis(r: AnalysisReport): List<Block> {
        val blocks = mutableListOf<Block>()
        val api = r.endpoints.filter { it.kind == com.funcodestudiocb.analyzer.engine.api.EndpointKind.API }.sortedWith(compareBy({ it.path }, { it.method }))
        if (api.isNotEmpty()) {
            blocks += Block.Heading("Rutas de la API")
            val shown = api.take(MAX_ROWS + 10)
            blocks += Block.Table(
                listOf("Método", "Ruta", "Definida en", "Framework", "Llamadas encontradas"),
                shown.map { e -> listOf(e.method, e.path, place(e.file, e.line), e.framework, number(e.callerCount)) },
                note = more(api.size, shown.size, "rutas"),
            )
        }
        val services = r.technologies.filter { it.category == TechnologyCategory.EXTERNAL_SERVICE || it.category == TechnologyCategory.CLOUD }
        val hosts = r.relations.filter { it.kind == RelationKind.HTTP && it.external != null }.groupingBy { it.external!! }.eachCount()
            .entries.sortedByDescending { it.value }
        if (services.isNotEmpty() || hosts.isNotEmpty()) {
            blocks += Block.Heading("Servicios externos")
            val rows = services.map { listOf(it.name, Labels.technologyCategory(it.category), Labels.confidence(it.confidence)) } +
                hosts.take(20).map { listOf(it.key, "Llamada HTTP", count(it.value, "llamada", "llamadas")) }
            blocks += Block.Table(listOf("Servicio", "Tipo", "Detalle"), rows)
        }
        r.diagrams.firstOrNull { it.kind == DiagramKind.PROJECTS }?.let { blocks += Block.Figure(it, "Proyectos, servicios externos y dependencias principales.") }
        if (blocks.isEmpty()) blocks += Block.Paragraph("No se encontraron rutas de una API ni llamadas a servicios externos.")
        return blocks
    }

    // ------------------------------------------------------------------ 10. dependencias

    private fun dependencies(r: AnalysisReport): List<Block> {
        val d = r.dependencySummary ?: return listOf(notAnalyzed("La auditoría de dependencias"))
        val blocks = mutableListOf<Block>()
        val noDb = d.databases.isNotEmpty() && d.databases.none { it.date != null }
        if (noDb) blocks += Block.Callout(Tone.WARNING, "No había base de vulnerabilidades descargada: las vulnerabilidades no se comprobaron (se descarga en Ajustes).")
        blocks += Block.Facts(
            listOf(
                "Directas" to number(d.direct),
                "Indirectas" to number(d.indirect),
                "Archivos de bloqueo" to number(d.lockfiles),
                "Con vulnerabilidades conocidas" to if (noDb) "No comprobado" else "${number(d.vulnerablePackages)} (${count(d.vulnerabilities, "aviso", "avisos")})",
                "Paquetes maliciosos" to number(d.malicious),
                "Sin uso visto en el código" to number(d.notSeen),
                "Sin soporte / lo pierden pronto" to "${number(d.endOfLife)} / ${number(d.endingSoon)}",
            ),
        )
        val vulnerable = r.resolvedDependencies.filter { it.vulnerabilities.isNotEmpty() }
            .sortedBy { dep -> dep.vulnerabilities.minOf { it.severity.ordinal } }
        if (vulnerable.isNotEmpty()) {
            blocks += Block.Heading("Dependencias vulnerables")
            val shown = vulnerable.take(MAX_ROWS)
            blocks += Block.Table(
                listOf("Paquete", "Versión", "Peor severidad", "Avisos", "Corregida en", "Uso"),
                shown.map { dep ->
                    val worst = dep.vulnerabilities.minBy { it.severity.ordinal }
                    listOf(
                        dep.name, dep.version ?: "—", Labels.severity(worst.severity) + if (dep.vulnerabilities.any { it.malicious }) " (malicioso)" else "",
                        number(dep.vulnerabilityCount), fixVersion(dep) ?: "—", Labels.usage(dep.usage),
                    )
                },
                tones = shown.map { dep -> Labels.severityTone(dep.vulnerabilities.minOf { it.severity }) },
                note = more(vulnerable.size, shown.size, "dependencias vulnerables"),
            )
        }
        val eol = r.endOfLife.filter { it.status == EolStatus.UNSUPPORTED || it.status == EolStatus.ENDING_SOON }
        if (eol.isNotEmpty()) {
            blocks += Block.Heading("Fin de soporte")
            blocks += Block.Table(
                listOf("Producto", "Versión", "Estado", "Fin de soporte", "Dónde"),
                eol.map { listOf(it.label, it.version, Labels.eol(it.status), it.eolDate ?: "—", place(it.path, it.line)) },
                tones = eol.map { if (it.status == EolStatus.UNSUPPORTED) Tone.DANGER else Tone.WARNING },
            )
        }
        if (d.notEvaluated.isNotEmpty()) blocks += Block.Bullets(d.notEvaluated)
        return blocks
    }

    /** Primera versión que corrige todos los avisos (si es una sola). */
    private fun fixVersion(dep: ResolvedDependency): String? = dep.vulnerabilities.flatMap { it.fixedIn }.distinct().singleOrNull()
        ?: dep.vulnerabilities.flatMap { it.fixedIn }.distinct().takeIf { it.isNotEmpty() }?.joinToString(limit = 3)

    // ------------------------------------------------------------------ 11. seguridad

    private fun security(r: AnalysisReport): List<Block> {
        val s = r.securitySummary ?: return listOf(notAnalyzed("La auditoría de seguridad"))
        val blocks = mutableListOf<Block>(
            Block.Facts(
                listOf(
                    "Hallazgos" to number(s.findings),
                    "Reglas aplicadas" to number(s.rules),
                    "Archivos examinados" to number(s.filesExamined),
                    "Rebajados por el contexto" to number(s.downgraded),
                    "Avisos silenciados en el código" to number(s.suppressions),
                ),
            ),
        )
        val sev = Severity.entries.filter { (s.bySeverity[it] ?: 0) > 0 }
        if (sev.isNotEmpty()) {
            val max = sev.maxOf { s.bySeverity.getValue(it) }.toLong()
            blocks += Block.Bars(sev.map { Bar(Labels.severity(it), s.bySeverity.getValue(it).toLong(), max, Labels.severityTone(it), number(s.bySeverity.getValue(it))) })
        }
        val ordered = r.security.sortedWith(compareBy<SecurityFinding>({ it.severity.ordinal }, { it.certainty.ordinal }, { it.path ?: "" }, { it.line ?: 0 }))
        if (ordered.isNotEmpty()) {
            val shown = ordered.take(MAX_ROWS + 10)
            blocks += Block.Table(
                listOf("Severidad", "Hallazgo", "Ubicación", "Certeza", "Regla"),
                shown.map { f ->
                    val name = f.name + (f.masked?.let { " ($it)" } ?: "")
                    listOf(Labels.severity(f.severity), name, place(f.path, f.line), Labels.certainty(f.certainty), f.ruleId)
                },
                tones = shown.map { Labels.severityTone(it.severity) },
                note = more(ordered.size, shown.size, "hallazgos (todos están en la pantalla Seguridad y en el JSON)"),
            )
            blocks += Block.Heading("Recomendaciones de los hallazgos más graves")
            blocks += Block.Bullets(ordered.filter { it.severity.ordinal <= Severity.HIGH.ordinal }.distinctBy { it.ruleId }.take(8).map { "${it.name}: ${it.recommendation}" }
                .ifEmpty { listOf("No hay hallazgos críticos ni altos.") })
        }
        if (s.dynamicChecks.isNotEmpty()) {
            blocks += Block.Heading("Pruebas dinámicas recomendadas")
            blocks += Block.Paragraph("Lo que el análisis estático no puede confirmar y conviene probar con la aplicación en marcha:")
            blocks += Block.Bullets(s.dynamicChecks)
        }
        if (s.notEvaluated.isNotEmpty()) blocks += Block.Bullets(s.notEvaluated.map { "No evaluado: $it" })
        return blocks
    }

    // ------------------------------------------------------------------ 12. problemas

    /** Problemas principales, del más al menos prioritario. */
    fun problems(r: AnalysisReport): List<Problem> {
        val out = mutableListOf<Problem>()
        r.security.filter { it.severity == Severity.CRITICAL || it.severity == Severity.HIGH }
            .sortedWith(compareBy({ it.severity.ordinal }, { it.certainty.ordinal }))
            .take(10)
            .forEach { f ->
                val certainty = if (f.certainty == Certainty.CONFIRMED) "" else ", ${Labels.certainty(f.certainty).lowercase()}"
                out += Problem(f.severity.ordinal, "${f.name} (severidad ${Labels.severity(f.severity).lowercase()}$certainty)", place(f.path, f.line), "§11")
            }
        r.resolvedDependencies.filter { d -> d.vulnerabilities.any { it.malicious } }.forEach {
            out += Problem(0, "Paquete malicioso: ${it.name} ${it.version.orEmpty()}".trim(), it.manifest ?: it.lockfile ?: "—", "§10")
        }
        r.resolvedDependencies.filter { d -> d.vulnerabilities.any { !it.malicious && it.severity.ordinal <= Severity.HIGH.ordinal } }.take(5).forEach { d ->
            val worst = d.vulnerabilities.minBy { it.severity.ordinal }
            out += Problem(worst.severity.ordinal, "Dependencia vulnerable (${Labels.severity(worst.severity).lowercase()}): ${d.name} ${d.version.orEmpty()}".trim(), place(d.manifest, d.manifestLine), "§10")
        }
        r.endOfLife.filter { it.status == EolStatus.UNSUPPORTED }.take(5).forEach {
            out += Problem(1, "Sin soporte: ${it.label} ${it.version}", place(it.path, it.line), "§10")
        }
        r.findings.filter { it.severity.ordinal <= Severity.HIGH.ordinal }.take(5).forEach {
            out += Problem(it.severity.ordinal, "Aviso del comprimido: ${it.detail}", it.path ?: "—", "§2")
        }
        r.metrics?.let { m ->
            m.complexFunctionList.filter { it.complexity > 20 }.take(3).forEach {
                out += Problem(2, "Función muy compleja: ${it.name} (complejidad ${it.complexity})", "${it.path}:${it.line}", "§14")
            }
            if (m.duplicationBase > 0 && m.duplicatedLines.toDouble() / m.duplicationBase > 0.05) {
                out += Problem(2, "Código repetido: ${percent(m.duplicatedLines.toDouble() / m.duplicationBase)} de las líneas", m.duplicates.firstOrNull()?.occurrences?.joinToString() ?: "—", "§14")
            }
            if (m.testFiles == 0 && m.codeFiles > 0) out += Problem(2, "No se encontraron pruebas automáticas", "—", "§14")
        }
        r.cycles.take(3).forEach { c -> out += Problem(3, "Ciclo de dependencias entre ${count(c.size, "archivo", "archivos")}", c.paths.take(3).joinToString(" → "), "§7") }
        r.architecture?.takeIf { it.inverted > 0 }?.let { out += Problem(3, "${count(it.inverted, "dependencia inversa", "dependencias inversas")} entre capas", it.invertedSamples.firstOrNull() ?: "—", "§4") }
        return out.sortedBy { it.priority }
    }

    private fun problemsSection(problems: List<Problem>): List<Block> {
        if (problems.isEmpty()) return listOf(Block.Callout(Tone.GOOD, "No se encontraron problemas destacados con los datos disponibles."))
        return listOf(
            Block.Paragraph("Ordenados por prioridad. Cada uno remite a la sección con el detalle."),
            Block.Table(
                listOf("Prioridad", "Problema", "Dónde", "Detalle"),
                problems.map { listOf(PRIORITY[it.priority.coerceIn(0, 3)], it.text, it.where, it.ref) },
                tones = problems.map { if (it.priority <= 1) Tone.DANGER else if (it.priority == 2) Tone.WARNING else Tone.INFO },
            ),
        )
    }

    private val PRIORITY = listOf("Urgente", "Alta", "Media", "Baja")

    // ------------------------------------------------------------------ 13. recomendaciones

    fun recommendationList(r: AnalysisReport): List<Statement> {
        val out = mutableListOf<Statement>()
        val severe = r.security.count { it.severity == Severity.CRITICAL || it.severity == Severity.HIGH }
        if (severe > 0) out += Statement("Corregir primero ${if (severe == 1) "el hallazgo crítico o alto" else "los $severe hallazgos críticos o altos"} de seguridad; cada ficha trae la corrección recomendada y un ejemplo.", "§11")
        r.resolvedDependencies.filter { d -> d.vulnerabilities.any { it.malicious } }.forEach { out += Statement("Quitar el paquete malicioso ${it.name} y revisar el equipo donde se instaló.", "§10") }
        r.resolvedDependencies.filter { d -> d.vulnerabilities.any { !it.malicious } }
            .sortedBy { d -> d.vulnerabilities.minOf { it.severity.ordinal } }.take(5).forEach { d ->
                val fix = fixVersion(d)
                out += Statement(if (fix != null) "Actualizar ${d.name} ${d.version.orEmpty()} a $fix." else "Sustituir o revisar ${d.name} ${d.version.orEmpty()}: sus avisos no tienen versión corregida.", "§10")
            }
        r.endOfLife.filter { it.status == EolStatus.UNSUPPORTED }.take(3).forEach {
            out += Statement("Migrar ${it.label} ${it.version} a una versión con soporte${it.eolDate?.let { d -> " (sin soporte desde $d)" } ?: ""}.", "§10")
        }
        val d = r.dependencySummary
        if (d != null && d.databases.isNotEmpty() && d.databases.none { it.date != null }) {
            out += Statement("Descargar la base de vulnerabilidades en Ajustes y repetir el análisis para comprobar las dependencias.", "§10")
        }
        r.metrics?.let { m ->
            m.complexFunctionList.filter { it.complexity > 20 }.take(3).forEach {
                out += Statement("Dividir ${it.name} (${it.path}:${it.line}): tiene complejidad ${it.complexity} y es difícil de probar y cambiar.", "§14")
            }
            m.duplicates.take(2).forEach { block ->
                if (block.lines >= 10) out += Statement("Extraer a una función común el bloque de ${block.lines} líneas repetido en ${block.occurrences.joinToString()}.", "§14")
            }
        }
        r.maintenance?.parts?.associateBy { it.id }?.let { parts ->
            if ((parts["tests"]?.score ?: 100) < 50) out += Statement("Añadir pruebas automáticas, empezando por los archivos más importantes (§6).", "§14")
            val tools = r.metrics?.qualityTools.orEmpty().map { it.kind }.toSet()
            if (QualityToolKind.CI !in tools && r.metrics != null) out += Statement("Configurar integración continua que compile y pase las pruebas en cada cambio.", "§14")
            if (QualityToolKind.LINTER !in tools && QualityToolKind.TYPE_CHECKER !in tools && r.metrics != null) out += Statement("Configurar un analizador de código (linter) para el lenguaje principal.", "§14")
            if ((parts["documentation"]?.score ?: 100) < 50 && r.metrics?.readme == null) out += Statement("Añadir un README que explique cómo compilar, configurar y ejecutar el proyecto.", "§14")
        }
        r.cycles.firstOrNull()?.let { out += Statement("Romper el ciclo de dependencias entre ${it.paths.take(3).joinToString(", ")}.", "§7") }
        val severeDebt = r.metrics?.debtByKind?.let { (it[com.funcodestudiocb.analyzer.engine.api.DebtKind.FIXME] ?: 0) + (it[com.funcodestudiocb.analyzer.engine.api.DebtKind.HACK] ?: 0) } ?: 0
        if (severeDebt > 0) out += Statement("Revisar ${if (severeDebt == 1) "la marca FIXME/HACK" else "las $severeDebt marcas FIXME/HACK"} que dejó el equipo en el código.", "§14")
        return out
    }

    private fun recommendations(r: AnalysisReport): List<Block> {
        val list = recommendationList(r)
        if (list.isEmpty()) return listOf(Block.Callout(Tone.GOOD, "No hay recomendaciones prioritarias con los datos disponibles."))
        return listOf(
            Block.Paragraph("En orden de prioridad: primero la seguridad y las dependencias, después el mantenimiento."),
            Block.Statements(list, numbered = true),
        )
    }

    // ------------------------------------------------------------------ 14. mantenimiento

    private fun maintenance(r: AnalysisReport): List<Block> {
        val index = r.maintenance ?: return listOf(notAnalyzed("El índice de mantenimiento"))
        val blocks = mutableListOf<Block>(
            Block.Gauge(
                index.score, index.level?.let(Labels::maintenance) ?: "No evaluado", Labels.maintenanceTone(index.level),
                "Indicador orientativo, no un estándar: sirve para comparar análisis del mismo proyecto y priorizar.",
            ),
            Block.Table(
                listOf("Sub-índice", "Peso", "Valor", "Lo medido"),
                index.parts.map { listOf(it.name, "${it.weight} %", it.score?.toString() ?: "No evaluado", it.measure) },
                tones = index.parts.map { p -> p.score?.let { s -> if (s >= 70) Tone.GOOD else if (s >= 50) Tone.WARNING else Tone.DANGER } ?: Tone.NEUTRAL },
            ),
            Block.Heading("Cómo se calcula"),
            Block.Paragraph(index.formula),
            Block.Bullets(index.parts.map { "${it.name}: ${it.explanation}" }),
        )
        if (index.notes.isNotEmpty()) blocks += Block.Bullets(index.notes)
        val m = r.metrics ?: return blocks
        blocks += Block.Heading("Cifras")
        blocks += Block.Facts(
            listOfNotNull(
                "Archivos de código" to "${number(m.codeFiles)} (${number(m.testFiles)} de pruebas)",
                "Líneas de código" to "${number(m.codeLines)} (${number(m.testLines)} de pruebas)",
                "Funciones medidas" to number(m.functions),
                m.averageComplexity?.let { "Complejidad media / mediana / máxima" to "${Labels.decimal(it)} / ${m.medianComplexity} / ${m.maxComplexity}" },
                "Funciones con complejidad > 10 / > 20" to "${number(m.functionsOver10)} / ${number(m.functionsOver20)}",
                "Código repetido" to if (m.duplicationBase > 0) "${percent(m.duplicatedLines.toDouble() / m.duplicationBase)} (${number(m.duplicatedLines)} líneas)" else "—",
                "Marcas de deuda" to m.debtByKind.entries.joinToString { "${it.key.name} ${it.value}" }.ifEmpty { "Ninguna" },
                "Herramientas de calidad" to m.qualityTools.joinToString { it.name }.ifEmpty { "Ninguna configurada" },
            ),
        )
        if (m.complexFunctionList.isNotEmpty()) {
            blocks += Block.Heading("Funciones más complejas")
            blocks += Block.Table(
                listOf("Función", "Archivo", "Complejidad", "Líneas"),
                m.complexFunctionList.take(10).map { listOf(it.name, "${it.path}:${it.line}", number(it.complexity), number(it.lines)) },
                tones = m.complexFunctionList.take(10).map { if (it.complexity > 20) Tone.DANGER else if (it.complexity > 10) Tone.WARNING else Tone.NEUTRAL },
            )
        }
        if (m.duplicates.isNotEmpty()) {
            blocks += Block.Heading("Código repetido")
            blocks += Block.Table(listOf("Líneas", "Dónde aparece"), m.duplicates.take(10).map { listOf(number(it.lines), it.occurrences.joinToString(" · ") + if (it.count > it.occurrences.size) " …" else "") })
        }
        if (m.debtSamples.isNotEmpty()) {
            blocks += Block.Heading("Deuda señalada en el código")
            blocks += Block.Table(listOf("Marca", "Dónde", "Texto"), m.debtSamples.take(15).map { listOf(it.kind.name, "${it.path}:${it.line}", it.text) })
        }
        if (m.notMeasured.isNotEmpty()) blocks += Block.Paragraph("Complejidad no medida (sin gramática): ${m.notMeasured.joinToString()}.")
        if (m.notes.isNotEmpty()) blocks += Block.Bullets(m.notes)
        return blocks
    }

    // ------------------------------------------------------------------ 15. conclusiones

    private fun conclusions(r: AnalysisReport, problems: List<Problem>): List<Block> {
        val out = mutableListOf<Statement>()
        r.maintenance?.score?.let { out += Statement("El nivel general de mantenimiento es ${Labels.maintenance(r.maintenance.level!!).lowercase()} ($it sobre 100).", "§14") }
        val severe = r.security.count { it.severity == Severity.CRITICAL || it.severity == Severity.HIGH }
        val malicious = r.dependencySummary?.malicious ?: 0
        when {
            malicious > 0 -> out += Statement("Lo más urgente: hay ${count(malicious, "paquete malicioso", "paquetes maliciosos")} entre las dependencias.", "§10")
            severe > 0 -> out += Statement("Lo más urgente es la seguridad: ${count(severe, "hallazgo crítico o alto", "hallazgos críticos o altos")}.", "§11")
            r.securitySummary != null -> out += Statement("No hay hallazgos de seguridad críticos ni altos con las reglas aplicadas; los de menor severidad están en la auditoría.", "§11")
        }
        val vulnerable = r.dependencySummary?.vulnerablePackages ?: 0
        if (vulnerable > 0) out += Statement("${count(vulnerable, "dependencia tiene", "dependencias tienen")} vulnerabilidades conocidas que se corrigen actualizando.", "§10")
        r.architecture?.let { a ->
            out += if (a.pattern != null) Statement("La estructura sigue un patrón reconocible («${a.pattern}»), lo que facilita orientarse en el código.", "§4")
            else Statement("No se reconoce un patrón de arquitectura claro: conviene documentar cómo se organiza el código.", "§4")
        }
        if (problems.isNotEmpty()) out += Statement("Las recomendaciones están ordenadas para atender primero los ${count(problems.size, "problema señalado", "problemas señalados")}.", "§13")
        out += if (r.outcome() == AnalysisOutcome.COMPLETED && r.coverage.isEmpty()) {
            Statement("El análisis cubrió todo el comprimido; aun así, es estático: no ejecuta el código ni prueba la aplicación en marcha.", "Anexo A")
        } else {
            Statement("El análisis tiene limitaciones que conviene leer antes de sacar conclusiones definitivas.", "Anexo A")
        }
        return listOf(Block.Statements(out))
    }

    // ------------------------------------------------------------------ anexos

    private fun coverage(r: AnalysisReport): List<Block> {
        val blocks = mutableListOf<Block>(
            Block.Paragraph(
                "El análisis es estático: examina el código sin ejecutarlo ni compilarlo y sin enviarlo a ningún servicio. " +
                    "Lo que depende de la ejecución (valores en tiempo real, configuración externa, reflexión) no se puede confirmar, " +
                    "y por eso cada dato lleva su certeza.",
            ),
            Block.Facts(listOf("Resultado" to Labels.outcome(r.outcome())) + listOfNotNull(r.engineProblem?.let { "Problema del motor" to it })),
        )
        if (r.stages.isNotEmpty()) {
            blocks += Block.Heading("Etapas del análisis")
            blocks += Block.Table(
                listOf("Etapa", "Estado", "Duración"),
                r.stages.map { listOf(Labels.stage(it.stageId), Labels.stageStatus(it.status), "${number(it.durationMs)} ms") },
                tones = r.stages.map { if (it.status == StageStatus.COMPLETED) Tone.NEUTRAL else Tone.WARNING },
            )
        }
        val notAnalyzed = r.notAnalyzedByStatus
        if (notAnalyzed.isNotEmpty()) {
            blocks += Block.Heading("Archivos no analizados")
            blocks += Block.Table(listOf("Motivo", "Archivos"), notAnalyzed.entries.map { listOf(Labels.fileStatus(it.key), number(it.value)) })
        }
        if (r.coverage.isNotEmpty()) {
            blocks += Block.Heading("Notas de cobertura")
            blocks += Block.Table(
                listOf("Etapa", "Tipo", "Detalle"),
                r.coverage.take(60).map { listOf(Labels.stage(it.stageId), Labels.coverage(it.reason), it.detail + (it.subject?.let { s -> " ($s)" } ?: "")) },
                note = more(r.coverage.size, 60, "notas"),
            )
        }
        if (r.excludedDirectories.isNotEmpty()) {
            blocks += Block.Heading("Carpetas excluidas")
            blocks += Block.Table(
                listOf("Carpeta", "Archivos", "Tamaño"),
                r.excludedDirectories.take(20).map { listOf(it.path, number(it.fileCount), Labels.bytes(it.totalBytes)) },
                note = more(r.excludedDirectories.size, 20, "carpetas"),
            )
        }
        return blocks
    }

    private fun metadata(r: AnalysisReport, meta: ReportMeta): List<Block> {
        val facts = mutableListOf(
            "Identificador del análisis" to meta.analysisId,
            "Archivo analizado" to meta.archiveName,
            "Huella SHA-256 del comprimido" to (meta.archiveSha256 ?: "No disponible"),
            "Tamaño" to "${Labels.bytes(meta.archiveSize)} (${number(meta.archiveSize)} bytes)",
        )
        meta.analyzedAtEpochMs?.let { facts += "Fecha del análisis" to Labels.dateTime(it, meta.zone) }
        facts += "Fecha del informe" to Labels.dateTime(meta.generatedAtEpochMs, meta.zone)
        facts += "Versión de la aplicación" to meta.appVersion
        meta.engineVersion?.let { facts += "Versión del motor" to it }
        facts += "Esquema de resultados" to "v$RESULT_SCHEMA_VERSION"
        r.securitySummary?.let { facts += "Reglas de seguridad" to "${number(it.rules)} reglas de Funcode (la versión de cada una está en su ficha)" }
        r.dependencySummary?.let { d ->
            val dbs = d.databases.joinToString { db -> "${db.ecosystem}: ${db.date?.let { Labels.date(it, meta.zone) } ?: "sin descargar"}" }
            facts += "Base de vulnerabilidades (OSV)" to dbs.ifEmpty { "Sin ecosistemas con base" }
            facts += "Datos de fin de soporte (endoflife.date)" to (d.eolDataDate?.let { Labels.date(it, meta.zone) } ?: "Sin descargar")
        }
        facts += "Generado por" to "Funcode Code Analyzer · Funcode Studio CB"
        return listOf(Block.Facts(facts))
    }
}
