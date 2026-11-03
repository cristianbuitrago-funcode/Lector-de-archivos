package com.funcodestudiocb.analyzer.analysis

import com.funcodestudiocb.analyzer.engine.api.ArchiveRejected
import com.funcodestudiocb.analyzer.engine.api.ArchiveSummary
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.DuplicateGroup
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.ExcludedFile
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFinding
import com.funcodestudiocb.analyzer.engine.api.IngestSummary
import com.funcodestudiocb.analyzer.engine.api.PasswordRequired
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus

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
) {
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
            )

        /** Etapas que no terminaron bien (para la cobertura). */
        fun failedStages(report: AnalysisReport) = report.stages.filter { it.status != StageStatus.COMPLETED }
    }
}
