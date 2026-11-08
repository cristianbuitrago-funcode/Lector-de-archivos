package com.funcodestudiocb.analyzer.ui.reports

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.engine.report.Labels
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

/** Índice de mantenimiento en el informe de la app (Fase 9), con acceso al informe completo y a la exportación. */
@Composable
fun MaintenanceReportPanel(report: AnalysisReport, onOpenReports: (() -> Unit)?) {
    if (!report.hasMetrics) return
    val index = report.maintenance
    val tone = Labels.maintenanceTone(index?.level)
    FuncodePanel(accent = toneColor(tone), modifier = Modifier.testTag("report_maintenance")) {
        Text(stringResource(R.string.maint_title), style = MaterialTheme.typography.titleSmall)
        val score = index?.score
        if (index == null || score == null) {
            Text(stringResource(R.string.maint_not_evaluated), style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$score/100", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("report_maintenance_score"))
                Spacer(Modifier.width(12.dp))
                Text(Labels.maintenance(index.level!!), style = MaterialTheme.typography.titleMedium, color = toneColor(tone), fontWeight = FontWeight.SemiBold)
            }
            val lowest = index.parts.filter { it.score != null }.sortedBy { it.score }.take(2)
            if (lowest.isNotEmpty()) {
                Text(
                    stringResource(R.string.maint_lowest, lowest.joinToString(" y ") { "${it.name.lowercase()} (${it.score})" }),
                    style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary,
                )
            }
        }
        if (onOpenReports != null) {
            Button(onClick = onOpenReports, modifier = Modifier.testTag("report_open_reports")) { Text(stringResource(R.string.maint_open_reports)) }
        }
    }
}
