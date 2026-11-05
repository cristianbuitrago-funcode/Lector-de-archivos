package com.funcodestudiocb.analyzer.analysis

import com.funcodestudiocb.analyzer.engine.api.ArchiveRejected
import com.funcodestudiocb.analyzer.engine.api.ArchitectureSummary
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.FileLayer
import com.funcodestudiocb.analyzer.engine.api.InformationFlow
import com.funcodestudiocb.analyzer.engine.api.ArchiveSummary
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.EndOfLife
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.DependencyCycle
import com.funcodestudiocb.analyzer.engine.api.DuplicateGroup
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.ExcludedFile
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileGraph
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFinding
import com.funcodestudiocb.analyzer.engine.api.IngestSummary
import com.funcodestudiocb.analyzer.engine.api.InventorySummary
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.PasswordRequired
import com.funcodestudiocb.analyzer.engine.api.ProjectRoot
import com.funcodestudiocb.analyzer.engine.api.Redaction
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.DataEntity
import com.funcodestudiocb.analyzer.engine.api.ConfigKey
import com.funcodestudiocb.analyzer.engine.api.RelationsSummary
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.api.Technology

/** Resultado final de un análisis, tal como se guarda en el historial. */
enum class AnalysisOutcome {
    /** Todo se analizó. */
    COMPLETED,

    /** Terminó, pero hay archivos o etapas sin analizar (ver cobertura). */
    PARTIAL,

    /** El comprimido no se pudo analizar (formato, daño, bomba, multivolumen…). */
    REJECTED,

    /** Faltó la contraseña o no era correcta y el usuario decidió no reintentar. */
    PASSWORD_MISSING,

    /** El motor falló, se detuvo por tiempo o envió datos inválidos. */
    FAILED,
}

/** Vista de un análisis construida exclusivamente a partir de los registros validados del motor. */
data class AnalysisReport(
    val archive: ArchiveSummary?,
    val rejection: ArchiveRejected?,
    val passwordRequired: PasswordRequired?,
    val summary: IngestSummary?,
    val files: List<FileEntry>,
    val excludedDirectories: List<ExcludedDirectory>,
    val excludedFiles: List<ExcludedFile>,
    val duplicates: List<DuplicateGroup>,
    val findings: List<IngestFinding>,
    val coverage: List<CoverageNote>,
    val stages: List<StageEvent>,
    val sessionOutcome: SessionOutcome?,
    /** Mensaje del proceso principal cuando el motor no terminó con normalidad. */
    val engineProblem: String?,
    /** Inventario (Fase 3). Vacío en análisis hechos con versiones anteriores. */
    val profiles: List<FileProfile> = emptyList(),
    val entryPoints: List<EntryPoint> = emptyList(),
    val projectRoots: List<ProjectRoot> = emptyList(),
    val redactions: List<Redaction> = emptyList(),
    val inventory: InventorySummary? = null,
    /** Lenguajes, dependencias declaradas y tecnologías (Fase 4). */
    val languages: List<LanguageStats> = emptyList(),
    val dependencies: List<DeclaredDependency> = emptyList(),
    val technologies: List<Technology> = emptyList(),
    /** Relaciones entre archivos (Fase 5A). */
    val relations: List<Relation> = emptyList(),
    val fileGraphs: List<FileGraph> = emptyList(),
    val cycles: List<DependencyCycle> = emptyList(),
    val relationsSummary: RelationsSummary? = null,
    /** Rutas, tablas y claves de configuración (Fase 5B). */
    val endpoints: List<Endpoint> = emptyList(),
    val dataEntities: List<DataEntity> = emptyList(),
    val configKeys: List<ConfigKey> = emptyList(),
    /** Auditoría de seguridad (Fase 6): fichas de los hallazgos y resumen. */
    val security: List<SecurityFinding> = emptyList(),
    val securitySummary: SecuritySummary? = null,
    /** Dependencias directas e indirectas con su uso, vulnerabilidades y fin de soporte (Fase 7). */
    val resolvedDependencies: List<ResolvedDependency> = emptyList(),
    val endOfLife: List<EndOfLife> = emptyList(),
    val dependencySummary: DependencySummary? = null,
    /** Arquitectura (Fase 8): capa de cada archivo, diagramas ya dispuestos, flujos de información y resumen. */
    val fileLayers: List<FileLayer> = emptyList(),
    val diagrams: List<Diagram> = emptyList(),
    val flows: List<InformationFlow> = emptyList(),
    val architecture: ArchitectureSummary? = null,
) {
    /** El análisis pasó por la etapa de arquitectura. */
    val hasArchitecture: Boolean get() = architecture != null || stages.any { it.stageId == "architecture" }

    /** El análisis pasó por la auditoría de dependencias (aunque no hubiera ninguna). */
    val hasDependencyAudit: Boolean get() = dependencySummary != null || stages.any { it.stageId == "dependency-audit" }

    /** El análisis pasó por la auditoría de seguridad (aunque no encontrara nada). */
    val hasSecurity: Boolean get() = securitySummary != null || stages.any { it.stageId == "security" }

    /** El análisis tiene la información de la Fase 4 (lenguajes y tecnologías). */
    val hasTechnologies: Boolean get() = languages.isNotEmpty() || technologies.isNotEmpty() || stages.any { it.stageId == "technologies" }

    /** Archivos no analizados, por motivo. */
    val notAnalyzedByStatus: Map<FileStatus, Int>
        get() = files.filter { it.status != FileStatus.ANALYZED }.groupingBy { it.status }.eachCount()

    val findingsBySeverity: Map<Severity, Int> get() = findings.groupingBy { it.severity }.eachCount()

    /** Hace falta (o no era correcta) la contraseña para analizar algo. */
    val needsPassword: Boolean
        get() = passwordRequired != null ||
            rejection?.reason == RejectionReason.PASSWORD_REQUIRED ||
            rejection?.reason == RejectionReason.WRONG_PASSWORD

    fun outcome(): AnalysisOutcome = when {
        engineProblem != null -> AnalysisOutcome.FAILED
        rejection?.reason == RejectionReason.PASSWORD_REQUIRED || rejection?.reason == RejectionReason.WRONG_PASSWORD ->
            AnalysisOutcome.PASSWORD_MISSING
        rejection != null -> AnalysisOutcome.REJECTED
        sessionOutcome == SessionOutcome.COMPLETED && files.all { it.status == FileStatus.ANALYZED } -> AnalysisOutcome.COMPLETED
        sessionOutcome == null -> AnalysisOutcome.FAILED
        else -> AnalysisOutcome.PARTIAL
    }

    companion object {
        fun from(records: List<ResultRecord>, sessionOutcome: SessionOutcome?, engineProblem: String?): AnalysisReport =
            AnalysisReport(
                archive = records.filterIsInstance<ArchiveSummary>().firstOrNull(),
                rejection = records.filterIsInstance<ArchiveRejected>().firstOrNull(),
                passwordRequired = records.filterIsInstance<PasswordRequired>().lastOrNull(),
                summary = records.filterIsInstance<IngestSummary>().lastOrNull(),
                files = records.filterIsInstance<FileEntry>(),
                excludedDirectories = records.filterIsInstance<ExcludedDirectory>(),
                excludedFiles = records.filterIsInstance<ExcludedFile>(),
                duplicates = records.filterIsInstance<DuplicateGroup>(),
                findings = records.filterIsInstance<IngestFinding>(),
                coverage = records.filterIsInstance<CoverageNote>(),
                stages = records.filterIsInstance<StageEvent>(),
                sessionOutcome = sessionOutcome,
                engineProblem = engineProblem,
                profiles = records.filterIsInstance<FileProfile>(),
                entryPoints = records.filterIsInstance<EntryPoint>(),
                projectRoots = records.filterIsInstance<ProjectRoot>(),
                redactions = records.filterIsInstance<Redaction>(),
                inventory = records.filterIsInstance<InventorySummary>().lastOrNull(),
                languages = records.filterIsInstance<LanguageStats>(),
                dependencies = records.filterIsInstance<DeclaredDependency>(),
                technologies = records.filterIsInstance<Technology>(),
                relations = records.filterIsInstance<Relation>(),
                fileGraphs = records.filterIsInstance<FileGraph>(),
                cycles = records.filterIsInstance<DependencyCycle>(),
                relationsSummary = records.filterIsInstance<RelationsSummary>().lastOrNull(),
                endpoints = records.filterIsInstance<Endpoint>(),
                dataEntities = records.filterIsInstance<DataEntity>(),
                configKeys = records.filterIsInstance<ConfigKey>(),
                security = records.filterIsInstance<SecurityFinding>(),
                securitySummary = records.filterIsInstance<SecuritySummary>().lastOrNull(),
                resolvedDependencies = records.filterIsInstance<ResolvedDependency>(),
                endOfLife = records.filterIsInstance<EndOfLife>(),
                dependencySummary = records.filterIsInstance<DependencySummary>().lastOrNull(),
                fileLayers = records.filterIsInstance<FileLayer>(),
                diagrams = records.filterIsInstance<Diagram>(),
                flows = records.filterIsInstance<InformationFlow>(),
                architecture = records.filterIsInstance<ArchitectureSummary>().lastOrNull(),
            )

        /** Etapas que no terminaron bien (para la cobertura). */
        fun failedStages(report: AnalysisReport) = report.stages.filter { it.status != StageStatus.COMPLETED }
    }
}
