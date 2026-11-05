package com.funcodestudiocb.analyzer.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.AnalysisOutcome
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.EncryptionState
import com.funcodestudiocb.analyzer.engine.api.ExclusionReason
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.util.Locale

private val SPANISH = Locale.forLanguageTag("es")

/** Tamaño legible en unidades binarias (KiB, MiB…), con coma decimal. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format(SPANISH, "%.1f %s", value, units[unit])
}

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.CRITICAL -> FuncodeColors.Critical
    Severity.HIGH -> FuncodeColors.High
    Severity.MEDIUM -> FuncodeColors.Medium
    Severity.LOW -> FuncodeColors.Low
    Severity.INFO -> FuncodeColors.Info
}

@Composable
fun severityLabel(severity: Severity): String = stringResource(
    when (severity) {
        Severity.CRITICAL -> R.string.severity_critical
        Severity.HIGH -> R.string.severity_high
        Severity.MEDIUM -> R.string.severity_medium
        Severity.LOW -> R.string.severity_low
        Severity.INFO -> R.string.severity_info
    },
)

@Composable
fun outcomeLabel(outcome: AnalysisOutcome): Pair<String, Color> = when (outcome) {
    AnalysisOutcome.COMPLETED -> stringResource(R.string.outcome_completed) to FuncodeColors.Success
    AnalysisOutcome.PARTIAL -> stringResource(R.string.outcome_partial) to FuncodeColors.Medium
    AnalysisOutcome.REJECTED -> stringResource(R.string.outcome_rejected) to FuncodeColors.Critical
    AnalysisOutcome.PASSWORD_MISSING -> stringResource(R.string.outcome_password) to FuncodeColors.High
    AnalysisOutcome.FAILED -> stringResource(R.string.outcome_failed) to FuncodeColors.Critical
}

@Composable
private fun rejectionLabel(reason: RejectionReason): String = stringResource(
    when (reason) {
        RejectionReason.UNSUPPORTED_FORMAT -> R.string.reject_unsupported
        RejectionReason.MULTI_VOLUME -> R.string.reject_multivolume
        RejectionReason.CORRUPT -> R.string.reject_corrupt
        RejectionReason.TOO_MANY_ENTRIES -> R.string.reject_too_many
        RejectionReason.TOO_LARGE -> R.string.reject_too_large
        RejectionReason.COMPRESSION_BOMB -> R.string.reject_bomb
        RejectionReason.OVERLAPPING_ENTRIES -> R.string.reject_overlap
        RejectionReason.PASSWORD_REQUIRED -> R.string.reject_password
        RejectionReason.WRONG_PASSWORD -> R.string.reject_wrong_password
    },
)

@Composable
internal fun statusLabel(status: FileStatus): String = stringResource(
    when (status) {
        FileStatus.ANALYZED -> R.string.status_file_analyzed
        FileStatus.SKIPPED_TOO_LARGE -> R.string.status_file_too_large
        FileStatus.SKIPPED_LIMIT -> R.string.status_file_limit
        FileStatus.SKIPPED_BOMB -> R.string.status_file_bomb
        FileStatus.ENCRYPTED -> R.string.status_file_encrypted
        FileStatus.CORRUPT -> R.string.status_file_corrupt
        FileStatus.UNSUPPORTED_METHOD -> R.string.status_file_unsupported
    },
)

@Composable
private fun findingLabel(kind: IngestFindingKind): String = stringResource(
    when (kind) {
        IngestFindingKind.DANGEROUS_PATH -> R.string.finding_dangerous_path
        IngestFindingKind.DECEPTIVE_NAME -> R.string.finding_deceptive_name
        IngestFindingKind.SYMLINK -> R.string.finding_symlink
        IngestFindingKind.HARDLINK -> R.string.finding_hardlink
        IngestFindingKind.NESTED_ARCHIVE -> R.string.finding_nested
        IngestFindingKind.ENCRYPTED_ENTRY -> R.string.finding_encrypted
        IngestFindingKind.COMPRESSION_BOMB -> R.string.finding_bomb
        IngestFindingKind.SIZE_MISMATCH -> R.string.finding_size_mismatch
        IngestFindingKind.CRC_MISMATCH -> R.string.finding_crc
        IngestFindingKind.DUPLICATE_PATH -> R.string.finding_duplicate_path
        IngestFindingKind.CASE_COLLISION -> R.string.finding_case_collision
        IngestFindingKind.LOCAL_HEADER_MISMATCH -> R.string.finding_local_header
        IngestFindingKind.LARGE_DICTIONARY -> R.string.finding_large_dictionary
        IngestFindingKind.CORRUPT_ENTRY -> R.string.finding_corrupt
        IngestFindingKind.UNSUPPORTED_METHOD -> R.string.finding_unsupported
        IngestFindingKind.TOTAL_LIMIT_REACHED -> R.string.finding_total_limit
        IngestFindingKind.ENTRY_TOO_LARGE -> R.string.finding_entry_too_large
    },
)

@Composable
private fun encryptionLabel(state: EncryptionState): String = stringResource(
    when (state) {
        EncryptionState.NONE -> R.string.encryption_none
        EncryptionState.PARTIAL -> R.string.encryption_partial
        EncryptionState.ALL_ENTRIES -> R.string.encryption_all
        EncryptionState.HEADERS -> R.string.encryption_headers
    },
)

@Composable
private fun exclusionReasonLabel(reason: ExclusionReason): String = stringResource(
    when (reason) {
        ExclusionReason.DIRECTORY_RULE -> R.string.exclusion_reason_directory
        ExclusionReason.CONTEXT_RULE -> R.string.exclusion_reason_context
        ExclusionReason.TEMPORARY_FILE -> R.string.exclusion_reason_temporary
    },
)

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Informe de la ingesta: lo que contiene el comprimido, lo que se analizó, lo que no y por qué. */
@Composable
fun ReportView(
    archiveName: String,
    report: AnalysisReport,
    outcome: AnalysisOutcome,
    onOpenExplorer: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val (label, color) = outcomeLabel(outcome)
        FuncodePanel(accent = color) {
            StatusChip(label, color, Modifier.testTag("report_outcome"))
            Text(archiveName.revealControlCharacters(), style = MaterialTheme.typography.titleMedium)
            report.archive?.let { a ->
                KeyValue(stringResource(R.string.report_format), a.format.name)
                KeyValue(stringResource(R.string.report_size), formatBytes(a.sizeBytes))
                KeyValue(stringResource(R.string.report_entries), a.entryCount.toString())
                KeyValue(stringResource(R.string.report_encryption), encryptionLabel(a.encryption))
                if (a.solid) KeyValue(stringResource(R.string.report_solid), stringResource(R.string.yes))
            }
            report.engineProblem?.let { Text(it.revealControlCharacters(), color = MaterialTheme.colorScheme.error) }
        }

        report.rejection?.let { r ->
            FuncodePanel(accent = MaterialTheme.colorScheme.error) {
                SectionTitle(stringResource(R.string.report_rejected_title))
                Text(rejectionLabel(r.reason), style = MaterialTheme.typography.titleSmall)
                Text(r.detail.revealControlCharacters(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        report.summary?.let { s ->
            FuncodePanel(accent = FuncodeColors.Electric) {
                SectionTitle(stringResource(R.string.report_totals))
                KeyValue(stringResource(R.string.report_files_analyzed), stringResource(R.string.report_x_of_y, s.analyzedFiles, s.files))
                KeyValue(stringResource(R.string.report_bytes_analyzed), formatBytes(s.analyzedBytes))
                KeyValue(stringResource(R.string.report_files_excluded), "${s.excludedFiles} (${formatBytes(s.excludedBytes)})")
                KeyValue(stringResource(R.string.report_duplicates), pluralStringResource(R.plurals.report_duplicates_value, s.duplicateGroups, s.duplicateGroups, s.duplicateFiles))
                KeyValue(stringResource(R.string.report_findings), s.findings.toString())
            }
        }

        report.inventory?.let { inv ->
            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.report_structure))
                Text(stringResource(R.string.report_structure_value, inv.entryPoints, inv.projectRoots, inv.unclassified), style = MaterialTheme.typography.bodyMedium)
                KeyValue(stringResource(R.string.report_unnecessary), (inv.roleCounts[FileRole.POSSIBLY_UNNECESSARY] ?: 0).toString())
                KeyValue(stringResource(R.string.report_secrets_hidden), inv.redactions.toString())
                KeyValue(
                    stringResource(R.string.report_code_copy),
                    if (inv.copyEnabled) pluralStringResource(R.plurals.report_code_copy_value, inv.storedFiles, inv.storedFiles, formatBytes(inv.storedChars)) else stringResource(R.string.report_code_copy_off),
                )
                if (onOpenExplorer != null) {
                    Button(onClick = onOpenExplorer, modifier = Modifier.testTag("report_open_explorer")) { Text(stringResource(R.string.report_open_explorer)) }
                }
            }
        }

        if (report.hasTechnologies) {
            TechnologiesPanel(report, onOpenFile)
            RelationsReportPanel(report, onOpenFile)
            FrameworkReportPanel(report, onOpenFile)
            com.funcodestudiocb.analyzer.ui.security.SecurityReportPanel(report, onOpenFile)
            LanguagesPanel(report)
        }

        if (report.findings.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.High) {
                SectionTitle(stringResource(R.string.report_findings_title))
                Text(stringResource(R.string.report_findings_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.findings.groupBy { it.kind }
                    .entries.sortedBy { (_, items) -> items.minOf { it.severity.ordinal } }
                    .forEach { (kind, items) ->
                        var expanded by rememberSaveable(kind) { mutableStateOf(false) }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val worst = items.minBy { it.severity.ordinal }.severity
                            StatusChip(severityLabel(worst), severityColor(worst))
                            Spacer(Modifier.size(8.dp))
                            Text(findingLabel(kind), modifier = Modifier.weight(1f))
                            Text("× ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expanded) {
                            items.take(MAX_ITEMS).forEach { f ->
                                Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                                    f.path?.let { Text(it.revealControlCharacters(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                    Text(f.detail.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (items.size > MAX_ITEMS) Text(stringResource(R.string.report_and_more, items.size - MAX_ITEMS), style = MaterialTheme.typography.bodySmall)
                        }
                    }
            }
        }

        val notAnalyzed = report.notAnalyzedByStatus
        if (notAnalyzed.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.Medium) {
                SectionTitle(stringResource(R.string.report_not_analyzed_title))
                notAnalyzed.forEach { (status, count) -> KeyValue(statusLabel(status), count.toString()) }
            }
        }

        if (report.excludedDirectories.isNotEmpty() || report.excludedFiles.isNotEmpty()) {
            FuncodePanel {
                SectionTitle(stringResource(R.string.report_excluded_title))
                Text(stringResource(R.string.report_excluded_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.excludedDirectories.take(MAX_ITEMS).forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${d.path.revealControlCharacters()}/", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("${exclusionReasonLabel(d.reason)} · ${d.rule.revealControlCharacters()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(pluralStringResource(R.plurals.report_files_size, d.fileCount, d.fileCount, formatBytes(d.totalBytes)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (report.excludedFiles.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.report_temporary_files, report.excludedFiles.size, report.excludedFiles.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (report.duplicates.isNotEmpty()) {
            FuncodePanel {
                SectionTitle(stringResource(R.string.report_duplicates_title))
                report.duplicates.take(MAX_ITEMS).forEach { g ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(pluralStringResource(R.plurals.report_duplicate_group, g.count, g.count, formatBytes(g.sizeBytes)), style = MaterialTheme.typography.bodyMedium)
                        g.paths.take(4).forEach { Text("· ${it.revealControlCharacters()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        if (report.coverage.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.report_coverage_title))
                report.coverage.forEach { Text("· ${it.detail.revealControlCharacters()}", style = MaterialTheme.typography.bodySmall) }
            }
        }

        FuncodePanel(accent = FuncodeColors.Gold) {
            Text(stringResource(R.string.report_next_phases), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_ITEMS = 30
