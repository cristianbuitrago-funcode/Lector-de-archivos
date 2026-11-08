package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.data.vault.UnlockResult
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Rendimiento (Fase 10, RNF-07): un proyecto de 5 000 archivos de código generado en el propio dispositivo se analiza
 * en el proceso aislado y se guarda cifrado. Mide los tiempos y los deja en la salida adicional de las pruebas
 * (la CI la adjunta). El objetivo del requisito es para un teléfono de gama media; en el emulador es una referencia.
 */
@RunWith(AndroidJUnit4::class)
class LargeProjectInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as FuncodeApp).container
    private val coordinator get() = container.coordinator

    @Before
    fun setUp() {
        container.vault.wipe()
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
    }

    @After
    fun tearDown() {
        coordinator.reset()
        container.vault.wipe()
        File(context.cacheDir, ZIP).delete()
    }

    @Test
    fun proyectoDe5000ArchivosEnElDispositivo() {
        val zip = File(context.cacheDir, ZIP)
        val generatedAt = System.nanoTime()
        val bytes = LargeProject.write(zip)
        val generation = (System.nanoTime() - generatedAt) / 1_000_000

        val started = System.nanoTime()
        coordinator.select(Uri.fromFile(zip))
        runBlocking { withTimeoutOrNull(120_000) { coordinator.state.first { it is AnalysisState.Ready } } } ?: fail("No se importó: ${coordinator.state.value}")
        val imported = (System.nanoTime() - started) / 1_000_000
        coordinator.start(ExclusionOptions())
        val finished = runBlocking {
            withTimeoutOrNull(LIMIT_MS) { coordinator.state.first { it is AnalysisState.Finished || it is AnalysisState.Failed } }
        } as? AnalysisState.Finished ?: fail("El análisis no terminó a tiempo: ${coordinator.state.value}")
        val analyzed = (System.nanoTime() - started) / 1_000_000
        val relocks = awaitSaved()
        val saved = (System.nanoTime() - started) / 1_000_000

        val report = finished.report
        val stages = report.stages.joinToString("\n") { "  %-20s %8d ms  %s".format(it.stageId, it.durationMs, it.status) }
        val summary = """
            Proyecto de ${LargeProject.FILES} archivos de código (${bytes / 1024} KB sin comprimir, ${zip.length() / 1024} KB en ZIP)
            Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} núcleos, memoria máxima de la app ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB
            Generación del ZIP: $generation ms
            Importación: $imported ms
            Análisis (desde la importación): $analyzed ms
            Guardado en el historial cifrado: ${saved - analyzed} ms
            Total: $saved ms
            Bloqueos automáticos de la app durante la prueba (en segundo plano): $relocks
            Resultado: ${finished.outcome}; archivos analizados: ${report.files.count { it.status == FileStatus.ANALYZED }}; funciones medidas: ${report.metrics?.functions}
            Etapas en el proceso aislado:
        """.trimIndent() + "\n" + stages
        Log.i("FuncodeRendimiento", summary)
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { dir ->
            runCatching { File(dir, "rendimiento-5000-archivos.txt").writeText(summary + "\n") }
        }

        assertEquals(AnalysisOutcome.COMPLETED, finished.outcome, summary)
        assertTrue(report.files.count { it.status == FileStatus.ANALYZED } >= LargeProject.FILES, summary)
        assertTrue(report.stages.all { it.status == StageStatus.COMPLETED }, summary)
        assertTrue((report.metrics?.functions ?: 0) >= LargeProject.FILES, "Cada archivo tiene funciones medidas: $summary")
        // El Panel y el informe se construyen con un análisis grande.
        val digest = runBlocking { container.repository.digests().first() }.single()
        assertTrue(digest.maintenance?.score != null && digest.security != null, "Resumen del Panel incompleto")
        assertTrue(runBlocking { container.reports.document(finished.analysisId) }!!.sections.size >= 15)
    }

    /**
     * Espera a que el resultado se guarde. Sin pantalla, la app está en segundo plano y se bloquea sola (por defecto,
     * al minuto), como en un teléfono: el resultado espera en memoria a que se desbloquee, porque bloqueada no puede
     * escribir en la base de datos cifrada. Aquí se desbloquea como lo haría el usuario al volver. Devuelve cuántas veces.
     */
    private fun awaitSaved(): Int {
        var relocks = 0
        val done = runBlocking {
            withTimeoutOrNull(LIMIT_MS) {
                while ((coordinator.state.value as? AnalysisState.Finished)?.saved != true) {
                    if (container.session.state.value == SessionState.Locked) {
                        relocks++
                        val key = (container.vault.unlockWithPassword("Funcode-2026".toCharArray()) as UnlockResult.Success).databaseKey
                        container.session.unlock(key)
                    }
                    delay(200)
                }
                true
            }
        }
        if (done != true) fail("No se guardó en el historial: ${coordinator.state.value}")
        return relocks
    }

    private companion object {
        const val ZIP = "proyecto-grande.zip"
        const val LIMIT_MS = 15L * 60 * 1000
    }
}

/** Proyecto sintético pero realista: varios lenguajes, importaciones entre archivos, funciones con ramas y manifiestos. */
internal object LargeProject {

    const val FILES = 5_000

    /** Escribe el ZIP y devuelve los bytes sin comprimir. */
    fun write(target: File): Long {
        var total = 0L
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            fun put(name: String, text: String) {
                val data = text.toByteArray()
                total += data.size
                out.putNextEntry(ZipEntry("grande/$name"))
                out.write(data)
                out.closeEntry()
            }
            put("package.json", """{"name": "grande", "dependencies": {"express": "^4.19.0", "react": "^18.2.0", "axios": "^1.6.0"}}""")
            put("requirements.txt", "flask==3.0.0\nrequests==2.31.0\nsqlalchemy==2.0.25\n")
            put("android/build.gradle.kts", "plugins { id(\"com.android.application\") }\ndependencies { implementation(\"androidx.core:core-ktx:1.13.0\") }\n")
            put("README.md", "# Proyecto grande\n\nGenerado para medir el rendimiento.\n")
            for (i in 0 until FILES) {
                when (i % 4) {
                    0 -> put("web/src/m${i / 100}/componente$i.js", js(i))
                    1 -> put("web/src/t${i / 100}/servicio$i.ts", ts(i))
                    2 -> put("api/p${i / 100}/modulo$i.py", py(i))
                    else -> put("android/src/main/java/com/grande/k${i / 100}/Clase$i.kt", kt(i))
                }
            }
        }
        return total
    }

    private fun js(i: Int) = buildString {
        appendLine("import React, { useState } from 'react';")
        appendLine("import { servicio${i + 1} } from '../t${(i + 1) / 100}/servicio${i + 1}';")
        appendLine("// Componente $i: lista con filtros")
        appendLine("export default function Componente$i({ items$i, filtro$i }) {")
        appendLine("  const [estado$i, setEstado$i] = useState(null);")
        repeat(12) { k ->
            appendLine("  function paso${i}_$k(valor$k) {")
            appendLine("    if (valor$k > $k && filtro$i) { return valor$k * ${k + i}; }")
            appendLine("    else if (valor$k === ${i % 7}) { setEstado$i('v$k'); }")
            appendLine("    for (const x$k of items$i || []) { if (x$k.id === $k || x$k.n > $i) return x$k; }")
            appendLine("    return valor$k ?? $k;")
            appendLine("  }")
        }
        appendLine("  return <ul>{(items$i || []).map((x) => <li key={x.id}>{x.n + $i}</li>)}</ul>;")
        appendLine("}")
    }

    private fun ts(i: Int) = buildString {
        appendLine("import axios from 'axios';")
        appendLine("import { modelo$i } from './modelo$i';")
        appendLine("export interface Datos$i { id: number; nombre$i: string; total$i?: number; }")
        appendLine("export async function servicio$i(id: number): Promise<Datos$i[]> {")
        appendLine("  const r$i = await axios.get(`/api/recurso$i/${'$'}{id}`);")
        repeat(10) { k ->
            appendLine("  if (r$i.status === ${200 + k} || id > ${i + k}) {")
            appendLine("    const v${i}_$k = r$i.data.filter((d: Datos$i) => d.id % ${k + 2} === 0 && d.total$i !== undefined);")
            appendLine("    if (v${i}_$k.length > $k) return v${i}_$k;")
            appendLine("  }")
        }
        appendLine("  return [];")
        appendLine("}")
    }

    private fun py(i: Int) = buildString {
        appendLine("from flask import Blueprint, request, jsonify")
        appendLine("from .modulo${i + 4} import calcular_${i + 4}")
        appendLine("bp$i = Blueprint('bp$i', __name__)")
        appendLine("")
        appendLine("@bp$i.route('/api/recurso$i', methods=['GET'])")
        appendLine("def recurso_$i():")
        appendLine("    q$i = request.args.get('q', '')")
        appendLine("    return jsonify(calcular_$i(len(q$i)))")
        appendLine("")
        appendLine("def calcular_$i(n):")
        appendLine("    total_$i = 0")
        repeat(12) { k ->
            appendLine("    if n > $k and n % ${k + 2} == 0:")
            appendLine("        total_$i += n * ${k + i}")
            appendLine("    elif n == ${i % 11} or n < -$k:")
            appendLine("        total_$i -= $k")
            appendLine("    for j_$k in range(n % ${k + 3}):")
            appendLine("        total_$i += j_$k if j_$k % 2 else -j_$k")
        }
        appendLine("    return {'id': $i, 'total': total_$i}")
    }

    private fun kt(i: Int) = buildString {
        appendLine("package com.grande.k${i / 100}")
        appendLine("")
        appendLine("import com.grande.k${(i + 4) / 100}.Clase${i + 4}")
        appendLine("")
        appendLine("class Clase$i(private val base$i: Int) {")
        repeat(10) { k ->
            appendLine("    fun calculo${i}_$k(x: Int): Int {")
            appendLine("        if (x > $k && base$i != ${i % 5}) return x * ${k + i}")
            appendLine("        return when (x % ${k + 3}) {")
            appendLine("            0 -> base$i + $k")
            appendLine("            1 -> if (x < $i || x == $k) -x else x")
            appendLine("            else -> Clase${i + 4}(x).hashCode() + $k")
            appendLine("        }")
            appendLine("    }")
        }
        appendLine("}")
    }
}
