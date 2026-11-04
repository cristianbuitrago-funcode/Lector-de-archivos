package com.funcodestudiocb.analyzer.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.Technology
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.explorer.categoryLabel
import com.funcodestudiocb.analyzer.ui.explorer.confidenceColor
import com.funcodestudiocb.analyzer.ui.explorer.confidenceLabel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.text.NumberFormat
import java.util.Locale

private val NUMBERS: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es"))

private fun Long.grouped(): String = NUMBERS.format(this)

/** Una tecnología que solo se menciona (documentación, comentarios): no prueba que se use. */
internal fun Technology.onlyMentioned(): Boolean = evidence.all { it.kind == EvidenceKind.MENTION }

@Composable
private fun PanelTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

/** Lenguajes del proyecto con sus líneas de código, comentarios y en blanco. */
@Composable
fun LanguagesPanel(report: AnalysisReport) {
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("report_languages")) {
        PanelTitle(stringResource(R.string.report_languages))
        val languages = report.languages
        if (languages.isEmpty()) {
            Text(stringResource(R.string.report_languages_none), style = MaterialTheme.typography.bodySmall)
            return@FuncodePanel
        }
        Text(
            stringResource(
                R.string.report_languages_total,
                languages.sumOf { it.code }.grouped(),
                languages.sumOf { it.comment }.grouped(),
                languages.sumOf { it.blank }.grouped(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val total = languages.sumOf { it.code }.coerceAtLeast(1)
        languages.take(MAX_LANGUAGES).forEach { l ->
            HorizontalDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name.revealControlCharacters(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.report_language_code, l.code.grouped()), style = MaterialTheme.typography.bodyMedium)
                    Text("  ${(l.code * 100 / total)} %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    pluralStringResource(R.plurals.report_language_detail, l.files, l.files, l.comment.grouped(), l.blank.grouped()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    l.syntaxFiles == 0 -> Text(stringResource(R.string.report_language_lexical), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Info)
                    l.lexicalFiles > 0 -> Text(pluralStringResource(R.plurals.report_language_partial, l.lexicalFiles, l.lexicalFiles), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Medium)
                }
            }
        }
    }
}

/**
 * Tecnologías por categoría, con su confianza; al tocar una se ven sus evidencias (archivo y línea). Las que solo se
 * mencionan van aparte, como no concluyentes.
 */
@Composable
fun TechnologiesPanel(report: AnalysisReport, onOpenFile: ((String) -> Unit)?) {
    val (mentions, used) = report.technologies.partition { it.onlyMentioned() }
    FuncodePanel(accent = FuncodeColors.Gold, modifier = Modifier.testTag("report_technologies")) {
        PanelTitle(stringResource(R.string.report_technologies))
        Text(stringResource(R.string.report_technologies_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (report.dependencies.isNotEmpty()) {
            val manifests = report.dependencies.map { it.manifest }.distinct().size
            Text(
                pluralStringResource(R.plurals.report_dependencies, report.dependencies.size, report.dependencies.size, manifests),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (used.isEmpty()) Text(stringResource(R.string.report_technologies_none), style = MaterialTheme.typography.bodySmall)
        used.groupBy { it.category }.entries.sortedBy { it.key.ordinal }.forEach { (category, techs) ->
            Text(
                categoryLabel(category),
                style = MaterialTheme.typography.titleSmall,
                color = FuncodeColors.ElectricSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
            techs.sortedWith(compareBy<Technology>({ it.confidence.ordinal }, { it.name })).forEach { TechnologyRow(it, onOpenFile) }
        }
    }
    if (mentions.isNotEmpty()) {
        FuncodePanel(accent = FuncodeColors.Medium, modifier = Modifier.testTag("report_mentions")) {
            PanelTitle(stringResource(R.string.report_mentions))
            Text(stringResource(R.string.report_mentions_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            mentions.sortedBy { it.name }.forEach { TechnologyRow(it, onOpenFile) }
        }
    }
}

@Composable
private fun TechnologyRow(tech: Technology, onOpenFile: ((String) -> Unit)?) {
    var expanded by rememberSaveable(tech.id) { mutableStateOf(false) }
    HorizontalDivider()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp)
            .testTag("tech_${tech.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(tech.name.revealControlCharacters(), fontWeight = FontWeight.Medium)
            if (tech.versions.isNotEmpty()) {
                Text(
                    stringResource(R.string.tech_versions, tech.versions.joinToString(", ").revealControlCharacters()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (tech.confidence != Confidence.POSSIBLE || !tech.onlyMentioned()) StatusChip(confidenceLabel(tech.confidence), confidenceColor(tech.confidence))
    }
    if (expanded) {
        Column(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tech.evidence.forEach { e ->
                val path = e.path
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (path != null && onOpenFile != null) Modifier.clickable { onOpenFile(path) } else Modifier),
                ) {
                    Text(e.detail.revealControlCharacters(), style = MaterialTheme.typography.bodySmall)
                    if (path != null) {
                        Text(
                            path.revealControlCharacters() + (e.line?.let { ":$it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (onOpenFile != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val more = tech.evidenceCount - tech.evidence.size
            if (more > 0) Text(pluralStringResource(R.plurals.tech_evidence_more, more, more), style = MaterialTheme.typography.labelSmall)
            if (tech.projectRoots.isNotEmpty()) {
                Text(
                    stringResource(R.string.tech_projects, tech.projectRoots.joinToString(", ") { it.ifEmpty { "/" } }.revealControlCharacters()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_LANGUAGES = 15
