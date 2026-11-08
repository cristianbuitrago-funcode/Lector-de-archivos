package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.DebtKind
import com.funcodestudiocb.analyzer.engine.api.DebtMarker
import com.funcodestudiocb.analyzer.engine.api.QualityTool
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.languages.FunctionMeasure
import com.funcodestudiocb.analyzer.engine.languages.LanguageKind
import com.google.re2j.Pattern

/**
 * Recoge, en el mismo recorrido del comprimido, lo que las métricas necesitan del contenido de cada archivo de código:
 * líneas, complejidad de sus funciones (del árbol sintáctico), marcas de deuda en los comentarios y sus líneas para
 * buscar código repetido. También mira los archivos de proyecto que activan herramientas de calidad. El texto ya está
 * redactado y no se conserva.
 */
class MetricsCollector : RedactedTextListener {

    override val timingLabel = "métricas"

    internal class FileData(
        val path: String,
        val languageId: String,
        val languageName: String,
        val codeLines: Int,
        val commentLines: Int,
        val measured: Boolean,
        val functions: List<FunctionMeasure>,
        val topLevelDecisions: Int,
        val syntaxErrors: Int,
        val debt: IntArray,
    )

    internal val files = LinkedHashMap<String, FileData>()
    internal val debtSamples = ArrayList<DebtMarker>()
    internal val projectTools = ArrayList<QualityTool>()
    internal val duplicates = DuplicateFinder()
    internal var generated = 0
        private set
    internal var limitReached = false
        private set

    override fun onText(context: StageContext, file: RedactedText) {
        if (file.info.name in QualityTools.PROJECT_FILES) projectTools += QualityTools.inProjectFile(file.path, file.text)
        val language = file.language ?: return
        if (language.kind != LanguageKind.PROGRAMMING) return
        if (file.state == ContentState.NOT_STORED_GENERATED) {
            generated++
            return
        }
        if (files.size >= MAX_FILES) {
            limitReached = true
            return
        }
        val analysis = file.analysis
        val measured = analysis != null && analysis.complexityMeasured
        val debt = IntArray(DebtKind.entries.size)
        if (analysis != null) scanDebt(file, analysis.comments, debt)
        files[file.path] = FileData(
            path = file.path,
            languageId = language.id,
            languageName = language.name,
            codeLines = analysis?.codeLines ?: 0,
            commentLines = analysis?.commentLines ?: 0,
            measured = measured,
            functions = if (analysis != null && measured) analysis.functions else emptyList(),
            topLevelDecisions = if (analysis != null && measured) analysis.topLevelDecisions else 0,
            syntaxErrors = analysis?.syntaxErrors ?: 0,
            debt = debt,
        )
        if (analysis != null && file.text.length <= MAX_DUPLICATION_CHARS) duplicates.add(file.path, file.text, analysis::inComment)
    }

    /**
     * Marcas TODO, FIXME, HACK y XXX dentro de los comentarios (pares inicio-fin en caracteres). Solo cuentan como se
     * usan para señalar deuda: al principio del comentario o de una de sus líneas (`// TODO revisar`), o seguidas de `:`
     * o `(` (`TODO(ana): …`). Una documentación que solo menciona la palabra no es deuda.
     */
    private fun scanDebt(file: RedactedText, comments: IntArray, counts: IntArray) {
        val text = file.text
        var i = 0
        while (i + 1 < comments.size) {
            val start = comments[i].coerceIn(0, text.length)
            val end = comments[i + 1].coerceIn(start, text.length)
            i += 2
            if (end - start < 3) continue
            val m = DEBT.matcher(text.substring(start, minOf(end, start + MAX_COMMENT_SCAN)))
            while (m.find()) {
                if (!isMarker(text, start + m.start(), start + m.end())) continue
                val kind = DebtKind.valueOf(m.group(1))
                counts[kind.ordinal]++
                if (debtSamples.size < MAX_DEBT_SAMPLES) {
                    val at = start + m.start()
                    val lineEnd = text.indexOf('\n', at).let { if (it < 0 || it > end) end else it }
                    val note = text.substring(at, lineEnd).trim().trimEnd('*', '/', '-', '>', ' ')
                        .filterNot { it.isISOControl() }.take(RecordValidator.MAX_DEBT_TEXT)
                    debtSamples += DebtMarker(kind, file.path, lineOf(file.lineStarts, at), note)
                }
            }
        }
    }

    private fun isMarker(text: String, from: Int, to: Int): Boolean {
        // Entre comillas o acentos graves es un ejemplo en la documentación, no una marca.
        if (from > 0 && text[from - 1] in QUOTES) return false
        if (to < text.length && (text[to] == ':' || text[to] == '(')) return true
        var i = from - 1
        while (i >= 0 && text[i] != '\n') {
            if (!(text[i].isWhitespace() || text[i] in COMMENT_PUNCTUATION)) return false
            i--
        }
        return true
    }

    private fun lineOf(lineStarts: IntArray, offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    private companion object {
        const val MAX_FILES = 60_000
        const val MAX_DEBT_SAMPLES = 400
        const val MAX_COMMENT_SCAN = 20_000
        const val MAX_DUPLICATION_CHARS = 2_000_000
        val DEBT: Pattern = Pattern.compile("""\b(TODO|FIXME|HACK|XXX)\b""")
        const val COMMENT_PUNCTUATION = "/*#-!<;'%"
        const val QUOTES = "`'\"«"
    }
}
