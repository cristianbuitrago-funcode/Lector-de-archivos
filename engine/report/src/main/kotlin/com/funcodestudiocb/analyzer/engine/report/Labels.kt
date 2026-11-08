package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DataEntityKind
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.MaintenanceLevel
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Textos en español del informe (la app solo está en español) y formato de números, tamaños y fechas. */
object Labels {
    val SPANISH: Locale = Locale.forLanguageTag("es-ES")

    fun severity(s: Severity) = when (s) {
        Severity.CRITICAL -> "Crítica"
        Severity.HIGH -> "Alta"
        Severity.MEDIUM -> "Media"
        Severity.LOW -> "Baja"
        Severity.INFO -> "Informativa"
    }

    fun severityTone(s: Severity) = when (s) {
        Severity.CRITICAL, Severity.HIGH -> Tone.DANGER
        Severity.MEDIUM -> Tone.WARNING
        Severity.LOW, Severity.INFO -> Tone.INFO
    }

    fun certainty(c: Certainty) = when (c) {
        Certainty.CONFIRMED -> "Confirmado"
        Certainty.PROBABLE -> "Probable"
        Certainty.POSSIBLE -> "Posible"
        Certainty.DYNAMIC -> "Requiere prueba dinámica"
    }

    fun confidence(c: Confidence) = when (c) {
        Confidence.CONFIRMED -> "Confirmada"
        Confidence.PROBABLE -> "Probable"
        Confidence.POSSIBLE -> "Posible"
    }

    fun relationConfidence(c: RelationConfidence) = when (c) {
        RelationConfidence.VERIFIED -> "Verificada"
        RelationConfidence.PROBABLE -> "Probable"
        RelationConfidence.UNVERIFIED -> "No verificada"
        RelationConfidence.BROKEN -> "Rota"
    }

    fun relationKind(k: RelationKind) = when (k) {
        RelationKind.IMPORT -> "Importación"
        RelationKind.INHERITANCE -> "Herencia"
        RelationKind.IMPLEMENTATION -> "Implementación"
        RelationKind.REFERENCE -> "Referencia"
        RelationKind.CALL -> "Llamada"
        RelationKind.ROUTE_HANDLER -> "Ruta → controlador"
        RelationKind.HTTP -> "Llamada HTTP"
        RelationKind.DB_ACCESS -> "Acceso a datos"
        RelationKind.CONFIG_READ -> "Lectura de configuración"
        RelationKind.RESOURCE -> "Recurso"
        RelationKind.IPC -> "Comunicación entre procesos"
        RelationKind.INJECTION -> "Inyección"
        RelationKind.REGISTRATION -> "Registro"
    }

    fun layer(l: ArchLayer) = when (l) {
        ArchLayer.FRONTEND -> "Frontend"
        ArchLayer.API -> "API"
        ArchLayer.CONTROLLER -> "Controlador"
        ArchLayer.SERVICE -> "Servicio"
        ArchLayer.MODEL -> "Modelo"
        ArchLayer.DATABASE -> "Base de datos"
        ArchLayer.SHARED -> "Compartido"
        ArchLayer.CONFIG -> "Configuración"
        ArchLayer.TEST -> "Pruebas"
    }

    fun role(r: FileRole) = when (r) {
        FileRole.ENTRY_POINT -> "Punto de entrada"
        FileRole.CONFIGURATION -> "Configuración"
        FileRole.DEPENDENCIES -> "Dependencias"
        FileRole.BUILD -> "Compilación"
        FileRole.DATABASE -> "Base de datos"
        FileRole.AUTHENTICATION -> "Autenticación"
        FileRole.SERVER -> "Servidor"
        FileRole.UI -> "Interfaz"
        FileRole.SOURCE -> "Código fuente"
        FileRole.TEST -> "Pruebas"
        FileRole.DOCUMENTATION -> "Documentación"
        FileRole.RESOURCE -> "Recurso"
        FileRole.MEDIA -> "Multimedia"
        FileRole.DATA -> "Datos"
        FileRole.BINARY -> "Binario"
        FileRole.POSSIBLY_UNNECESSARY -> "Posiblemente innecesario"
    }

    fun entryPoint(k: EntryPointKind) = when (k) {
        EntryPointKind.ANDROID_LAUNCHER -> "Actividad de inicio de Android"
        EntryPointKind.ANDROID_APPLICATION -> "Clase Application de Android"
        EntryPointKind.ANDROID_EXPORTED_COMPONENT -> "Componente Android exportado"
        EntryPointKind.MAIN_FUNCTION -> "Función principal"
        EntryPointKind.SERVER_APP -> "Arranque del servidor"
        EntryPointKind.PACKAGE_MAIN -> "Entrada del paquete"
        EntryPointKind.PACKAGE_BIN -> "Ejecutable del paquete"
        EntryPointKind.NPM_SCRIPT -> "Script de npm"
        EntryPointKind.WEB_PAGE -> "Página web"
        EntryPointKind.WEB_BOOTSTRAP -> "Arranque de la web"
        EntryPointKind.PHP_FRONT_CONTROLLER -> "Controlador frontal PHP"
        EntryPointKind.ELECTRON_MAIN -> "Proceso principal de Electron"
        EntryPointKind.GAME_MAIN_SCENE -> "Escena principal del juego"
        EntryPointKind.GAME_AUTOLOAD -> "Carga automática del juego"
    }

    fun technologyCategory(c: TechnologyCategory) = when (c) {
        TechnologyCategory.LANGUAGE_RUNTIME -> "Lenguaje o entorno"
        TechnologyCategory.FRAMEWORK -> "Framework"
        TechnologyCategory.UI -> "Interfaz"
        TechnologyCategory.MOBILE -> "Móvil"
        TechnologyCategory.GAME_ENGINE -> "Motor de juegos"
        TechnologyCategory.DATABASE -> "Base de datos"
        TechnologyCategory.DATA_ACCESS -> "Acceso a datos"
        TechnologyCategory.EXTERNAL_SERVICE -> "Servicio externo"
        TechnologyCategory.CLOUD -> "Nube"
        TechnologyCategory.AUTHENTICATION -> "Autenticación"
        TechnologyCategory.NETWORKING -> "Red"
        TechnologyCategory.TESTING -> "Pruebas"
        TechnologyCategory.BUILD_TOOL -> "Compilación"
        TechnologyCategory.DEVOPS -> "DevOps"
        TechnologyCategory.LIBRARY -> "Biblioteca"
    }

    fun securityCategory(c: SecurityCategory) = when (c) {
        SecurityCategory.SQL_INJECTION -> "Inyección SQL"
        SecurityCategory.XSS -> "XSS"
        SecurityCategory.CSRF -> "CSRF"
        SecurityCategory.PATH_TRAVERSAL -> "Recorrido de rutas"
        SecurityCategory.COMMAND_EXECUTION -> "Ejecución de comandos"
        SecurityCategory.HARDCODED_PASSWORD -> "Contraseña en el código"
        SecurityCategory.EXPOSED_SECRET -> "Secreto expuesto"
        SecurityCategory.INSECURE_CONFIGURATION -> "Configuración insegura"
        SecurityCategory.EXCESSIVE_PERMISSIONS -> "Permisos excesivos"
        SecurityCategory.WEAK_AUTHENTICATION -> "Autenticación débil"
        SecurityCategory.INSUFFICIENT_VALIDATION -> "Validación insuficiente"
        SecurityCategory.VULNERABLE_DEPENDENCY -> "Dependencia vulnerable"
        SecurityCategory.OUTDATED_LIBRARY -> "Versión sin soporte"
        SecurityCategory.CORS -> "CORS"
        SecurityCategory.SENSITIVE_DATA_EXPOSURE -> "Exposición de datos sensibles"
        SecurityCategory.SESSION -> "Sesiones"
        SecurityCategory.ACCESS_CONTROL -> "Control de acceso"
        SecurityCategory.SUPPRESSION -> "Supresión de avisos"
    }

    fun usage(u: UsageStatus) = when (u) {
        UsageStatus.USED -> "Usada"
        UsageStatus.NOT_SEEN -> "Sin uso visto"
        UsageStatus.DEVELOPMENT_TOOL -> "Herramienta de desarrollo"
        UsageStatus.INDIRECT -> "Indirecta"
        UsageStatus.NOT_ANALYZED -> "No analizado"
    }

    fun eol(s: EolStatus) = when (s) {
        EolStatus.UNSUPPORTED -> "Sin soporte"
        EolStatus.ENDING_SOON -> "Lo pierde pronto"
        EolStatus.SUPPORTED -> "Con soporte"
        EolStatus.UNKNOWN_CYCLE -> "Versión no reconocida"
    }

    fun fileStatus(s: FileStatus) = when (s) {
        FileStatus.ANALYZED -> "Analizado"
        FileStatus.SKIPPED_TOO_LARGE -> "Demasiado grande"
        FileStatus.SKIPPED_LIMIT -> "Límite del análisis alcanzado"
        FileStatus.SKIPPED_BOMB -> "Posible bomba de descompresión"
        FileStatus.ENCRYPTED -> "Cifrado"
        FileStatus.CORRUPT -> "Dañado"
        FileStatus.UNSUPPORTED_METHOD -> "Compresión no admitida"
    }

    fun dataEntity(k: DataEntityKind) = when (k) {
        DataEntityKind.TABLE -> "Tabla"
        DataEntityKind.COLLECTION -> "Colección"
    }

    fun coverage(r: CoverageReason) = when (r) {
        CoverageReason.STAGE_FAILED -> "Etapa con error"
        CoverageReason.STAGE_SKIPPED -> "Etapa no ejecutada"
        CoverageReason.STAGE_TIMED_OUT -> "Etapa sin tiempo"
        CoverageReason.NOT_ANALYZED -> "No analizado"
        CoverageReason.LIMIT_REACHED -> "Límite alcanzado"
        CoverageReason.UNSUPPORTED -> "No admitido"
        CoverageReason.EXCLUDED -> "Excluido"
    }

    fun stageStatus(s: StageStatus) = when (s) {
        StageStatus.COMPLETED -> "Completada"
        StageStatus.FAILED -> "Con error"
        StageStatus.SKIPPED -> "No ejecutada"
        StageStatus.TIMED_OUT -> "Sin tiempo"
        StageStatus.CANCELLED -> "Cancelada"
    }

    fun outcome(o: AnalysisOutcome) = when (o) {
        AnalysisOutcome.COMPLETED -> "Completo"
        AnalysisOutcome.PARTIAL -> "Parcial (ver Anexo A)"
        AnalysisOutcome.REJECTED -> "Rechazado"
        AnalysisOutcome.PASSWORD_MISSING -> "Falta la contraseña"
        AnalysisOutcome.FAILED -> "No terminó"
    }

    fun maintenance(l: MaintenanceLevel) = when (l) {
        MaintenanceLevel.HIGH -> "Alto"
        MaintenanceLevel.GOOD -> "Bueno"
        MaintenanceLevel.MEDIUM -> "Medio"
        MaintenanceLevel.LOW -> "Bajo"
        MaintenanceLevel.VERY_LOW -> "Muy bajo"
    }

    fun maintenanceTone(l: MaintenanceLevel?) = when (l) {
        MaintenanceLevel.HIGH, MaintenanceLevel.GOOD -> Tone.GOOD
        MaintenanceLevel.MEDIUM -> Tone.WARNING
        MaintenanceLevel.LOW, MaintenanceLevel.VERY_LOW -> Tone.DANGER
        null -> Tone.NEUTRAL
    }

    fun qualityTool(k: QualityToolKind) = when (k) {
        QualityToolKind.LINTER -> "Analizador de código"
        QualityToolKind.FORMATTER -> "Formateador"
        QualityToolKind.CI -> "Integración continua"
        QualityToolKind.TYPE_CHECKER -> "Comprobación de tipos"
    }

    /** Título de cada etapa del motor (para la cobertura). */
    fun stage(id: String) = STAGES[id] ?: id

    private val STAGES = mapOf(
        "ingest.open" to "Apertura y comprobación del comprimido",
        "ingest.read" to "Lectura de los archivos",
        "ingest.duplicates" to "Búsqueda de archivos duplicados",
        "inventory" to "Inventario y puntos de entrada",
        "languages" to "Lenguajes y líneas de código",
        "dependencies" to "Dependencias declaradas",
        "technologies" to "Tecnologías, bases de datos y servicios externos",
        "relations" to "Relaciones entre archivos",
        "dependency-audit" to "Dependencias y vulnerabilidades",
        "security" to "Auditoría de seguridad",
        "architecture" to "Arquitectura y diagramas",
        "metrics" to "Métricas e índice de mantenimiento",
    )

    // ------------------------------------------------------------------ formato

    /** Número con separador de miles español: «12.345». */
    fun number(n: Long): String = String.format(SPANISH, "%,d", n)

    fun number(n: Int): String = number(n.toLong())

    /** Decimal con coma: «2,5». */
    fun decimal(value: Double, digits: Int = 1): String = String.format(SPANISH, "%.${digits}f", value)

    /** Porcentaje: «4,5 %», «12 %». */
    fun percent(fraction: Double): String {
        val p = fraction * 100
        return if (p > 0 && p < 10) String.format(SPANISH, "%.1f %%", p) else "${p.roundToInt()} %"
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "${number(n)} B"
        n < 1024L * 1024 -> "${decimal(n / 1024.0)} KB"
        n < 1024L * 1024 * 1024 -> "${decimal(n / (1024.0 * 1024))} MB"
        else -> "${decimal(n / (1024.0 * 1024 * 1024))} GB"
    }

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", SPANISH)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH)

    fun dateTime(epochMs: Long, zone: ZoneId): String = DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(epochMs: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** «1 archivo» / «3 archivos». */
    fun count(n: Int, singular: String, plural: String) = if (n == 1) "1 $singular" else "${number(n)} $plural"

    fun count(n: Long, singular: String, plural: String) = if (n == 1L) "1 $singular" else "${number(n)} $plural"
}
