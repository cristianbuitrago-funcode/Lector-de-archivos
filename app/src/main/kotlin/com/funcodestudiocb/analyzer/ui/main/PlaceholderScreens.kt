package com.funcodestudiocb.analyzer.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import kotlinx.coroutines.flow.flowOf

/**
 * Pantalla aún no implementada. Se marca explícitamente como tal: la app no simula funciones que
 * todavía no existen.
 */
@Composable
fun PlaceholderScreen(destination: Destination, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlannedPanel(destination)
    }
}

@Composable
private fun PlannedPanel(destination: Destination) {
    val planned = destination.planned ?: return
    FuncodePanel(accent = FuncodeColors.Gold) {
        StatusChip(
            stringResource(R.string.placeholder_badge, destination.phase),
            FuncodeColors.Gold,
            Modifier.testTag("placeholder_${destination.route}"),
        )
        Text(stringResource(R.string.placeholder_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        stringArrayResource(planned).forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text("•", color = FuncodeColors.Electric)
                Spacer(Modifier.size(8.dp))
                Text(item)
            }
        }
    }
}

/** Panel: en la Fase 1 muestra el estado real de la instalación y lo que llegará en la Fase 10. */
@Composable
fun DashboardScreen(container: AppContainer) {
    val db = container.session.databaseOrNull()
    val diagnosticsFlow = remember(db) { db?.runs()?.countByMode("DIAGNOSTIC") ?: flowOf(0) }
    val diagnostics by diagnosticsFlow.collectAsStateWithLifecycle(initialValue = 0)
    val biometric = remember { container.vault.isBiometricEnabled() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FuncodePanel(accent = FuncodeColors.Electric) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            StatusLine(stringResource(R.string.status_phase), FuncodeColors.Gold, ok = null)
            StatusLine(stringResource(R.string.status_access), FuncodeColors.Success, ok = true)
            StatusLine(stringResource(R.string.status_database), FuncodeColors.Success, ok = true)
            StatusLine(
                stringResource(if (biometric) R.string.status_biometric_on else R.string.status_biometric_off),
                if (biometric) FuncodeColors.Success else FuncodeColors.Info,
                ok = biometric,
            )
            StatusLine(stringResource(R.string.status_diagnostics, diagnostics), FuncodeColors.ElectricSoft, ok = null)
        }
        PlannedPanel(Destination.Dashboard)
    }
}

@Composable
private fun StatusLine(text: String, color: Color, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok == true) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
