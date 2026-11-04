package com.funcodestudiocb.analyzer.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.api.FileGraph
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.SymbolCategory
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
fun relationKindLabel(kind: RelationKind): String = stringResource(
    when (kind) {
        RelationKind.IMPORT -> R.string.relation_kind_import
        RelationKind.INHERITANCE -> R.string.relation_kind_inheritance
        RelationKind.IMPLEMENTATION -> R.string.relation_kind_implementation
        RelationKind.REFERENCE -> R.string.relation_kind_reference
        RelationKind.CALL -> R.string.relation_kind_call
        RelationKind.ROUTE_HANDLER -> R.string.relation_kind_route
        RelationKind.HTTP -> R.string.relation_kind_http
        RelationKind.DB_ACCESS -> R.string.relation_kind_db
        RelationKind.CONFIG_READ -> R.string.relation_kind_config
        RelationKind.RESOURCE -> R.string.relation_kind_resource
        RelationKind.IPC -> R.string.relation_kind_ipc
        RelationKind.INJECTION -> R.string.relation_kind_injection
    },
)

@Composable
fun relationConfidenceLabel(confidence: RelationConfidence): String = stringResource(
    when (confidence) {
        RelationConfidence.VERIFIED -> R.string.relation_verified
        RelationConfidence.PROBABLE -> R.string.relation_probable
        RelationConfidence.UNVERIFIED -> R.string.relation_unverified
        RelationConfidence.BROKEN -> R.string.relation_broken
    },
)

fun relationConfidenceColor(confidence: RelationConfidence): Color = when (confidence) {
    RelationConfidence.VERIFIED -> FuncodeColors.Success
    RelationConfidence.PROBABLE -> FuncodeColors.Electric
    RelationConfidence.UNVERIFIED -> FuncodeColors.Medium
    RelationConfidence.BROKEN -> FuncodeColors.Critical
}

@Composable
private fun relationConfidenceMeaning(confidence: RelationConfidence): String = stringResource(
    when (confidence) {
        RelationConfidence.VERIFIED -> R.string.relation_verified_meaning
        RelationConfidence.PROBABLE -> R.string.relation_probable_meaning
        RelationConfidence.UNVERIFIED -> R.string.relation_unverified_meaning
        RelationConfidence.BROKEN -> R.string.relation_broken_meaning
    },
)

@Composable
private fun symbolCategoryLabel(category: SymbolCategory): String = stringResource(
    when (category) {
        SymbolCategory.CLASS -> R.string.symbol_class
        SymbolCategory.INTERFACE -> R.string.symbol_interface
        SymbolCategory.ENUM -> R.string.symbol_enum
        SymbolCategory.OBJECT -> R.string.symbol_object
        SymbolCategory.FUNCTION -> R.string.symbol_function
        SymbolCategory.TYPE -> R.string.symbol_type
        SymbolCategory.CONSTANT -> R.string.symbol_constant
    },
)

/** Destino legible de una conexión: archivo, paquete externo o lo que se buscó sin encontrarlo. */
@Composable
fun relationDestination(relation: Relation): String = when {
    relation.target != null -> relation.target!!.revealControlCharacters()
    relation.external != null -> stringResource(R.string.relation_external, relation.external!!.revealControlCharacters())
    else -> stringResource(R.string.relation_missing, relation.reference.revealControlCharacters())
}

@Composable
private fun Title(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

/** Fila de una conexión en la ficha de un archivo. */
@Composable
private fun RelationRow(relation: Relation, other: String, onOpen: (Relation) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(relation) }
            .padding(vertical = 5.dp)
            .testTag("relation_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(other, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = FuncodeColors.ElectricSoft)
            Text(
                relationKindLabel(relation.kind) + (relation.line?.let { " · " + stringResource(R.string.file_evidence_line, it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(relationConfidenceLabel(relation.confidence), relationConfidenceColor(relation.confidence))
    }
}

/** Lugar del archivo en el grafo y sus conexiones de salida y de entrada. */
@Composable
fun RelationsPanel(model: ExplorerModel, path: String, onOpen: (Relation) -> Unit) {
    val graph = model.graph[path]
    val out = model.outgoing[path].orEmpty().sortedWith(compareBy({ it.target == null }, { it.confidence.ordinal }))
    val inc = model.incoming[path].orEmpty()
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("file_relations")) {
        Title(stringResource(R.string.file_relations))
        graph?.let { GraphFacts(it, model) }
        Text(stringResource(R.string.file_relations_uses, out.size), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        if (out.isEmpty()) Text(stringResource(R.string.file_relations_none), style = MaterialTheme.typography.bodySmall)
        out.take(MAX_ROWS).forEach { RelationRow(it, relationDestination(it), onOpen) }
        if (out.size > MAX_ROWS) Text(stringResource(R.string.report_and_more, out.size - MAX_ROWS), style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.file_relations_used_by, inc.size), fontWeight = FontWeight.Medium)
        if (inc.isEmpty()) Text(stringResource(R.string.file_relations_none), style = MaterialTheme.typography.bodySmall)
        inc.take(MAX_ROWS).forEach { RelationRow(it, it.source.revealControlCharacters(), onOpen) }
        if (inc.size > MAX_ROWS) Text(stringResource(R.string.report_and_more, inc.size - MAX_ROWS), style = MaterialTheme.typography.labelSmall)
    }
    graph?.symbols?.takeIf { it.isNotEmpty() }?.let { symbols ->
        FuncodePanel(modifier = Modifier.testTag("file_symbols")) {
            Title(stringResource(R.string.file_symbols))
            symbols.take(MAX_ROWS).forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(s.name.revealControlCharacters(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${symbolCategoryLabel(s.category)} · ${stringResource(R.string.file_evidence_line, s.line)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (symbols.size > MAX_ROWS) Text(stringResource(R.string.report_and_more, symbols.size - MAX_ROWS), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GraphFacts(graph: FileGraph, model: ExplorerModel) {
    val total = model.graph.size
    Text(stringResource(R.string.file_graph_rank, graph.rank, total), style = MaterialTheme.typography.bodySmall)
    Text(
        if (graph.transitiveCapped) pluralStringResource(R.plurals.file_graph_dependents_capped, graph.incoming, graph.incoming, graph.transitiveDependents)
        else pluralStringResource(R.plurals.file_graph_dependents, graph.incoming, graph.incoming, graph.transitiveDependents),
        style = MaterialTheme.typography.bodySmall,
    )
    graph.cycle?.let { c ->
        val size = model.report.cycles.firstOrNull { it.index == c }?.size ?: 0
        Text(stringResource(R.string.file_graph_cycle, size), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
    }
    when (graph.reachableFromEntryPoint) {
        false -> Text(stringResource(R.string.file_graph_not_reachable), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
        else -> Unit
    }
    if (graph.unreferenced) {
        Text(stringResource(R.string.file_graph_unreferenced), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium, modifier = Modifier.testTag("file_unreferenced"))
    }
}

/** Conexión con sus cinco atributos: origen y destino, tipo, información intercambiada, impacto y confianza. */
@Composable
fun RelationDetail(relation: Relation, onClose: () -> Unit, onOpenFile: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("relation_detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.explorer_back)) }
            Text(stringResource(R.string.relation_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusChip(relationConfidenceLabel(relation.confidence), relationConfidenceColor(relation.confidence))
        }

        FuncodePanel(accent = FuncodeColors.Electric) {
            Title(stringResource(R.string.relation_origin_destination))
            Text(stringResource(R.string.relation_origin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                relation.source.revealControlCharacters() + (relation.line?.let { ":$it" } ?: ""),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = FuncodeColors.ElectricSoft,
                modifier = Modifier.clickable { onOpenFile(relation.source) }.testTag("relation_origin"),
            )
            Spacer(Modifier.padding(2.dp))
            Text(stringResource(R.string.relation_destination), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val target = relation.target
            Text(
                relationDestination(relation),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = if (target != null) FuncodeColors.ElectricSoft else if (relation.confidence == RelationConfidence.BROKEN) FuncodeColors.Critical else MaterialTheme.colorScheme.onSurface,
                modifier = (if (target != null) Modifier.clickable { onOpenFile(target) } else Modifier).testTag("relation_destination"),
            )
        }

        FuncodePanel {
            Title(stringResource(R.string.relation_type))
            Text(relationKindLabel(relation.kind), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.relation_written, relation.reference.revealControlCharacters()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        FuncodePanel(modifier = Modifier.testTag("relation_exchanged")) {
            Title(stringResource(R.string.relation_exchanged))
            val text = when {
                relation.exchanged.isNotEmpty() -> relation.exchanged.joinToString(", ") + (relation.exchangedNote?.let { " ($it)" } ?: "")
                relation.exchangedNote != null -> relation.exchangedNote!!.replaceFirstChar { it.uppercase() }
                else -> stringResource(R.string.relation_exchanged_unknown)
            }
            Text(text.revealControlCharacters(), style = MaterialTheme.typography.bodySmall)
        }

        FuncodePanel(accent = FuncodeColors.High, modifier = Modifier.testTag("relation_impact")) {
            Title(stringResource(R.string.relation_impact))
            impactLines(relation).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }

        FuncodePanel(accent = relationConfidenceColor(relation.confidence)) {
            Title(stringResource(R.string.relation_confidence))
            Text(relationConfidenceMeaning(relation.confidence), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.relation_evidence), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            relation.evidence.forEach { e ->
                Text(
                    "• " + e.detail.revealControlCharacters() + (e.line?.let { " (" + stringResource(R.string.file_evidence_line, it) + ")" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

/** El impacto de modificar o eliminar el destino, en frases. */
@Composable
private fun impactLines(relation: Relation): List<String> {
    val impact = relation.impact
    val out = mutableListOf<String>()
    when {
        relation.confidence == RelationConfidence.BROKEN -> {
            out += stringResource(R.string.impact_broken)
            out += pluralStringResource(R.plurals.impact_broken_dependents, impact.transitiveDependents, impact.transitiveDependents)
        }
        relation.external != null -> out += pluralStringResource(R.plurals.impact_external, impact.directDependents, relation.external!!.revealControlCharacters(), impact.directDependents)
        else -> {
            if (impact.undefinedSymbols.isNotEmpty()) out += stringResource(R.string.impact_undefined, impact.undefinedSymbols.joinToString(", "))
            else if (relation.kind == RelationKind.RESOURCE) out += stringResource(R.string.impact_resource)
            else out += stringResource(R.string.impact_source_breaks)
            out += if (impact.transitiveCapped) pluralStringResource(R.plurals.impact_dependents_capped, impact.directDependents, impact.directDependents, impact.transitiveDependents)
            else pluralStringResource(R.plurals.impact_dependents, impact.directDependents, impact.directDependents, impact.transitiveDependents)
        }
    }
    out += when {
        impact.entryPointCount == 0 -> stringResource(R.string.impact_no_entry)
        impact.entryPointCount > impact.entryPoints.size -> (impact.entryPointCount - impact.entryPoints.size).let { more ->
            pluralStringResource(R.plurals.impact_entries_more, more, impact.entryPoints.joinToString(", "), more)
        }
        else -> stringResource(R.string.impact_entries, impact.entryPoints.joinToString(", "))
    }
    return out
}

private const val MAX_ROWS = 50
