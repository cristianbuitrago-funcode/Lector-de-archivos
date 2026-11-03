package com.funcodestudiocb.analyzer.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Database(entities = [RunEntity::class], version = 1, exportSchema = true)
abstract class FuncodeDatabase : RoomDatabase() {
    abstract fun runs(): RunDao

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
