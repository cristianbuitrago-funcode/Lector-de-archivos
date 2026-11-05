package com.funcodestudiocb.analyzer.engine.vulndb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Resultado de añadir un volcado de OSV. */
class OsvImport(val ecosystem: String, val records: Int, val advisories: Int, val packages: Int, val invalid: Int, val skipped: Int)

/**
 * Construye el índice a partir de los volcados públicos de OSV (`<ecosistema>/all.zip`) y de los datos de
 * endoflife.date. Lo usa el actualizador de la app, en el proceso principal: el proceso aislado solo lee el resultado.
 *
 * Trabaja en disco para no depender de la memoria del teléfono (el volcado de npm tiene cientos de miles de avisos):
 * cada pareja (paquete, aviso) se escribe codificada en un archivo temporal; luego se ordena por la huella del
 * paquete y se agrupa en bloques. El resultado se escribe en un archivo temporal y se renombra al final: una
 * actualización interrumpida nunca deja una base a medias.
 */
class VulnIndexWriter(private val workDir: File) : Closeable {

    private val json = Json { ignoreUnknownKeys = true }
    private val entriesFile = File(workDir, "entradas.tmp")
    private val entries = DataOutputStream(BufferedOutputStream(FileOutputStream(entriesFile), 1 shl 16))
    private var written = 0L
    private var hashes = LongArray(4096)
    private var offsets = LongArray(4096)
    private var count = 0
    private val ecosystems = mutableListOf<EcosystemInfo>()
    private var eol: List<EolProduct> = emptyList()
    private var eolDate = 0L

    init {
        require(workDir.isDirectory) { "La carpeta de trabajo no existe" }
    }

    /** Añade un aviso; devuelve cuántos paquetes del ecosistema afecta. */
    fun addRecord(osvEcosystem: String, record: OsvRecord, packages: MutableSet<Long>? = null): Int {
        if (record.withdrawn != null) return 0
        var added = 0
        for (affected in record.affected) {
            val pkg = affected.pkg ?: continue
            if (pkg.ecosystem != osvEcosystem && !pkg.ecosystem.startsWith("$osvEcosystem:")) continue
            if (pkg.name.isBlank() || pkg.name.length > 300) continue
            val ranges = affected.ranges.filter { it.type == "SEMVER" || it.type == "ECOSYSTEM" }.mapNotNull { r ->
                val events = r.events.mapNotNull { e ->
                    val (k, v) = e.entries.singleOrNull() ?: return@mapNotNull null
                    val value = (v as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val kind = when (k) {
                        "introduced" -> EventKind.INTRODUCED
                        "fixed" -> EventKind.FIXED
                        "last_affected" -> EventKind.LAST_AFFECTED
                        "limit" -> EventKind.LIMIT
                        else -> return@mapNotNull null
                    }
                    RangeEvent(kind, value)
                }
                events.takeIf { it.isNotEmpty() }?.let { AffectedRange(it) }
            }
            val versions = if (ranges.isEmpty()) affected.versions else emptyList()
            if (ranges.isEmpty() && versions.isEmpty()) continue
            val (severity, score, vector) = OsvSeverityRating.of(record, affected)
            val summary = clean(record.summary ?: record.details?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "").take(VulnIndex.MAX_SUMMARY)
            val advisory = Advisory(
                id = record.id,
                aliases = record.aliases.filter { it.length <= 100 }.take(16),
                summary = summary,
                severity = severity,
                score = score,
                vector = vector,
                published = record.published?.take(10),
                malicious = record.id.startsWith("MAL-"),
                cwes = OsvSeverityRating.cwes(record),
                ranges = ranges,
                versions = versions,
            )
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { VulnIndex.writeAdvisory(it, advisory) }
            val key = OsvEcosystems.key(osvEcosystem, pkg.name)
            append(key, pkg.name, bytes.toByteArray())
            packages?.add(VulnIndex.hash(key))
            added++
        }
        return added
    }

    /** Sin caracteres de control y con los espacios seguidos reducidos a uno. */
    private fun clean(text: String): String = buildString {
        var space = false
        for (c in text) {
            if (c.isWhitespace() || c.isISOControl()) {
                if (!space && isNotEmpty()) append(' ')
                space = true
            } else {
                append(c)
                space = false
            }
        }
    }.trim()

    private fun append(key: String, display: String, advisory: ByteArray) {
        if (count == hashes.size) {
            hashes = hashes.copyOf(count * 2)
            offsets = offsets.copyOf(count * 2)
        }
        hashes[count] = VulnIndex.hash(key)
        offsets[count] = written
        count++
        val record = ByteArrayOutputStream(advisory.size + key.length + 64)
        DataOutputStream(record).use { out ->
            out.writeUTF(key)
            out.writeUTF(display.take(200))
            out.writeInt(advisory.size)
            out.write(advisory)
        }
        record.writeTo(entries)
        written += record.size()
    }

    /**
     * Añade el volcado de un ecosistema. Se comprueba como cualquier comprimido no confiable: número de entradas,
     * tamaño de cada una (se mide al leer, no se cree la cabecera) y tamaño total.
     */
    fun addOsvZip(osvEcosystem: String, zip: File, sourceDate: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): OsvImport {
        require(osvEcosystem in OsvEcosystems.SUPPORTED) { "Ecosistema no admitido" }
        var records = 0
        var advisories = 0
        var invalid = 0
        var skipped = 0
        var total = 0L
        val packages = HashSet<Long>()
        ZipFile(zip).use { file ->
            val list = file.entries().toList()
            if (list.size > MAX_ENTRIES) throw IOException("El volcado tiene demasiadas entradas")
            list.forEachIndexed { i, entry ->
                if (cancelled()) throw java.util.concurrent.CancellationException("Actualización cancelada")
                if (i % 2_000 == 0) onProgress(i, list.size)
                if (entry.isDirectory || !entry.name.endsWith(".json")) {
                    skipped++
                    return@forEachIndexed
                }
                val bytes = file.getInputStream(entry).use { readLimited(it, MAX_ENTRY_BYTES) }
                if (bytes == null) {
                    skipped++
                    return@forEachIndexed
                }
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw IOException("El volcado descomprimido es demasiado grande")
                val record = try {
                    json.decodeFromString(OsvRecord.serializer(), bytes.decodeToString())
                } catch (e: Exception) {
                    invalid++
                    return@forEachIndexed
                }
                records++
                val n = addRecord(osvEcosystem, record, packages)
                if (n > 0) advisories++
            }
            onProgress(list.size, list.size)
        }
        ecosystems.removeAll { it.name == osvEcosystem }
        ecosystems += EcosystemInfo(osvEcosystem, sourceDate, advisories, packages.size)
        return OsvImport(osvEcosystem, records, advisories, packages.size, invalid, skipped)
    }

    /** Lee como mucho [limit] bytes; `null` si hay más (la entrada se descarta). */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun setEol(products: List<EolProduct>, date: Long) {
        eol = products
        eolDate = date
    }

    /** Escribe el índice en [target] (temporal + renombrado atómico) y devuelve su descripción. */
    fun writeTo(target: File, createdAt: Long = System.currentTimeMillis()): IndexInfo {
        entries.close()
        // Orden por huella (y por posición, para que el resultado sea estable).
        val order = (0 until count).sortedWith(compareBy<Int>({ hashes[it] }, { offsets[it] }))
        val blocksFile = File(workDir, "bloques.tmp")
        val directory = ByteArrayOutputStream()
        val dirOut = DataOutputStream(directory)
        var blocksLength = 0L
        var directoryEntries = 0
        RandomAccessFile(entriesFile, "r").use { raf ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(blocksFile), 1 shl 16)).use { blocks ->
                var i = 0
                while (i < order.size) {
                    val h = hashes[order[i]]
                    var j = i
                    // Avisos de esta huella, agrupados por paquete (dos claves distintas pueden compartir huella).
                    val byKey = LinkedHashMap<String, Pair<String, MutableList<ByteArray>>>()
                    while (j < order.size && hashes[order[j]] == h) {
                        raf.seek(offsets[order[j]])
                        val key = raf.readUTF()
                        val display = raf.readUTF()
                        val len = raf.readInt()
                        if (len < 0 || len > VulnIndex.MAX_BLOCK) throw IOException("Entrada temporal dañada")
                        val bytes = ByteArray(len)
                        raf.readFully(bytes)
                        byKey.getOrPut(key) { display to mutableListOf() }.second += bytes
                        j++
                    }
                    for ((key, value) in byKey) {
                        val advisories = value.second.distinctBy { it.contentHashCode() }.take(VulnIndex.MAX_ADVISORIES_PER_PACKAGE)
                        var block = VulnDatabase.encodeBlock(key, value.first, advisories)
                        if (block.size > VulnIndex.MAX_BLOCK) block = VulnDatabase.encodeBlock(key, value.first, advisories.take(advisories.size / 2))
                        dirOut.writeLong(h)
                        dirOut.writeLong(blocksLength) // relativo; se corrige al ensamblar
                        dirOut.writeInt(block.size)
                        blocks.write(block)
                        blocksLength += block.size
                        directoryEntries++
                    }
                    i = j
                }
            }
        }
        entriesFile.delete()
        val headerProbe = ByteArrayOutputStream()
        DataOutputStream(headerProbe).use { VulnIndex.writeHeader(it, createdAt, ecosystems, eol, eolDate, directoryEntries, 0L) }
        val headerLength = headerProbe.size().toLong()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(DigestOutputStream(fos, digest), 1 shl 16))
            VulnIndex.writeHeader(out, createdAt, ecosystems, eol, eolDate, directoryEntries, headerLength + blocksLength)
            BufferedInputStream(FileInputStream(blocksFile)).use { it.copyTo(out) }
            // Directorio con las posiciones absolutas.
            val dir = java.nio.ByteBuffer.wrap(directory.toByteArray())
            while (dir.hasRemaining()) {
                out.writeLong(dir.long)
                out.writeLong(headerLength + dir.long)
                out.writeInt(dir.int)
            }
            out.flush()
            val sha = digest.digest()
            fos.write(sha)
            fos.fd.sync()
        }
        blocksFile.delete()
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileInputStream(target).channel.use { ch -> VulnDatabase.open(ch).use { return it.info } }
    }

    override fun close() {
        runCatching { entries.close() }
        entriesFile.delete()
        File(workDir, "bloques.tmp").delete()
    }

    companion object {
        const val MAX_ENTRIES = 3_000_000
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 12L * 1024 * 1024 * 1024
    }
}

/** Datos de endoflife.date (`https://endoflife.date/api/<producto>.json`). */
object EolData {
    private val json = Json { ignoreUnknownKeys = true }

    /** Productos que se descargan, con su nombre en español cuando cambia. */
    val PRODUCTS = linkedMapOf(
        "nodejs" to "Node.js", "python" to "Python", "php" to "PHP", "ruby" to "Ruby", "go" to "Go", "dotnet" to ".NET",
        "dotnetfx" to ".NET Framework", "eclipse-temurin" to "Java (Eclipse Temurin)", "kotlin" to "Kotlin", "django" to "Django",
        "laravel" to "Laravel", "symfony" to "Symfony", "rails" to "Ruby on Rails", "spring-boot" to "Spring Boot",
        "spring-framework" to "Spring Framework", "angular" to "Angular", "react" to "React", "vue" to "Vue", "nextjs" to "Next.js",
        "nuxt" to "Nuxt", "electron" to "Electron", "express" to "Express", "nestjs" to "NestJS", "react-native" to "React Native",
        "svelte" to "Svelte", "ionic" to "Ionic", "jquery" to "jQuery", "bootstrap" to "Bootstrap", "tailwind-css" to "Tailwind CSS",
        "log4j" to "Log4j", "tomcat" to "Apache Tomcat", "drupal" to "Drupal", "cakephp" to "CakePHP", "magento" to "Magento",
        "strapi" to "Strapi", "numpy" to "NumPy", "unity" to "Unity", "godot" to "Godot", "gradle" to "Gradle",
        "postgresql" to "PostgreSQL", "mysql" to "MySQL", "mariadb" to "MariaDB", "mongodb" to "MongoDB", "redis" to "Redis",
        "elasticsearch" to "Elasticsearch", "nginx" to "nginx", "deno" to "Deno", "bun" to "Bun",
    )

    /** Interpreta la respuesta de la API (una lista de ciclos); ciclos o campos raros se ignoran. */
    fun parse(product: String, label: String, text: String): EolProduct {
        val root = json.parseToJsonElement(text) as? JsonArray ?: throw IOException("Respuesta de endoflife.date no válida")
        fun field(o: JsonObject, k: String): String? = when (val v: JsonElement? = o[k]) {
            is JsonPrimitive -> v.content.takeIf { it.isNotBlank() && it != "null" && it.length <= 60 }
            else -> null
        }
        val cycles = root.take(2_000).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val cycle = field(o, "cycle") ?: return@mapNotNull null
            EolCycle(cycle, field(o, "releaseDate"), field(o, "eol"), field(o, "support"), field(o, "latest"), field(o, "lts"))
        }
        if (cycles.isEmpty()) throw IOException("endoflife.date no devolvió ciclos para $product")
        return EolProduct(product, label, cycles)
    }
}
