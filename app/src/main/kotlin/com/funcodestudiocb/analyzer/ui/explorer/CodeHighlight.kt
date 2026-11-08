package com.funcodestudiocb.analyzer.ui.explorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters

/** Colores del resaltado de sintaxis sobre el fondo oscuro de Funcode (categorías del motor, 1–7). */
object SyntaxColors {
    val Keyword = Color(0xFF7CC0FF)
    val StringLiteral = Color(0xFF9BE28E)
    val Comment = Color(0xFF8A9BB5)
    val Number = Color(0xFFF5C542)
    val Constant = Color(0xFFD4AF37)
    val Type = Color(0xFF4FD1C5)
    val Function = Color(0xFFFFB86C)

    fun style(category: Int): SpanStyle? = when (category) {
        1 -> SpanStyle(color = Keyword)
        2 -> SpanStyle(color = StringLiteral)
        3 -> SpanStyle(color = Comment, fontStyle = FontStyle.Italic)
        4 -> SpanStyle(color = Number)
        5 -> SpanStyle(color = Constant)
        6 -> SpanStyle(color = Type)
        7 -> SpanStyle(color = Function)
        else -> null
    }
}

/**
 * Reparte el resaltado del texto completo — tríos (inicio, longitud, categoría) — entre sus líneas, con posiciones
 * relativas al inicio de cada línea. Un fragmento que ocupa varias líneas (un comentario de bloque) se parte.
 * [lines] es el texto partido por `\n`.
 */
internal fun splitHighlight(lines: List<String>, spans: IntArray): List<IntArray> {
    if (spans.isEmpty()) return emptyList()
    val starts = IntArray(lines.size)
    var offset = 0
    lines.forEachIndexed { i, line ->
        starts[i] = offset
        offset += line.length + 1
    }
    val perLine = Array(lines.size) { ArrayList<Int>() }
    var line = 0
    for (i in 0 until spans.size / 3) {
        val start = spans[i * 3]
        val end = start + spans[i * 3 + 1]
        val category = spans[i * 3 + 2]
        while (line + 1 < lines.size && starts[line + 1] <= start) line++
        var l = line
        while (l < lines.size && starts[l] < end) {
            val from = maxOf(start, starts[l]) - starts[l]
            val to = minOf(end, starts[l] + lines[l].length) - starts[l]
            if (to > from) {
                perLine[l] += from
                perLine[l] += to - from
                perLine[l] += category
            }
            l++
        }
    }
    return perLine.map { it.toIntArray() }
}

private fun visible(text: String): String = text.replace("\t", "    ").revealControlCharacters()

/**
 * Línea de código lista para mostrar con su resaltado: tabuladores como espacios, caracteres de control y de
 * dirección bidireccional visibles (contra el "Trojan Source") y las líneas muy largas recortadas. Devuelve el
 * texto y, si se recortó, la longitud original.
 */
internal fun highlightedCodeLine(raw: String, spans: IntArray?, maxChars: Int): Pair<AnnotatedString, Int?> {
    val line = raw.removeSuffix("\r")
    val length = minOf(line.length, maxChars)
    val builder = AnnotatedString.Builder()
    var pos = 0
    if (spans != null) {
        for (i in 0 until spans.size / 3) {
            val start = spans[i * 3].coerceAtMost(length)
            val end = (spans[i * 3] + spans[i * 3 + 1]).coerceAtMost(length)
            val style = SyntaxColors.style(spans[i * 3 + 2])
            if (start < pos || end <= start || style == null) continue
            builder.append(visible(line.substring(pos, start)))
            builder.withStyle(style) { append(visible(line.substring(start, end))) }
            pos = end
        }
    }
    builder.append(visible(line.substring(pos, length)))
    return builder.toAnnotatedString() to if (line.length > maxChars) line.length else null
}
