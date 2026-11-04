package com.funcodestudiocb.analyzer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Registro de cada ejecución del motor (análisis o diagnóstico).
 * En la Fase 1 solo se registran diagnósticos; los análisis completos llegan en la Fase 2.
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: String,
    /** `DIAGNOSTIC` o `FULL`. */
    val mode: String,
    /** Estado final visto por el proceso principal (`COMPLETED`, `INCOMPLETE`…). */
    val status: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMs: Long,
    @ColumnInfo(name = "finished_at") val finishedAtEpochMs: Long,
    /** Resumen legible, ya validado. */
    val summary: String,
    /** Para diagnósticos: si todas las comprobaciones pasaron. */
    val passed: Boolean,
)

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY started_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT COUNT(*) FROM runs WHERE mode = :mode")
    fun countByMode(mode: String): Flow<Int>
}

/** Un análisis de proyecto (Fase 2+): metadatos y contadores para el historial y el panel. */
@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "archive_name") val archiveName: String,
    @ColumnInfo(name = "archive_size") val archiveSize: Long,
    @ColumnInfo(name = "archive_sha256") val archiveSha256: String,
    /** ZIP, RAR4 o RAR5; `null` si se rechazó antes de identificarlo. */
    val format: String?,
    /** Resultado final (ver `AnalysisOutcome` en la app). */
    val status: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMs: Long,
    @ColumnInfo(name = "finished_at") val finishedAtEpochMs: Long,
    @ColumnInfo(name = "file_count") val fileCount: Int,
    @ColumnInfo(name = "analyzed_count") val analyzedCount: Int,
    @ColumnInfo(name = "excluded_count") val excludedCount: Int,
    @ColumnInfo(name = "finding_count") val findingCount: Int,
    @ColumnInfo(name = "duplicate_groups") val duplicateGroups: Int,
    val message: String?,
)

/** Cada registro validado que produjo el motor, tal cual (JSON del protocolo). */
@Entity(
    tableName = "analysis_records",
    primaryKeys = ["analysis_id", "seq"],
    indices = [Index(value = ["analysis_id", "type"])],
)
data class AnalysisRecordEntity(
    @ColumnInfo(name = "analysis_id") val analysisId: String,
    val seq: Int,
    val type: String,
    val json: String,
)

/**
 * Copia del código de un archivo, con los posibles secretos ya ocultados por el motor (Fase 3), troceada en
 * fragmentos. Se borra con su análisis.
 */
@Entity(tableName = "file_contents", primaryKeys = ["analysis_id", "path", "chunk"])
data class FileContentEntity(
    @ColumnInfo(name = "analysis_id") val analysisId: String,
    val path: String,
    val chunk: Int,
    val text: String,
    /** Resaltado de sintaxis del fragmento (Fase 4): enteros separados por comas, ver [HighlightCodec]. */
    @ColumnInfo(defaultValue = "") val highlight: String = "",
)

/** Fragmento de la copia del código con su resaltado. */
data class ContentRow(val text: String, val highlight: String)

/** Los tríos (inicio, longitud, categoría) del resaltado se guardan como texto compacto. */
object HighlightCodec {
    fun encode(spans: List<Int>): String = spans.joinToString(",")

    /** Devuelve una lista vacía si el texto no es válido (nunca falla al abrir el visor). */
    fun decode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val parts = text.split(',')
        if (parts.size % 3 != 0) return IntArray(0)
        val out = IntArray(parts.size)
        for (i in parts.indices) out[i] = parts[i].toIntOrNull()?.takeIf { it >= 0 } ?: return IntArray(0)
        return out
    }
}

/** Ajustes de la app que se guardan cifrados (p. ej. las exclusiones por defecto). */
@Entity(tableName = "app_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
abstract class AnalysisDao {
    @Insert
    protected abstract suspend fun insertAnalysis(analysis: AnalysisEntity)

    @Insert
    protected abstract suspend fun insertRecords(records: List<AnalysisRecordEntity>)

    /** Un fragmento repetido (motor defectuoso) se ignora en lugar de impedir que se guarde todo el análisis. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertContents(contents: List<FileContentEntity>)

    /**
     * Guarda el análisis, sus registros y la copia del código en una sola transacción. [contents] se recorre
     * por lotes (la copia puede ocupar decenas de MB y no se carga entera en memoria).
     */
    @Transaction
    open suspend fun insert(analysis: AnalysisEntity, records: List<AnalysisRecordEntity>, contents: Sequence<FileContentEntity> = emptySequence()) {
        insertAnalysis(analysis)
        records.chunked(500).forEach { insertRecords(it) }
        contents.chunked(100).forEach { insertContents(it) }
    }

    @Query("SELECT text FROM file_contents WHERE analysis_id = :id AND path = :path ORDER BY chunk")
    abstract suspend fun content(id: String, path: String): List<String>

    @Query("SELECT text, highlight FROM file_contents WHERE analysis_id = :id AND path = :path ORDER BY chunk")
    abstract suspend fun contentRows(id: String, path: String): List<ContentRow>

    @Query("SELECT COUNT(*) FROM file_contents WHERE analysis_id = :id")
    abstract suspend fun contentChunkCount(id: String): Int

    @Query("DELETE FROM file_contents WHERE analysis_id = :id")
    protected abstract suspend fun deleteContents(id: String)

    @Query("SELECT * FROM analyses ORDER BY started_at DESC LIMIT :limit")
    abstract fun recent(limit: Int): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses WHERE id = :id")
    abstract suspend fun get(id: String): AnalysisEntity?

    @Query("SELECT * FROM analysis_records WHERE analysis_id = :id ORDER BY seq")
    abstract suspend fun records(id: String): List<AnalysisRecordEntity>

    @Query("SELECT COUNT(*) FROM analyses")
    abstract fun count(): Flow<Int>

    @Query("DELETE FROM analysis_records WHERE analysis_id = :id")
    protected abstract suspend fun deleteRecords(id: String)

    @Query("DELETE FROM analyses WHERE id = :id")
    protected abstract suspend fun deleteAnalysis(id: String)

    @Transaction
    open suspend fun delete(id: String) {
        deleteContents(id)
        deleteRecords(id)
        deleteAnalysis(id)
    }
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)
}

@Database(
    entities = [RunEntity::class, AnalysisEntity::class, AnalysisRecordEntity::class, SettingEntity::class, FileContentEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class FuncodeDatabase : RoomDatabase() {
    abstract fun runs(): RunDao
    abstract fun analyses(): AnalysisDao
    abstract fun settings(): SettingsDao

    companion object {
        const val FILE_NAME = "funcode.db"

        @Volatile
        private var nativeLoaded = false

        /**
         * Abre la base de datos cifrada con SQLCipher.
         *
         * Se usa la clave en bruto (`x'…'`, 256 bits) en lugar de una contraseña: la clave ya es aleatoria,
         * así que la derivación PBKDF2 de SQLCipher no aporta nada y solo ralentizaría la apertura.
         * [databaseKey] se borra de memoria dentro de este método.
         */
        fun open(context: Context, databaseKey: ByteArray): FuncodeDatabase {
            require(databaseKey.size == 32) { "Se requiere una clave de 256 bits" }
            loadNative()
            val rawKey = rawKeyLiteral(databaseKey)
            databaseKey.fill(0)
            // SupportOpenHelperFactory borra `rawKey` tras usarlo.
            return Room.databaseBuilder(context.applicationContext, FuncodeDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(rawKey))
                .build()
        }

        fun databaseFiles(context: Context): List<java.io.File> {
            val main = context.getDatabasePath(FILE_NAME)
            return listOf("", "-wal", "-shm", "-journal").map { java.io.File(main.path + it) }
        }

        internal fun rawKeyLiteral(key: ByteArray): ByteArray {
            val hex = "0123456789abcdef"
            val out = ByteArray(3 + key.size * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            key.forEachIndexed { i, b ->
                val v = b.toInt() and 0xff
                out[2 + i * 2] = hex[v ushr 4].code.toByte()
                out[3 + i * 2] = hex[v and 0x0f].code.toByte()
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }

        private fun loadNative() {
            if (!nativeLoaded) {
                synchronized(this) {
                    if (!nativeLoaded) {
                        System.loadLibrary("sqlcipher")
                        nativeLoaded = true
                    }
                }
            }
        }
    }
}
