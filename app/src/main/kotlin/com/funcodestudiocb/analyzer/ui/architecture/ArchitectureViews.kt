package com.funcodestudiocb.analyzer.ui.architecture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.InformationFlow
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.explorer.confidenceColor
import com.funcodestudiocb.analyzer.ui.explorer.confidenceLabel
import com.funcodestudiocb.analyzer.ui.explorer.relationConfidenceColor
import com.funcodestudiocb.analyzer.ui.explorer.relationConfidenceLabel
import com.funcodestudiocb.analyzer.ui.explorer.relationKindLabel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
fun layerLabel(layer: ArchLayer): String = stringResource(
    when (layer) {
        ArchLayer.FRONTEND -> R.string.arch_layer_frontend
        ArchLayer.API -> R.string.arch_layer_api
        ArchLayer.CONTROLLER -> R.string.arch_layer_controller
        ArchLayer.SERVICE -> R.string.arch_layer_service
        ArchLayer.MODEL -> R.string.arch_layer_model
        ArchLayer.DATABASE -> R.string.arch_layer_database
        ArchLayer.SHARED -> R.string.arch_layer_shared
        ArchLayer.CONFIG -> R.string.arch_layer_config
        ArchLayer.TEST -> R.string.arch_layer_test
    },
)

@Composable
fun layerMeaning(layer: ArchLayer): String = stringResource(
    when (layer) {
        ArchLayer.FRONTEND -> R.string.arch_layer_frontend_desc
        ArchLayer.API -> R.string.arch_layer_api_desc
        ArchLayer.CONTROLLER -> R.string.arch_layer_controller_desc
        ArchLayer.SERVICE -> R.string.arch_layer_service_desc
        ArchLayer.MODEL -> R.string.arch_layer_model_desc
        ArchLayer.DATABASE -> R.string.arch_layer_database_desc
        ArchLayer.SHARED -> R.string.arch_layer_shared_desc
        ArchLayer.CONFIG -> R.string.arch_layer_config_desc
        ArchLayer.TEST -> R.string.arch_layer_test_desc
    },
)

/**
 * Modelo y BD llevan colores propios: con el amarillo y el naranja de la paleta se confundían con el dorado del
 * controlador y con los colores que marcan los ciclos y las dependencias inversas.
 */
private val ModelColor = Color(0xFFB388FF)
private val DatabaseColor = Color(0xFFFF6FB5)

fun layerColor(layer: ArchLayer): Color = when (layer) {
    ArchLayer.FRONTEND -> FuncodeColors.Electric
    ArchLayer.API -> FuncodeColors.Low
    ArchLayer.CONTROLLER -> FuncodeColors.Gold
    ArchLayer.SERVICE -> FuncodeColors.Success
    ArchLayer.MODEL -> ModelColor
    ArchLayer.DATABASE -> DatabaseColor
    ArchLayer.SHARED -> FuncodeColors.Info
    ArchLayer.CONFIG -> FuncodeColors.TextSecondary
    ArchLayer.TEST -> FuncodeColors.Info
}

/** Cómo se pasa de un paso del flujo al siguiente, en pocas palabras. */
@Composable
fun flowViaLabel(kind: com.funcodestudiocb.analyzer.engine.api.RelationKind): String = stringResource(
    when (kind) {
        com.funcodestudiocb.analyzer.engine.api.RelationKind.HTTP -> R.string.arch_via_http
        com.funcodestudiocb.analyzer.engine.api.RelationKind.ROUTE_HANDLER -> R.string.arch_via_handler
        com.funcodestudiocb.analyzer.engine.api.RelationKind.DB_ACCESS -> R.string.arch_via_db
        com.funcodestudiocb.analyzer.engine.api.RelationKind.INJECTION -> R.string.arch_via_injection
        com.funcodestudiocb.analyzer.engine.api.RelationKind.CALL -> R.string.arch_via_call
        com.funcodestudiocb.analyzer.engine.api.RelationKind.IMPORT -> R.string.arch_via_import
        else -> R.string.arch_via_uses
    },
)

@Composable
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

@Composable
internal fun LayerDot(layer: ArchLayer?, size: Int = 10) {
    Box(Modifier.size(size.dp).background(layer?.let { layerColor(it) } ?: FuncodeColors.Info, CircleShape))
}

/** Un flujo de información como cadena legible: cada paso con su capa, y cómo se llega al siguiente. */
@Composable
fun FlowCard(flow: InformationFlow, onOpenFile: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    FuncodePanel(accent = if (flow.reachesDatabase) FuncodeColors.Success else FuncodeColors.Electric, modifier = modifier.testTag("flow_card")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${flow.method} ${flow.path}".revealControlCharacters(), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            StatusChip(relationConfidenceLabel(flow.confidence), relationConfidenceColor(flow.confidence))
        }
        Text(flow.framework.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            flow.steps.forEachIndexed { i, step ->
                if (i > 0) {
                    Text(
                        "→" + (step.via?.let { " " + flowViaLabel(it) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = step.confidence?.let { relationConfidenceColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                val path = step.path
                Column(
                    Modifier
                        .border(1.dp, layerColor(step.layer), RoundedCornerShape(8.dp))
                        .background(layerColor(step.layer).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .then(if (path != null && onOpenFile != null) Modifier.clickable { onOpenFile(path) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("flow_step"),
                ) {
                    Text(layerLabel(step.layer), style = MaterialTheme.typography.labelSmall, color = layerColor(step.layer))
                    Text(step.label.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            stringResource(if (flow.reachesDatabase) R.string.arch_flow_complete else R.string.arch_flow_partial),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (flow.alternatives > 0) {
            Text(stringResource(R.string.arch_flow_alternatives, flow.alternatives), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Leyenda: colores de las capas y estilos de las conexiones. */
@Composable
fun DiagramLegend(modifier: Modifier = Modifier) {
    Column(modifier.testTag("arch_legend"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArchLayer.entries.filter { it != ArchLayer.TEST }.forEach { layer ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LayerDot(layer)
                    Text(layerLabel(layer), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LineSample(FuncodeColors.ElectricSoft, null, stringResource(R.string.arch_legend_verified))
            LineSample(FuncodeColors.ElectricSoft, floatArrayOf(9f, 6f), stringResource(R.string.arch_legend_probable))
            LineSample(FuncodeColors.ElectricSoft, floatArrayOf(2f, 5f), stringResource(R.string.arch_legend_unverified))
            LineSample(FuncodeColors.High, null, stringResource(R.string.arch_legend_inverted))
            LineSample(FuncodeColors.Medium, null, stringResource(R.string.arch_legend_cycle))
        }
    }
}

@Composable
private fun LineSample(color: Color, dash: FloatArray?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.width(26.dp).size(width = 26.dp, height = 10.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
                pathEffect = dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density }) },
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Ficha de lo seleccionado en un diagrama. */
@Composable
fun SelectionDetail(
    state: ArchitectureUiState.Loaded,
    diagram: Diagram,
    onOpenFile: (String) -> Unit,
    onFocus: (String) -> Unit,
    onOpenDependencies: () -> Unit,
    onSelectNode: (String) -> Unit,
) {
    val selection = state.selection
    val edge = selection.edge?.let { (a, b) -> diagram.edges.firstOrNull { it.from == a && it.to == b } }
    val node = selection.node?.let { id -> diagram.nodes.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxWidth().testTag("arch_detail"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (edge != null) {
            val from = diagram.nodes.firstOrNull { it.id == edge.from }?.label ?: edge.from
            val to = diagram.nodes.firstOrNull { it.id == edge.to }?.label ?: edge.to
            Text("${from.revealControlCharacters()} → ${to.revealControlCharacters()}", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("arch_edge_title"))
            val kinds = edge.kinds.map { relationKindLabel(it).lowercase() }
            Text(stringResource(R.string.arch_edge_count, edge.count, kinds.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(relationConfidenceLabel(edge.confidence), relationConfidenceColor(edge.confidence))
                if (edge.weakest != edge.confidence) StatusChip(relationConfidenceLabel(edge.weakest), relationConfidenceColor(edge.weakest))
            }
            if (edge.inverted) Text(stringResource(R.string.arch_edge_inverted), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.High)
            if (edge.cycle) Text(stringResource(R.string.arch_edge_cycle), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
            edge.samples.forEach { sample ->
                val path = sample.substringBefore(" → ").substringBeforeLast(':').takeIf { it.isNotBlank() }
                Text(
                    "• " + sample.revealControlCharacters(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = FuncodeColors.ElectricSoft,
                    modifier = Modifier.clickable { path?.let(onOpenFile) }.padding(vertical = 2.dp),
                )
            }
            return@Column
        }
        if (node == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).background(nodeColor(node), CircleShape))
            Text(node.label.revealControlCharacters(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("arch_node_title"))
        }
        node.detail?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val incoming = diagram.edges.filter { it.to == node.id }
        val outgoing = diagram.edges.filter { it.from == node.id }
        Text(stringResource(R.string.arch_node_edges, outgoing.sumOf { it.count }, incoming.sumOf { it.count }), style = MaterialTheme.typography.bodySmall)
        when (node.kind) {
            DiagramNodeKind.LAYER -> {
                val layer = node.layer ?: return@Column
                Text(layerMeaning(layer), style = MaterialTheme.typography.bodySmall)
                FileList(state.report.fileLayers.filter { it.layer == layer }.map { it.path }, state, onOpenFile)
            }
            DiagramNodeKind.MODULE -> {
                val prefix = node.path
                val files = if (prefix == null) emptyList() else state.report.fileLayers.map { it.path }.filter { it.startsWith("$prefix/") }
                FileList(files, state, onOpenFile)
            }
            DiagramNodeKind.FILE -> {
                val path = node.path ?: node.id
                state.layers[path]?.let { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LayerDot(l.layer)
                        Text(layerLabel(l.layer), fontWeight = FontWeight.Medium)
                        StatusChip(confidenceLabel(l.confidence), confidenceColor(l.confidence))
                    }
                    Text(l.reason.revealControlCharacters(), style = MaterialTheme.typography.bodySmall)
                }
                Text(path.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenFile(path) }, modifier = Modifier.testTag("arch_open_file")) { Text(stringResource(R.string.arch_open_explorer)) }
                    if (path != state.focus) OutlinedButton(onClick = { onFocus(path) }, modifier = Modifier.testTag("arch_refocus")) { Text(stringResource(R.string.arch_focus_here)) }
                }
            }
            DiagramNodeKind.PROJECT -> {
                node.path?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                OutlinedButton(onClick = onOpenDependencies) { Text(stringResource(R.string.dep_open)) }
            }
            DiagramNodeKind.PACKAGE -> {
                Text(stringResource(R.string.arch_package_desc), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenDependencies, modifier = Modifier.testTag("arch_open_dependencies")) { Text(stringResource(R.string.dep_open)) }
            }
            DiagramNodeKind.EXTERNAL_SERVICE -> {
                Text(stringResource(R.string.arch_external_desc), style = MaterialTheme.typography.bodySmall)
                incoming.flatMap { it.samples }.forEach { Text("• " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
        if (outgoing.isNotEmpty() || incoming.isNotEmpty()) {
            SectionTitle(stringResource(R.string.arch_connected))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (outgoing.map { it.to } + incoming.map { it.from }).distinct().take(MAX_CONNECTED).forEach { id ->
                    val other = diagram.nodes.firstOrNull { it.id == id } ?: return@forEach
                    Text(
                        other.label.revealControlCharacters(),
                        style = MaterialTheme.typography.labelSmall,
                        color = FuncodeColors.ElectricSoft,
                        modifier = Modifier.clickable { onSelectNode(id) }.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileList(files: List<String>, state: ArchitectureUiState.Loaded, onOpenFile: (String) -> Unit) {
    if (files.isEmpty()) return
    SectionTitle(stringResource(R.string.arch_files, files.size))
    files.take(MAX_FILES).forEach { path ->
        val l = state.layers[path]
        Row(
            Modifier.fillMaxWidth().clickable { onOpenFile(path) }.padding(vertical = 3.dp).testTag("arch_file_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayerDot(l?.layer, 8)
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/').revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.ElectricSoft)
                l?.let { Text(it.reason.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    if (files.size > MAX_FILES) Text(stringResource(R.string.dep_more, files.size - MAX_FILES), style = MaterialTheme.typography.labelSmall)
}

/** Panel del informe: patrón, capas, flujos y dependencias inversas. */
@Composable
fun ArchitectureReportPanel(report: AnalysisReport, onOpenArchitecture: (() -> Unit)?) {
    val s = report.architecture ?: return
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("report_architecture")) {
        SectionTitle(stringResource(R.string.report_architecture))
        s.pattern?.let { Text(stringResource(R.string.arch_pattern, it), fontWeight = FontWeight.Medium) }
        Text(stringResource(R.string.arch_classified, s.classified, s.files), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArchLayer.entries.forEach { l ->
                val n = s.byLayer[l] ?: 0
                if (n > 0) StatusChip("${layerLabel(l)} $n", layerColor(l))
            }
        }
        Text(stringResource(R.string.arch_flows_count, s.flows, s.completeFlows), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("report_architecture_flows"))
        if (s.inverted > 0) Text(stringResource(R.string.arch_inverted_count, s.inverted), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.High)
        report.flows.firstOrNull { it.reachesDatabase }?.let { FlowCard(it, onOpenFile = null) }
        if (onOpenArchitecture != null) {
            Button(onClick = onOpenArchitecture, modifier = Modifier.testTag("report_open_architecture")) { Text(stringResource(R.string.arch_open)) }
        }
    }
}

private const val MAX_FILES = 60
private const val MAX_CONNECTED = 20
