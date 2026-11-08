package com.funcodestudiocb.analyzer.engine.metrics

import java.util.BitSet

/**
 * Busca código repetido entre archivos (y dentro de un mismo archivo) en un solo recorrido, sin guardar el texto.
 *
 * Cada línea se normaliza (sin espacios sobrantes) y se descartan las vacías, las de comentario, las triviales (`}`,
 * `else`, `break;`…) y las importaciones. Se comparan ventanas de [WINDOW] líneas normalizadas seguidas mediante una
 * huella de 64 bits: dos ventanas con la misma huella son el mismo código. Las líneas de las dos copias se marcan como
 * repetidas y las ventanas consecutivas que coinciden con el mismo archivo forman un bloque.
 *
 * La tabla de huellas tiene un tamaño máximo ([MAX_WINDOWS]): en un proyecto mayor, el resto de las ventanas se
 * compara con las ya vistas pero no se añade (y se dice).
 */
internal class DuplicateFinder {

    /** Archivos examinados: ruta, líneas contadas y líneas repetidas (por número de línea, desde 1). */
    private class FileLines(val path: String) {
        val counted = BitSet()
        val duplicated = BitSet()
    }

    /** Bloque repetido: líneas de la copia actual y de la anterior. */
    class Block(val lines: Int, val first: String, val second: String)

    private val files = ArrayList<FileLines>()
    private var keys = LongArray(INITIAL_CAPACITY)
    private var values = LongArray(INITIAL_CAPACITY)
    private var size = 0

    /** Se alcanzó el máximo de ventanas: el resto solo se comparó con las ya vistas. */
    var saturated = false
        private set

    private val blocks = ArrayList<Block>()

    /**
     * Examina un archivo. [text] ya está redactado; [inComment] dice si una posición del texto está en un comentario.
     */
    fun add(path: String, text: String, inComment: (Int) -> Boolean) {
        if (files.size >= MAX_FILES) {
            saturated = true
            return
        }
        val fileIndex = files.size
        val file = FileLines(path)
        files += file

        // Líneas normalizadas: (número de línea, huella).
        val lineNumbers = IntArray(minOf(text.length / 2 + 1, MAX_LINES_PER_FILE))
        val hashes = LongArray(lineNumbers.size)
        var n = 0
        var line = 1
        var start = 0
        while (start <= text.length && n < lineNumbers.size && line <= MAX_LINES_PER_FILE) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            val hash = normalizedHash(text, start, end, inComment)
            if (hash != 0L) {
                lineNumbers[n] = line
                hashes[n] = hash
                file.counted.set(line)
                n++
            }
            if (end >= text.length) break
            start = end + 1
            line++
        }
        if (n < WINDOW) return

        // Bloque en curso: coincidencias consecutivas con el mismo archivo.
        var runOther = -1
        var runOtherStart = 0
        var runOtherLast = 0
        var runStart = 0
        var runEnd = 0
        fun closeRun() {
            if (runOther >= 0) {
                blocks += Block(runEnd - runStart + 1, "$path:$runStart", "${files[runOther].path}:$runOtherStart")
                if (blocks.size > MAX_BLOCKS * 4) trimBlocks()
            }
            runOther = -1
        }

        for (i in 0..n - WINDOW) {
            var h = 0L
            for (k in 0 until WINDOW) h = h * PRIME + hashes[i + k]
            if (h == 0L) h = 1L
            val firstLine = lineNumbers[i]
            val lastLine = lineNumbers[i + WINDOW - 1]
            val found = find(h)
            if (found != null) {
                val otherFile = (found ushr 40).toInt()
                val otherStart = ((found ushr 20) and LINE_MASK).toInt()
                val otherEnd = (found and LINE_MASK).toInt()
                // En el mismo archivo, solo cuenta si no se solapa con la propia ventana.
                if (otherFile != fileIndex || otherEnd < firstLine) {
                    file.duplicated.set(firstLine, lastLine + 1)
                    files[otherFile].duplicated.set(otherStart, otherEnd + 1)
                    if (runOther == otherFile && otherStart > runOtherLast) {
                        runEnd = lastLine
                        runOtherLast = otherStart
                    } else {
                        closeRun()
                        runOther = otherFile
                        runOtherStart = otherStart
                        runOtherLast = otherStart
                        runStart = firstLine
                        runEnd = lastLine
                    }
                    continue
                }
            } else {
                insert(h, (fileIndex.toLong() shl 40) or (firstLine.toLong() shl 20) or lastLine.toLong())
            }
            closeRun()
        }
        closeRun()
    }

    /** Líneas contadas y repetidas de un archivo examinado. */
    fun counts(): Map<String, Pair<Int, Int>> = files.associate { f ->
        val dup = f.duplicated.clone() as BitSet
        dup.and(f.counted)
        f.path to (f.counted.cardinality() to dup.cardinality())
    }

    /** Los bloques más largos, agrupando las copias de un mismo original. */
    fun topBlocks(max: Int): List<Pair<Int, List<String>>> {
        val grouped = LinkedHashMap<String, Pair<Int, LinkedHashSet<String>>>()
        for (b in blocks.sortedByDescending { it.lines }) {
            val entry = grouped.getOrPut(b.second) { b.lines to linkedSetOf(b.second) }
            entry.second += b.first
        }
        return grouped.values.sortedByDescending { it.first }.take(max).map { (lines, places) -> lines to places.toList() }
    }

    private fun trimBlocks() {
        val keep = blocks.sortedByDescending { it.lines }.take(MAX_BLOCKS * 2)
        blocks.clear()
        blocks += keep
    }

    // ------------------------------------------------------------------ normalización

    private fun normalizedHash(text: String, start: Int, end: Int, inComment: (Int) -> Boolean): Long {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e - s < MIN_LINE_CHARS) return 0L
        if (inComment(s) && inComment(e - 1)) return 0L
        if (IGNORED_PREFIXES.any { text.startsWith(it, s) }) return 0L
        var letters = 0
        var hash = FNV_OFFSET
        var lastSpace = false
        for (i in s until e) {
            val c = text[i]
            if (c.isWhitespace()) {
                if (lastSpace) continue
                lastSpace = true
                hash = (hash xor ' '.code.toLong()) * FNV_PRIME
                continue
            }
            lastSpace = false
            if (c.isLetterOrDigit()) letters++
            hash = (hash xor c.code.toLong()) * FNV_PRIME
        }
        if (letters < MIN_LETTERS) return 0L
        val trimmed = text.substring(s, e)
        if (trimmed in TRIVIAL) return 0L
        return if (hash == 0L) 1L else hash
    }

    // ------------------------------------------------------------------ tabla de huellas (direccionamiento abierto)

    private fun find(key: Long): Long? {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == 0L) return null
            if (k == key) return values[i]
            i = (i + 1) and mask
        }
    }

    private fun insert(key: Long, value: Long) {
        if (size >= MAX_WINDOWS) {
            saturated = true
            return
        }
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (keys[i] != 0L) {
            if (keys[i] == key) return
            i = (i + 1) and mask
        }
        keys[i] = key
        values[i] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = LongArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != 0L) insert(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Long): Int {
        var h = key
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        return h.toInt()
    }

    companion object {
        const val WINDOW = 6
        const val MAX_WINDOWS = 500_000
        const val MAX_FILES = 60_000
        const val MAX_BLOCKS = 30
        private const val MAX_LINES_PER_FILE = 1_000_000
        private const val INITIAL_CAPACITY = 1 shl 12
        private const val LINE_MASK = (1L shl 20) - 1
        private const val PRIME = 1_000_003L
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
        private const val MIN_LINE_CHARS = 4
        private const val MIN_LETTERS = 3

        /** Importaciones y directivas: se repiten en todos los archivos sin ser código duplicado. */
        private val IGNORED_PREFIXES = listOf(
            "import ", "from ", "using ", "#include", "package ", "use ", "require ", "require_once", "include ", "@import", "export * from",
        )

        /** Líneas que no dicen nada por sí solas. */
        private val TRIVIAL = setOf(
            "} else {", "}else{", "else {", "} catch {", "return;", "break;", "continue;", "default:", "return null;", "return true;",
            "return false;", "pass", "else:", "try:", "finally:", "end", "end;", "});", "}));", "})", "</div>", "<div>",
        )
    }
}
