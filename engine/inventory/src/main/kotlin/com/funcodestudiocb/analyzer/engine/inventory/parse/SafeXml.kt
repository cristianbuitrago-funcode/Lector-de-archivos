package com.funcodestudiocb.analyzer.engine.inventory.parse

/** Atributo de una etiqueta XML con su espacio de nombres ya resuelto. */
data class XmlAttribute(val prefix: String?, val localName: String, val namespace: String?, val value: String)

/** Etiqueta de apertura. [line] empieza en 1. */
class XmlStart(val name: String, val attributes: List<XmlAttribute>, val line: Int) {
    val localName: String get() = name.substringAfter(':')

    /** Valor de un atributo por su nombre local y espacio de nombres (o por el prefijo habitual si no está declarado). */
    fun attribute(localName: String, namespace: String? = null, usualPrefix: String? = null): String? =
        attributes.firstOrNull { a ->
            a.localName == localName && when {
                namespace == null -> a.prefix == null
                a.namespace != null -> a.namespace == namespace
                else -> a.prefix == usualPrefix
            }
        }?.value
}

/**
 * Lector XML mínimo y seguro para archivos no confiables (manifiestos, configuración).
 *
 * Es seguro por construcción: **no procesa DTD** (la declaración `<!DOCTYPE …>` se salta entera), solo
 * resuelve las cinco entidades predefinidas y las referencias numéricas, y nunca abre recursos externos. Así no
 * hay XXE ni "billion laughs". Aplica límites de profundidad, atributos y eventos, y se comporta igual en el PC
 * y en Android (no depende de los analizadores XML de cada plataforma).
 *
 * Solo informa de etiquetas de apertura y cierre: es lo que necesita el inventario. Si el documento está mal
 * formado, se detiene y [parse] devuelve `false`, conservando lo leído hasta ese punto.
 */
class SafeXmlReader(
    private val text: String,
    private val maxDepth: Int = 256,
    private val maxEvents: Int = 500_000,
    private val maxAttributes: Int = 256,
) {
    private var pos = 0
    private val lineStarts: IntArray = computeLineStarts(text)

    /**
     * @param onText texto entre etiquetas (sin espacios alrededor y con las entidades resueltas) y secciones CDATA;
     * solo se calcula si se pide
     */
    fun parse(onStart: (XmlStart) -> Unit, onEnd: (String) -> Unit = {}, onText: ((String) -> Unit)? = null): Boolean {
        val stack = ArrayList<String>()
        val namespaces = ArrayList<Map<String, String>>()
        var events = 0
        while (pos < text.length) {
            val lt = text.indexOf('<', pos)
            if (lt < 0) return stack.isEmpty()
            if (onText != null && lt > pos && stack.isNotEmpty()) {
                val raw = text.substring(pos, lt).trim()
                if (raw.isNotEmpty()) onText(decodeEntities(raw))
            }
            pos = lt
            if (++events > maxEvents) return false
            when {
                text.startsWith("<!--", pos) -> pos = skipPast("-->", pos + 4) ?: return false
                text.startsWith("<![CDATA[", pos) -> {
                    val end = skipPast("]]>", pos + 9) ?: return false
                    if (onText != null) text.substring(pos + 9, end - 3).trim().takeIf { it.isNotEmpty() }?.let(onText)
                    pos = end
                }
                text.startsWith("<?", pos) -> pos = skipPast("?>", pos + 2) ?: return false
                text.startsWith("<!", pos) -> pos = skipDeclaration(pos + 2) ?: return false
                text.startsWith("</", pos) -> {
                    val end = text.indexOf('>', pos + 2)
                    if (end < 0) return false
                    val name = text.substring(pos + 2, end).trim()
                    if (stack.isEmpty() || stack.last() != name) return false
                    stack.removeAt(stack.size - 1)
                    namespaces.removeAt(namespaces.size - 1)
                    onEnd(name)
                    pos = end + 1
                }
                else -> {
                    val line = lineOf(pos)
                    val tag = readStartTag() ?: return false
                    if (stack.size >= maxDepth) return false
                    val declared = HashMap<String, String>()
                    for ((name, value) in tag.rawAttributes) {
                        if (name == "xmlns") declared[""] = value else if (name.startsWith("xmlns:")) declared[name.substring(6)] = value
                    }
                    namespaces += declared
                    val attributes = tag.rawAttributes.filter { (name, _) -> name != "xmlns" && !name.startsWith("xmlns:") }.map { (name, value) ->
                        val prefix = name.substringBefore(':', "").ifEmpty { null }
                        XmlAttribute(prefix, name.substringAfter(':'), prefix?.let { p -> resolve(namespaces, p) }, value)
                    }
                    onStart(XmlStart(tag.name, attributes, line))
                    if (tag.selfClosing) {
                        namespaces.removeAt(namespaces.size - 1)
                        onEnd(tag.name)
                    } else {
                        stack += tag.name
                    }
                }
            }
        }
        return stack.isEmpty()
    }

    private class RawTag(val name: String, val rawAttributes: List<Pair<String, String>>, val selfClosing: Boolean)

    private fun readStartTag(): RawTag? {
        var i = pos + 1
        val nameStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
        if (i == nameStart || i - nameStart > MAX_NAME) return null
        val name = text.substring(nameStart, i)
        val attributes = ArrayList<Pair<String, String>>()
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) return null
            when (text[i]) {
                '>' -> {
                    pos = i + 1
                    return RawTag(name, attributes, selfClosing = false)
                }
                '/' -> {
                    if (i + 1 >= text.length || text[i + 1] != '>') return null
                    pos = i + 2
                    return RawTag(name, attributes, selfClosing = true)
                }
            }
            val attrStart = i
            while (i < text.length && !text[i].isWhitespace() && text[i] != '=' && text[i] != '>' && text[i] != '/') i++
            if (i == attrStart || i - attrStart > MAX_NAME) return null
            val attrName = text.substring(attrStart, i)
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') return null
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || (text[i] != '"' && text[i] != '\'')) return null
            val quote = text[i]
            val valueEnd = text.indexOf(quote, i + 1)
            if (valueEnd < 0 || valueEnd - i > MAX_VALUE) return null
            attributes += attrName to decodeEntities(text.substring(i + 1, valueEnd))
            if (attributes.size > maxAttributes) return null
            i = valueEnd + 1
        }
    }

    /** Salta `<!DOCTYPE …>` u otra declaración, incluido un subconjunto interno entre corchetes, sin interpretarlo. */
    private fun skipDeclaration(from: Int): Int? {
        var i = from
        var brackets = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '[' -> brackets++
                c == ']' -> brackets--
                c == '>' && brackets <= 0 -> return i + 1
            }
            i++
        }
        return null
    }

    private fun skipPast(marker: String, from: Int): Int? {
        val i = text.indexOf(marker, from)
        return if (i < 0) null else i + marker.length
    }

    private fun lineOf(offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    private fun resolve(scopes: List<Map<String, String>>, prefix: String): String? {
        for (i in scopes.indices.reversed()) scopes[i][prefix]?.let { return it }
        return if (prefix == "xml") "http://www.w3.org/XML/1998/namespace" else null
    }

    companion object {
        private const val MAX_NAME = 256
        private const val MAX_VALUE = 65_536

        /** Solo las cinco entidades predefinidas y referencias numéricas; cualquier otra queda tal cual. */
        fun decodeEntities(value: String): String {
            if (!value.contains('&')) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '&') {
                    out.append(c)
                    i++
                    continue
                }
                val semi = value.indexOf(';', i + 1)
                if (semi < 0 || semi - i > 12) {
                    out.append(c)
                    i++
                    continue
                }
                val entity = value.substring(i + 1, semi)
                val decoded: String? = when {
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "amp" -> "&"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (decoded == null) {
                    out.append(value, i, semi + 1)
                } else {
                    out.append(decoded)
                }
                i = semi + 1
            }
            return out.toString()
        }

        private fun codePoint(cp: Int): String? =
            if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

        fun computeLineStarts(text: String): IntArray {
            var count = 1
            for (ch in text) if (ch == '\n') count++
            val starts = IntArray(count)
            var n = 1
            for (i in text.indices) if (text[i] == '\n') starts[n++] = i + 1
            return starts
        }
    }
}
