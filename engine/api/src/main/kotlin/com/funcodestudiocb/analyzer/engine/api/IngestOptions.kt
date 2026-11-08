package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.Serializable

/** Opciones de la carga y lectura del comprimido (modo [AnalysisMode.FULL]). */
@Serializable
data class IngestOptions(
    /** Nombre del archivo tal como lo eligió el usuario (solo para mostrar). */
    val displayName: String,
    val limits: IngestLimits = IngestLimits(),
    val exclusions: ExclusionOptions = ExclusionOptions(),
)

/** Límites de la ingesta (§9.3 de la arquitectura). Configurables dentro de rangos seguros. */
@Serializable
data class IngestLimits(
    val maxArchiveBytes: Long = 300L * MIB,
    /** Bytes descomprimidos REALES (no declarados) permitidos en total. */
    val maxTotalUncompressedBytes: Long = 1024L * MIB,
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 100L * MIB,
    /** Proporción máxima descomprimido/comprimido de una entrada… */
    val maxEntryRatio: Int = 250,
    /** …aplicada a partir de este tamaño descomprimido. */
    val entryRatioThresholdBytes: Long = 1L * MIB,
    /** Proporción máxima entre lo declarado y el tamaño del comprimido. */
    val maxGlobalRatio: Int = 100,
    val maxPathDepth: Int = 64,
    val maxPathLength: Int = 1024,
    /** Memoria máxima que UnRAR puede reservar (diccionario), en MiB. */
    val maxRarMemoryMb: Int = 512,
) {
    companion object {
        const val MIB: Long = 1024L * 1024L
    }
}

/** Qué se excluye del análisis. Lo excluido se registra (ruta, número de archivos y tamaño), pero no se lee. */
@Serializable
data class ExclusionOptions(
    /** Nombres de carpeta excluidos en cualquier nivel (comparación exacta del nombre). */
    val directoryNames: List<String> = DEFAULT_DIRECTORY_NAMES,
    /** Excluir carpetas generadas por motores detectados (Unity, Unreal…). */
    val contextRules: Boolean = true,
    /** Excluir archivos temporales y de sistema (.DS_Store, Thumbs.db, *.swp…). */
    val temporaryFiles: Boolean = true,
) {
    companion object {
        val DEFAULT_DIRECTORY_NAMES: List<String> = listOf(
            "node_modules", "bower_components", "jspm_packages",
            ".git", ".svn", ".hg",
            "build", "dist", "out", "target", "obj", ".cxx", ".externalNativeBuild",
            "vendor", "Pods", "DerivedData",
            ".gradle", ".idea", ".vs",
            "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache", ".tox",
            ".dart_tool", ".pub-cache", ".next", ".nuxt", ".angular", ".cache", "coverage", ".terraform",
        )
        const val MAX_NAMES = 200
        const val MAX_NAME_LENGTH = 100
    }
}

/** Validación de las opciones de ingesta recibidas desde el otro proceso. */
internal object IngestOptionsValidator {
    private val NAME_PATTERN = Regex("^[^/\\\\\\u0000-\\u001F]{1,${ExclusionOptions.MAX_NAME_LENGTH}}$")

    fun validate(options: IngestOptions): List<String> {
        val errors = mutableListOf<String>()
        if (options.displayName.isEmpty() || options.displayName.length > 255) errors += "Nombre de archivo inválido"
        val l = options.limits
        val mib = IngestLimits.MIB
        if (l.maxArchiveBytes !in 1L..2048L * mib) errors += "Tamaño máximo del comprimido fuera de rango"
        if (l.maxTotalUncompressedBytes !in 1L..8192L * mib) errors += "Tamaño total máximo fuera de rango"
        if (l.maxEntries !in 1..1_000_000) errors += "Número máximo de entradas fuera de rango"
        if (l.maxEntryBytes !in 1L..l.maxTotalUncompressedBytes) errors += "Tamaño máximo por archivo fuera de rango"
        if (l.maxEntryRatio !in 2..100_000 || l.maxGlobalRatio !in 2..100_000) errors += "Proporción de compresión fuera de rango"
        if (l.entryRatioThresholdBytes < 0) errors += "Umbral de proporción inválido"
        if (l.maxPathDepth !in 1..1024 || l.maxPathLength !in 16..32_768) errors += "Límites de ruta fuera de rango"
        if (l.maxRarMemoryMb !in 16..4096) errors += "Memoria máxima de RAR fuera de rango"
        val names = options.exclusions.directoryNames
        if (names.size > ExclusionOptions.MAX_NAMES) errors += "Demasiadas carpetas excluidas"
        if (names.any { !NAME_PATTERN.matches(it) || it == "." || it == ".." }) errors += "Nombre de carpeta excluida inválido"
        return errors
    }
}
