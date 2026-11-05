package com.funcodestudiocb.analyzer.ui.architecture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramEdge
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 4f

/**
 * Dibujo de un diagrama ya dispuesto (por el motor o por [FocusGraph]): nodos coloreados por capa, aristas con el estilo
 * de su certeza (continua verificada, discontinua probable, punteada no verificada), las inversas en naranja y las de un
 * ciclo en amarillo. Se amplía con dos dedos, se desplaza arrastrando, un toque selecciona y un doble toque ajusta.
 */
@Composable
fun DiagramCanvas(
    diagram: Diagram,
    selection: DiagramSelection,
    onSelectNode: (String?) -> Unit,
    onSelectEdge: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 256)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(diagram) { mutableFloatStateOf(1f) }
    var offset by remember(diagram) { mutableStateOf(Offset.Zero) }
    var fitted by remember(diagram) { mutableStateOf(false) }

    fun fit() {
        if (size == IntSize.Zero) return
        val z = min(size.width / (diagram.width * density), size.height / (diagram.height * density)).coerceIn(MIN_ZOOM, 1.4f)
        zoom = z
        offset = Offset((size.width - diagram.width * density * z) / 2f, ((size.height - diagram.height * density * z) / 2f).coerceAtLeast(0f))
        fitted = true
    }
    LaunchedEffect(size, diagram) { if (!fitted && size != IntSize.Zero) fit() }

    /** Elemento bajo el dedo, en coordenadas del diagrama. */
    fun hit(p: Offset) {
        val s = zoom * density
        val u = Offset((p.x - offset.x) / s, (p.y - offset.y) / s)
        diagram.nodes.lastOrNull { u.x in it.x..(it.x + it.width) && u.y in it.y..(it.y + it.height) }?.let {
            onSelectNode(it.id)
            return
        }
        val tolerance = 12f / zoom
        diagram.edges.minByOrNull { distance(u, it) }?.takeIf { distance(u, it) <= tolerance }?.let {
            onSelectEdge(it.from, it.to)
            return
        }
        onSelectNode(null)
    }

    val description = stringResource(R.string.arch_canvas_description, diagram.nodes.size, diagram.edges.size)
    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(diagram) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val nz = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        offset = centroid - (centroid - offset) * (nz / zoom) + pan
                        zoom = nz
                    }
                }
                .pointerInput(diagram) { detectTapGestures(onDoubleTap = { fit() }, onTap = { hit(it) }) }
                .semantics { contentDescription = description }
                .testTag("diagram_canvas"),
        ) {
            val s = zoom * density
            fun x(v: Float) = offset.x + v * s
            fun y(v: Float) = offset.y + v * s
            val focusNode = selection.node
            for (e in diagram.edges) {
                val related = (focusNode != null && (e.from == focusNode || e.to == focusNode)) || selection.edge == (e.from to e.to)
                val dimmed = (focusNode != null || selection.edge != null) && !related
                drawEdge(e, ::x, ::y, related, dimmed, density)
            }
            for (n in diagram.nodes) {
                val rect = Rect(x(n.x), y(n.y), x(n.x + n.width), y(n.y + n.height))
                if (rect.right < 0 || rect.bottom < 0 || rect.left > this.size.width || rect.top > this.size.height) continue
                val color = nodeColor(n)
                val selected = n.id == focusNode
                val radius = CornerRadius(10f * s, 10f * s)
                drawRoundRect(FuncodeColors.Navy, rect.topLeft, rect.size, radius)
                drawRoundRect(color.copy(alpha = 0.20f), rect.topLeft, rect.size, radius)
                drawRoundRect(if (selected) FuncodeColors.Gold else color, rect.topLeft, rect.size, radius, style = Stroke(width = (if (selected) 3f else 1.5f) * density))
                n.severity?.let { sev ->
                    drawCircle(com.funcodestudiocb.analyzer.ui.analysis.severityColor(sev), 5f * density, Offset(rect.right - 9f * density, rect.top + 9f * density))
                }
                if (zoom >= 0.35f) {
                    val pad = 8f * s
                    val maxWidth = (rect.width - 2 * pad).toInt().coerceAtLeast(1)
                    val label = measurer.measure(
                        n.label,
                        TextStyle(color = FuncodeColors.TextPrimary, fontSize = (13f * zoom).coerceIn(6f, 22f).sp, fontWeight = FontWeight.SemiBold),
                        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth),
                    )
                    drawText(label, topLeft = Offset(rect.left + pad, rect.top + pad * 0.8f))
                    val detail = n.detail
                    if (detail != null && zoom >= 0.6f) {
                        val d = measurer.measure(
                            detail,
                            TextStyle(color = FuncodeColors.TextSecondary, fontSize = (11f * zoom).coerceIn(6f, 18f).sp),
                            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth),
                        )
                        drawText(d, topLeft = Offset(rect.left + pad, rect.top + pad * 0.8f + label.size.height))
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            ZoomButton("+", "arch_zoom_in") { zoom = (zoom * 1.4f).coerceAtMost(MAX_ZOOM); offset = Offset(offset.x - size.width * 0.14f, offset.y - size.height * 0.14f) }
            ZoomButton("−", "arch_zoom_out") { zoom = (zoom / 1.4f).coerceAtLeast(MIN_ZOOM); offset = Offset(offset.x + size.width * 0.1f, offset.y + size.height * 0.1f) }
            Surface(shape = RoundedCornerShape(50), color = FuncodeColors.Navy2.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = ::fit, modifier = Modifier.size(40.dp).testTag("arch_zoom_fit")) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.arch_fit), tint = FuncodeColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(text: String, tag: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = FuncodeColors.Navy2.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp).testTag(tag)) {
            Text(text, color = FuncodeColors.TextPrimary, fontSize = 20.sp)
        }
    }
}

private fun DrawScope.drawEdge(e: DiagramEdge, x: (Float) -> Float, y: (Float) -> Float, related: Boolean, dimmed: Boolean, density: Float) {
    val p = e.points
    if (p.size < 4) return
    val path = Path()
    path.moveTo(x(p[0]), y(p[1]))
    for (i in 2 until p.size step 2) path.lineTo(x(p[i]), y(p[i + 1]))
    val base = when {
        related -> FuncodeColors.Gold
        e.inverted -> FuncodeColors.High
        e.cycle -> FuncodeColors.Medium
        else -> FuncodeColors.ElectricSoft
    }
    val color = base.copy(alpha = if (dimmed) 0.18f else if (related) 1f else 0.75f)
    val width = ((1.2f + ln(e.count + 1f) * 0.7f).coerceAtMost(5f) * (if (related) 1.5f else 1f)) * density
    val effect = when (e.confidence) {
        RelationConfidence.VERIFIED -> null
        RelationConfidence.PROBABLE -> PathEffect.dashPathEffect(floatArrayOf(9f * density, 6f * density))
        else -> PathEffect.dashPathEffect(floatArrayOf(2f * density, 5f * density))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // Punta de flecha en el destino.
    val n = p.size
    val x2 = x(p[n - 2])
    val y2 = y(p[n - 1])
    val x1 = x(p[n - 4])
    val y1 = y(p[n - 3])
    val angle = atan2(y2 - y1, x2 - x1)
    val head = 9f * density
    val arrow = Path().apply {
        moveTo(x2, y2)
        lineTo(x2 - head * cos(angle - 0.45f), y2 - head * sin(angle - 0.45f))
        lineTo(x2 - head * cos(angle + 0.45f), y2 - head * sin(angle + 0.45f))
        close()
    }
    drawPath(arrow, color)
}

/** Distancia de un punto a la polilínea de una arista (en unidades del diagrama). */
private fun distance(p: Offset, e: DiagramEdge): Float {
    var best = Float.MAX_VALUE
    val pts = e.points
    for (i in 0 until pts.size - 2 step 2) {
        val ax = pts[i]
        val ay = pts[i + 1]
        val bx = pts[i + 2]
        val by = pts[i + 3]
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0f, 1f)
        best = minOf(best, hypot(p.x - (ax + t * dx), p.y - (ay + t * dy)))
    }
    return best
}

/** Color de un nodo: el de su capa, o el de su tipo si no la tiene. */
fun nodeColor(n: com.funcodestudiocb.analyzer.engine.api.DiagramNode): Color = n.layer?.let { layerColor(it) } ?: when (n.kind) {
    com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind.PROJECT -> FuncodeColors.Gold
    com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind.EXTERNAL_SERVICE -> FuncodeColors.Low
    com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind.PACKAGE -> n.severity?.let { com.funcodestudiocb.analyzer.ui.analysis.severityColor(it) } ?: FuncodeColors.Info
    else -> FuncodeColors.Info
}
