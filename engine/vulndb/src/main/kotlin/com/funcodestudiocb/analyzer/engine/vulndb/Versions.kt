package com.funcodestudiocb.analyzer.engine.vulndb

/** Cómo se ordenan las versiones de cada ecosistema de OSV. */
enum class VersionScheme { SEMVER, PEP440, MAVEN, NUGET, COMPOSER, RUBYGEMS }

/**
 * Comparación de versiones por ecosistema, sin expresiones regulares (recorridos de caracteres acotados). Una versión
 * que no se puede interpretar no se compara (`null`): nunca se inventa si está afectada.
 */
object Versions {
    private const val MAX_LENGTH = 100

    fun schemeOf(osvEcosystem: String): VersionScheme = when (osvEcosystem) {
        "PyPI" -> VersionScheme.PEP440
        "Maven" -> VersionScheme.MAVEN
        "NuGet" -> VersionScheme.NUGET
        "Packagist" -> VersionScheme.COMPOSER
        "RubyGems" -> VersionScheme.RUBYGEMS
        else -> VersionScheme.SEMVER // npm, Go, crates.io, Pub
    }

    /** Orden de [a] respecto a [b], o `null` si alguna no se puede interpretar. */
    fun compare(scheme: VersionScheme, a: String, b: String): Int? {
        if (a.length > MAX_LENGTH || b.length > MAX_LENGTH) return null
        return when (scheme) {
            VersionScheme.SEMVER -> semver(a, ignoreCase = false)?.let { x -> semver(b, ignoreCase = false)?.let { y -> compareSemver(x, y) } }
            VersionScheme.NUGET -> semver(a, ignoreCase = true)?.let { x -> semver(b, ignoreCase = true)?.let { y -> compareSemver(x, y) } }
            VersionScheme.PEP440 -> pep440(a)?.let { x -> pep440(b)?.let { y -> x.compareTo(y) } }
            VersionScheme.MAVEN -> compareTokens(mavenTokens(a), mavenTokens(b), maven = true)
            VersionScheme.COMPOSER -> composer(a)?.let { x -> composer(b)?.let { y -> x.compareTo(y) } }
            VersionScheme.RUBYGEMS -> compareTokens(rubyTokens(a), rubyTokens(b), maven = false)
        }
    }

    /** Versiones iguales según el ecosistema (`v1.2.3` = `1.2.3`, `1.0` = `1.0.0` en Maven). */
    fun same(scheme: VersionScheme, a: String, b: String): Boolean = a == b || compare(scheme, a, b) == 0

    // ------------------------------------------------------------------ SemVer (npm, Go, crates.io, Pub, NuGet)

    class SemVer(val numbers: List<Long>, val pre: List<String>)

    fun semver(raw: String, ignoreCase: Boolean): SemVer? {
        var v = raw.trim().removePrefix("=").removePrefix("v").removePrefix("V")
        v = v.substringBefore('+') // metadatos de compilación (y +incompatible de Go): no cuentan
        if (v.isEmpty()) return null
        val dash = v.indexOf('-')
        val core = if (dash >= 0) v.substring(0, dash) else v
        val pre = if (dash >= 0) v.substring(dash + 1) else ""
        val numbers = core.split('.').map { part -> if (part.isEmpty() || part.length > 18 || part.any { !it.isDigit() }) return null else part.toLong() }
        if (numbers.isEmpty() || numbers.size > 6) return null
        val preParts = if (pre.isEmpty()) emptyList() else pre.split('.').map { if (ignoreCase) it.lowercase() else it }
        if (preParts.any { it.isEmpty() }) return null
        return SemVer(numbers, preParts)
    }

    private fun compareSemver(a: SemVer, b: SemVer): Int {
        val n = maxOf(a.numbers.size, b.numbers.size, 3)
        for (i in 0 until n) {
            val c = (a.numbers.getOrElse(i) { 0 }).compareTo(b.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (a.pre.isEmpty() || b.pre.isEmpty()) return (if (a.pre.isEmpty()) 1 else 0) - (if (b.pre.isEmpty()) 1 else 0)
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x = a.pre[i]
            val y = b.pre[i]
            val xn = x.all { it.isDigit() } && x.length <= 18
            val yn = y.all { it.isDigit() } && y.length <= 18
            val c = when {
                xn && yn -> x.toLong().compareTo(y.toLong())
                xn -> -1
                yn -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    // ------------------------------------------------------------------ PEP 440 (PyPI)

    class Pep440(val epoch: Long, val release: List<Long>, val pre: Pair<Int, Long>?, val post: Long?, val dev: Long?) : Comparable<Pep440> {
        override fun compareTo(other: Pep440): Int {
            epoch.compareTo(other.epoch).let { if (it != 0) return it }
            val n = maxOf(release.size, other.release.size)
            for (i in 0 until n) {
                val c = release.getOrElse(i) { 0 }.compareTo(other.release.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            compareKeys(preKey(), other.preKey()).let { if (it != 0) return it }
            (post ?: -1L).compareTo(other.post ?: -1L).let { if (it != 0) return it }
            return (dev ?: Long.MAX_VALUE).compareTo(other.dev ?: Long.MAX_VALUE)
        }

        /** Sin pre ni post y con dev: antes que cualquier pre; sin pre: después de todas. */
        private fun preKey(): Pair<Int, Long> = when {
            pre != null -> pre
            post == null && dev != null -> -1 to 0L
            else -> Int.MAX_VALUE to 0L
        }

        private fun compareKeys(a: Pair<Int, Long>, b: Pair<Int, Long>): Int = a.first.compareTo(b.first).takeIf { it != 0 } ?: a.second.compareTo(b.second)
    }

    fun pep440(raw: String): Pep440? {
        var s = raw.trim().lowercase().removePrefix("v").substringBefore('+')
        if (s.isEmpty()) return null
        var epoch = 0L
        val bang = s.indexOf('!')
        if (bang > 0) {
            epoch = s.substring(0, bang).toLongOrNull() ?: return null
            s = s.substring(bang + 1)
        }
        var i = 0
        val release = mutableListOf<Long>()
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == start || i - start > 18) return null
            release += s.substring(start, i).toLong()
            if (i < s.length && s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()) i++ else break
        }
        if (release.isEmpty()) return null
        fun skipSep() {
            if (i < s.length && s[i] in ".-_") i++
        }
        fun number(): Long {
            skipSep()
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return if (i == start) 0L else s.substring(start, minOf(i, start + 18)).toLong()
        }
        fun word(vararg options: String): String? {
            val save = i
            skipSep()
            for (o in options) if (s.startsWith(o, i)) {
                i += o.length
                return o
            }
            i = save
            return null
        }
        var pre: Pair<Int, Long>? = null
        word("alpha", "beta", "preview", "pre", "rc", "a", "b", "c")?.let { w ->
            val rank = when (w) {
                "a", "alpha" -> 0
                "b", "beta" -> 1
                else -> 2
            }
            pre = rank to number()
        }
        var post: Long? = null
        if (word("post", "rev", "r") != null) {
            post = number()
        } else if (i < s.length && s[i] == '-' && i + 1 < s.length && s[i + 1].isDigit()) {
            i++
            post = number()
        }
        var dev: Long? = null
        if (word("dev") != null) dev = number()
        if (i != s.length) return null
        return Pep440(epoch, release.dropLastWhile { it == 0L }, pre, post, dev)
    }

    // ------------------------------------------------------------------ Composer (Packagist)

    class ComposerVersion(val numbers: List<Long>, val stability: Int, val stabilityNumber: Long) : Comparable<ComposerVersion> {
        override fun compareTo(other: ComposerVersion): Int {
            for (i in 0 until maxOf(numbers.size, other.numbers.size, 4)) {
                val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return stability.compareTo(other.stability).takeIf { it != 0 } ?: stabilityNumber.compareTo(other.stabilityNumber)
        }
    }

    fun composer(raw: String): ComposerVersion? {
        val s = raw.trim().lowercase().removePrefix("v")
        if (s.isEmpty() || s.startsWith("dev-")) return null
        var i = 0
        val numbers = mutableListOf<Long>()
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == start || i - start > 18) return null
            numbers += s.substring(start, i).toLong()
            if (i < s.length && s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()) i++ else break
        }
        if (numbers.isEmpty()) return null
        var rest = s.substring(i).trimStart('-', '.', '_')
        if (rest.isEmpty()) return ComposerVersion(numbers, 4, 0)
        val stability = when {
            rest.startsWith("dev") -> 0
            rest.startsWith("alpha") || rest.startsWith("a") -> 1
            rest.startsWith("beta") || rest.startsWith("b") -> 2
            rest.startsWith("rc") -> 3
            rest.startsWith("patch") || rest.startsWith("pl") || rest.startsWith("p") -> 5
            else -> return null
        }
        rest = rest.dropWhile { it.isLetter() }.trimStart('.', '-', '_')
        val num = rest.takeWhile { it.isDigit() }.take(18).toLongOrNull() ?: 0L
        return ComposerVersion(numbers, stability, num)
    }

    // ------------------------------------------------------------------ Maven y RubyGems (por piezas)

    /** Pieza de versión: número o calificador. */
    private class Token(val number: Long?, val text: String)

    private fun split(raw: String): List<Token>? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val out = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '.' || c == '-' || c == '_' || c == '+' -> i++
                c.isDigit() -> {
                    val start = i
                    while (i < s.length && s[i].isDigit()) i++
                    val digits = s.substring(start, i).trimStart('0').ifEmpty { "0" }
                    if (digits.length > 18) return null
                    out += Token(digits.toLong(), digits)
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    out += Token(null, s.substring(start, i))
                }
                else -> return null
            }
            if (out.size > 20) return null
        }
        return out.ifEmpty { null }
    }

    private fun mavenTokens(raw: String): List<Token>? = split(raw)?.map { t ->
        // Alias de Maven: a1 = alpha-1, b = beta, m = milestone, cr = rc, ga/final/release = versión.
        if (t.number == null) Token(null, MAVEN_ALIASES[t.text] ?: t.text) else t
    }?.dropLastWhile { it.number == 0L || it.number == null && it.text == "" }

    private fun rubyTokens(raw: String): List<Token>? = split(raw)

    private val MAVEN_ALIASES = mapOf("a" to "alpha", "b" to "beta", "m" to "milestone", "cr" to "rc", "ga" to "", "final" to "", "release" to "")
    private val MAVEN_QUALIFIERS = listOf("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")

    private fun qualifierRank(q: String): Int = MAVEN_QUALIFIERS.indexOf(q).let { if (it < 0) MAVEN_QUALIFIERS.size else it }

    private fun compareTokens(a: List<Token>?, b: List<Token>?, maven: Boolean): Int? {
        if (a == null || b == null) return null
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            val c = when {
                x == null && y == null -> 0
                x == null -> -missing(y!!, maven)
                y == null -> missing(x, maven)
                x.number != null && y.number != null -> x.number.compareTo(y.number)
                x.number != null -> 1 // número > calificador
                y.number != null -> -1
                maven -> qualifierRank(x.text).compareTo(qualifierRank(y.text)).takeIf { it != 0 } ?: x.text.compareTo(y.text)
                else -> x.text.compareTo(y.text)
            }
            if (c != 0) return c
        }
        return 0
    }

    /** Pieza frente a nada: un número > 0 suma; un calificador de prelanzamiento resta. */
    private fun missing(t: Token, maven: Boolean): Int = when {
        t.number != null -> if (t.number == 0L) 0 else 1
        maven -> qualifierRank(t.text).compareTo(qualifierRank(""))
        else -> -1
    }
}

/** Evento de un rango de OSV. */
enum class EventKind { INTRODUCED, FIXED, LAST_AFFECTED, LIMIT }

class RangeEvent(val kind: EventKind, val version: String)

class AffectedRange(val events: List<RangeEvent>)

/**
 * Decide si una versión está afectada según el algoritmo de OSV: los eventos se ordenan y la versión es vulnerable
 * desde cada `introduced` hasta el siguiente `fixed` (exclusivo) o `last_affected` (inclusivo). `null` si no se puede
 * decidir (versión o eventos que no se entienden).
 */
object Affected {
    fun check(scheme: VersionScheme, version: String, ranges: List<AffectedRange>, versions: List<String>): Boolean? {
        if (versions.any { Versions.same(scheme, it, version) }) return true
        var unknown = false
        for (range in ranges) {
            when (inRange(scheme, version, range)) {
                true -> return true
                null -> unknown = true
                false -> {}
            }
        }
        return if (unknown && versions.isEmpty()) null else false
    }

    private fun inRange(scheme: VersionScheme, version: String, range: AffectedRange): Boolean? {
        // "introduced: 0" es el principio de todo.
        val events = range.events.sortedWith { a, b ->
            when {
                a.version == "0" && b.version == "0" -> 0
                a.version == "0" -> -1
                b.version == "0" -> 1
                else -> Versions.compare(scheme, a.version, b.version) ?: 0
            }
        }
        var vulnerable = false
        for (e in events) {
            val cmp = if (e.version == "0") 1 else Versions.compare(scheme, version, e.version) ?: return null
            when (e.kind) {
                EventKind.INTRODUCED -> if (cmp >= 0) vulnerable = true
                EventKind.FIXED, EventKind.LIMIT -> if (cmp >= 0) vulnerable = false
                EventKind.LAST_AFFECTED -> if (cmp > 0) vulnerable = false
            }
        }
        return vulnerable
    }

    /** Versiones que corrigen la vulnerabilidad (los `fixed` de los rangos), sin repetir. */
    fun fixedVersions(ranges: List<AffectedRange>): List<String> = ranges.flatMap { r -> r.events.filter { it.kind == EventKind.FIXED }.map { it.version } }.distinct()
}
