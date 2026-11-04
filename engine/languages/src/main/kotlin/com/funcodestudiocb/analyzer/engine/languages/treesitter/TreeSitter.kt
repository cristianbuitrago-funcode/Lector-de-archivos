package com.funcodestudiocb.analyzer.engine.languages.treesitter

/** Declaraciones JNI de libfuncode_treesitter (native/treesitter/src/main/cpp/funcode_treesitter_jni.c). */
internal object TreeSitterNative {
    @JvmStatic
    external fun nativeGrammars(): Array<String>

    @JvmStatic
    external fun nativeParse(
        grammar: Int,
        source: ByteArray,
        timeoutMs: Int,
        maxItems: Int,
        highlight: Boolean,
        wantedTypes: Array<String>?,
    ): IntArray
}

enum class SyntaxStatus { OK, TIMED_OUT, UNAVAILABLE, OUT_OF_MEMORY }

/**
 * Resultado de analizar un archivo con tree-sitter. Las posiciones son desplazamientos en bytes del texto UTF-8.
 *
 * @property comments pares (inicio, fin)
 * @property tokens tríos (inicio, fin, categoría de [HighlightCategory])
 * @property nodes quíntuplas (índice del tipo pedido, inicio, fin, inicio del nombre, fin del nombre; -1 sin nombre)
 */
class SyntaxResult(
    val status: SyntaxStatus,
    val syntaxErrors: Int,
    /** Listas recortadas al llegar al máximo de elementos: 1 = comentarios, 2 = resaltado, 4 = nodos pedidos. */
    val truncation: Int,
    val comments: IntArray,
    val tokens: IntArray,
    val nodes: IntArray,
) {
    val commentsTruncated: Boolean get() = truncation and 1 != 0
    val tokensTruncated: Boolean get() = truncation and 2 != 0
    val nodesTruncated: Boolean get() = truncation and 4 != 0

    companion object {
        fun failed(status: SyntaxStatus) = SyntaxResult(status, 0, 0, IntArray(0), IntArray(0), IntArray(0))
    }
}

/** Categorías de resaltado que devuelve el puente nativo. */
object HighlightCategory {
    const val KEYWORD = 1
    const val STRING = 2
    const val COMMENT = 3
    const val NUMBER = 4
    const val CONSTANT = 5
    const val TYPE = 6
    const val FUNCTION = 7
    const val MAX = 7
}

/**
 * Acceso a tree-sitter (código nativo). Si la biblioteca no se puede cargar, [available] es `false` y el motor
 * usa el recuento léxico: el análisis continúa y la cobertura lo indica.
 */
object TreeSitter {

    private val grammars: Map<String, Int> by lazy {
        try {
            System.loadLibrary("funcode_treesitter")
            TreeSitterNative.nativeGrammars().withIndex().associate { (i, name) -> name to i }
        } catch (e: UnsatisfiedLinkError) {
            emptyMap()
        } catch (e: SecurityException) {
            emptyMap()
        }
    }

    val available: Boolean get() = grammars.isNotEmpty()

    fun hasGrammar(name: String): Boolean = name in grammars

    fun parse(grammar: String, utf8: ByteArray, timeoutMs: Int, maxItems: Int, highlight: Boolean, wantedTypes: List<String>): SyntaxResult {
        val index = grammars[grammar] ?: return SyntaxResult.failed(SyntaxStatus.UNAVAILABLE)
        val raw = TreeSitterNative.nativeParse(index, utf8, timeoutMs, maxItems, highlight, wantedTypes.toTypedArray().takeIf { it.isNotEmpty() })
        val status = when (raw.getOrElse(0) { 3 }) {
            0 -> SyntaxStatus.OK
            1 -> SyntaxStatus.TIMED_OUT
            2 -> SyntaxStatus.UNAVAILABLE
            else -> SyntaxStatus.OUT_OF_MEMORY
        }
        if (status != SyntaxStatus.OK) return SyntaxResult.failed(status)
        var pos = 3
        fun take(width: Int): IntArray {
            val count = raw[pos++]
            val out = raw.copyOfRange(pos, pos + count * width)
            pos += count * width
            return out
        }
        val comments = take(2)
        val tokens = take(3)
        val nodes = take(5)
        return SyntaxResult(status, raw[1], raw[2], comments, tokens, nodes)
    }
}
