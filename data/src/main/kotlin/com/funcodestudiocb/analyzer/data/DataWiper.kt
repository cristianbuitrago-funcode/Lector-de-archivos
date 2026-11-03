package com.funcodestudiocb.analyzer.data

import android.content.Context
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import java.io.File

/** Borra todos los datos locales de la app (salvo la bóveda, que borra [com.funcodestudiocb.analyzer.data.vault.Vault]). */
class DataWiper(private val context: Context) {

    fun wipeAppData() {
        FuncodeDatabase.databaseFiles(context).forEach { it.delete() }
        listOf(context.cacheDir, context.noBackupFilesDir, File(context.filesDir, "diagnostico")).forEach { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
