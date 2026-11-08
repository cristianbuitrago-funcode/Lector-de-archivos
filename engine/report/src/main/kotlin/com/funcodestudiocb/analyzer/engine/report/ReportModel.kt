package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.Diagram
import java.time.ZoneId

/**
 * Datos del informe que no salen de los registros del motor: los pone quien lo genera (la app o la CLI).
 * [archiveSha256] es la huella del comprimido tal como se importó.
 */
data class ReportMeta(
    val analysisId: String,
    val archiveName: String,
    val archiveSize: Long,
    val archiveSha256: String?,
    val analyzedAtEpochMs: Long?,
    val generatedAtEpochMs: Long,
    val appVersion: String,
    val engineVersion: String?,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Tono con el que se presenta un dato (color en HTML y PDF). */
enum class Tone { NEUTRAL, GOOD, INFO, WARNING, DANGER }

/** Frase del resumen o de las conclusiones con la sección de la que sale su dato (`§11`, `Anexo A`). */
data class Statement(val text: String, val ref: String)

/** Barra de un gráfico horizontal. */
data class Bar(val label: String, val value: Long, val max: Long, val tone: Tone, val display: String)

/** Bloques de contenido de una sección: el mismo modelo se dibuja en HTML, en PDF y en la pantalla. */
sealed interface Block {
    data class Heading(val text: String) : Block

    data class Paragraph(val text: String) : Block

    /** Frases trazables (resumen ejecutivo, conclusiones y recomendaciones). */
    data class Statements(val items: List<Statement>, val numbered: Boolean = false) : Block

    /** Pares dato-valor. */
    data class Facts(val items: List<Pair<String, String>>) : Block

    /** Tabla; [tones] da el tono de cada fila (o `null`); [note], lo que no cabe («y 12 más»). */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val tones: List<Tone>? = null,
        val note: String? = null,
    ) : Block

    data class Bullets(val items: List<String>) : Block

    data class Callout(val tone: Tone, val text: String) : Block

    data class Bars(val items: List<Bar>) : Block

    /** Indicador 0–100 (índice de mantenimiento). */
    data class Gauge(val score: Int?, val label: String, val tone: Tone, val caption: String) : Block

    /** Diagrama ya dispuesto por el motor (Fase 8). */
    data class Figure(val diagram: Diagram, val caption: String) : Block
}

data class ReportSection(
    /** Ancla estable: `s1`…`s15`, `anexo-a`, `anexo-b`. */
    val id: String,
    /** `1`…`15`, `A`, `B`. */
    val number: String,
    val title: String,
    val blocks: List<Block>,
) {
    /** Referencia con la que lo citan otras secciones: `§4` o `Anexo A`. */
    val ref: String get() = if (number.all { it.isDigit() }) "§$number" else "Anexo $number"
}

/** Informe completo, independiente del formato. */
data class ReportDocument(
    val title: String,
    val subtitle: String,
    val meta: ReportMeta,
    val sections: List<ReportSection>,
)
