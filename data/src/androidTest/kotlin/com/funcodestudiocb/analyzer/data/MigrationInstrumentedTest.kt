package com.funcodestudiocb.analyzer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** La actualización de la Fase 1 (BD v1) a la Fase 2 (v2) conserva los datos existentes. */
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

    private companion object {
        const val NAME = "migracion-prueba.db"
    }
}
