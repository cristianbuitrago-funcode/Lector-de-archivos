package com.funcodestudiocb.analyzer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Las actualizaciones de la BD (Fase 1 → 2 → 3) conservan los datos existentes. */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FuncodeDatabase::class.java)

    @Test
    fun migra1a2ConservandoLosDiagnosticos() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO runs (id, mode, status, started_at, finished_at, summary, passed) VALUES ('r1', 'DIAGNOSTIC', 'COMPLETED', 1, 2, 'ok', 1)")
        }
        helper.runMigrationsAndValidate(NAME, 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM runs").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM analyses").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migra2a3ConservandoLosAnalisis() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO analyses (id, archive_name, archive_size, archive_sha256, format, status, started_at, finished_at, " +
                    "file_count, analyzed_count, excluded_count, finding_count, duplicate_groups, message) " +
                    "VALUES ('a1', 'p.zip', 10, 'x', 'ZIP', 'COMPLETED', 1, 2, 3, 3, 0, 0, 0, NULL)",
            )
            db.execSQL("INSERT INTO analysis_records (analysis_id, seq, type, json) VALUES ('a1', 0, 'file', '{}')")
        }
        helper.runMigrationsAndValidate(NAME, 3, true).use { db ->
            db.query("SELECT COUNT(*) FROM analysis_records WHERE analysis_id = 'a1'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.execSQL("INSERT INTO file_contents (analysis_id, path, chunk, text) VALUES ('a1', 'a.kt', 0, 'fun main() {}')")
            db.query("SELECT text FROM file_contents").use { c ->
                c.moveToFirst()
                assertEquals("fun main() {}", c.getString(0))
            }
        }
    }

    @Test
    fun migra3a4ConservandoLaCopiaDelCodigoSinResaltado() {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO analyses (id, archive_name, archive_size, archive_sha256, format, status, started_at, finished_at, " +
                    "file_count, analyzed_count, excluded_count, finding_count, duplicate_groups, message) " +
                    "VALUES ('a1', 'p.zip', 10, 'x', 'ZIP', 'COMPLETED', 1, 2, 3, 3, 0, 0, 0, NULL)",
            )
            db.execSQL("INSERT INTO file_contents (analysis_id, path, chunk, text) VALUES ('a1', 'a.kt', 0, 'fun main() {}')")
        }
        helper.runMigrationsAndValidate(NAME, 4, true).use { db ->
            db.query("SELECT text, highlight FROM file_contents WHERE analysis_id = 'a1'").use { c ->
                c.moveToFirst()
                assertEquals("fun main() {}", c.getString(0))
                assertEquals("", c.getString(1), "Los análisis anteriores quedan sin resaltado")
            }
            db.execSQL("INSERT INTO file_contents (analysis_id, path, chunk, text, highlight) VALUES ('a1', 'b.kt', 0, 'val x = 1', '0,3,1')")
        }
    }

    private companion object {
        const val NAME = "migracion-prueba.db"
    }
}
