package com.funcodestudiocb.analyzer.vulndb

import com.funcodestudiocb.analyzer.engine.vulndb.IndexInfo
import com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Estado de un ecosistema en la base descargada. */
data class EcosystemState(val name: String, val sourceDate: Long, val advisories: Int, val packages: Int)

/** Descripción de la base descargada (copia de la cabecera del índice, para mostrarla sin abrirlo entero). */
data class VulnDbStatus(
    val createdAt: Long,
    val ecosystems: List<EcosystemState>,
    val eolDate: Long,
    val eolProducts: Int,
    val sizeBytes: Long,
    val sha256: String,
    /** Productos de endoflife.date que no se pudieron descargar en la última actualización. */
    val eolMissing: List<String> = emptyList(),
) {
    fun ageDays(now: Long): Long = ((now - createdAt) / DAY_MS).coerceAtLeast(0)

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(info: IndexInfo, eolMissing: List<String>) = VulnDbStatus(
            createdAt = info.createdAt,
            ecosystems = info.ecosystems.map { EcosystemState(it.name, it.sourceDate, it.advisories, it.packages) },
            eolDate = info.eolDate,
            eolProducts = info.eolProducts,
            sizeBytes = info.sizeBytes,
            sha256 = info.sha256,
            eolMissing = eolMissing,
        )
    }
}

/** Qué se descarga al actualizar. */
data class VulnDbPreferences(
    val ecosystems: Set<String> = DEFAULT_ECOSYSTEMS,
    val endOfLife: Boolean = true,
) {
    companion object {
        /** Todos menos npm, cuyo volcado (unos 220 MB) se elige expresamente. */
        val DEFAULT_ECOSYSTEMS = OsvEcosystems.SUPPORTED.filter { it != "npm" }.toSet()
    }
}

/**
 * Archivos de la base de vulnerabilidades en el almacenamiento privado de la app (`noBackupFilesDir`, fuera de las
 * copias de seguridad). Solo contienen datos públicos (avisos de OSV y fechas de endoflife.date) y la lista de
 * ecosistemas elegidos: nada del código analizado. Por eso no van en la base de datos cifrada, y así una
 * actualización puede terminar aunque la app se bloquee mientras descarga.
 */
class VulnDbStore(val directory: File) {

    /** Índice que se entrega al proceso aislado. */
    val index: File get() = File(directory, INDEX)
    private val statusFile get() = File(directory, STATUS)
    private val preferencesFile get() = File(directory, PREFERENCES)

    /** Carpeta de trabajo de las actualizaciones (descargas y temporales); se vacía al terminar. */
    val workDirectory: File get() = File(directory, WORK)

    /** La base instalada, o `null` si no hay ninguna (o su descripción no coincide con el índice). */
    fun status(): VulnDbStatus? {
        val file = index
        if (!file.isFile) return null
        val status = runCatching { decodeStatus(Json.parseToJsonElement(statusFile.readText()).jsonObject) }.getOrNull() ?: return null
        return status.takeIf { it.sizeBytes == file.length() }
    }

    /** Índice a entregar al análisis, o `null` si no hay base. */
    fun indexOrNull(): File? = index.takeIf { it.isFile && status() != null }

    fun preferences(): VulnDbPreferences =
        runCatching {
            val o = Json.parseToJsonElement(preferencesFile.readText()).jsonObject
            VulnDbPreferences(
                ecosystems = o["ecosystems"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it in OsvEcosystems.SUPPORTED }.toSet(),
                endOfLife = o["endOfLife"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }.getOrNull() ?: VulnDbPreferences()

    fun savePreferences(preferences: VulnDbPreferences) = writeAtomically(
        preferencesFile,
        buildJsonObject {
            put("ecosystems", buildJsonArray { preferences.ecosystems.sorted().forEach { add(JsonPrimitive(it)) } })
            put("endOfLife", preferences.endOfLife)
        }.toString(),
    )

    /** Registra la descripción de un índice recién instalado en [index]. */
    internal fun saveStatus(status: VulnDbStatus) = writeAtomically(
        statusFile,
        buildJsonObject {
            put("createdAt", status.createdAt)
            put(
                "ecosystems",
                buildJsonArray {
                    status.ecosystems.forEach { e ->
                        add(
                            buildJsonObject {
                                put("name", e.name)
                                put("sourceDate", e.sourceDate)
                                put("advisories", e.advisories)
                                put("packages", e.packages)
                            },
                        )
                    }
                },
            )
            put("eolDate", status.eolDate)
            put("eolProducts", status.eolProducts)
            put("sizeBytes", status.sizeBytes)
            put("sha256", status.sha256)
            put("eolMissing", buildJsonArray { status.eolMissing.forEach { add(JsonPrimitive(it)) } })
        }.toString(),
    )

    private fun decodeStatus(o: JsonObject): VulnDbStatus {
        fun long(k: String) = o[k]!!.jsonPrimitive.longOrNull!!
        return VulnDbStatus(
            createdAt = long("createdAt"),
            ecosystems = (o["ecosystems"] as JsonArray).map { e ->
                val eo = e.jsonObject
                EcosystemState(
                    eo["name"]!!.jsonPrimitive.content,
                    eo["sourceDate"]!!.jsonPrimitive.longOrNull!!,
                    eo["advisories"]!!.jsonPrimitive.intOrNull!!,
                    eo["packages"]!!.jsonPrimitive.intOrNull!!,
                )
            },
            eolDate = long("eolDate"),
            eolProducts = o["eolProducts"]!!.jsonPrimitive.intOrNull!!,
            sizeBytes = long("sizeBytes"),
            sha256 = o["sha256"]!!.jsonPrimitive.content,
            eolMissing = (o["eolMissing"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }

    /** Borra la base (no las preferencias). */
    fun delete() {
        statusFile.delete()
        index.delete()
        workDirectory.deleteRecursively()
    }

    private fun writeAtomically(target: File, text: String) {
        directory.mkdirs()
        val tmp = File(directory, target.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        const val INDEX = "base.fcv"
        private const val STATUS = "estado.json"
        private const val PREFERENCES = "preferencias.json"
        private const val WORK = "trabajo"

        /** A partir de esta edad se avisa de que conviene actualizar. */
        const val STALE_AFTER_DAYS = 7L

        /** Tamaño aproximado de la descarga de cada ecosistema (medido en noviembre de 2026). */
        val APPROX_DOWNLOAD_MB = mapOf(
            "npm" to 220, "PyPI" to 38, "Go" to 13, "Packagist" to 11, "Maven" to 11,
            "RubyGems" to 5, "crates.io" to 4, "NuGet" to 3, "Pub" to 1,
        )
    }
}
