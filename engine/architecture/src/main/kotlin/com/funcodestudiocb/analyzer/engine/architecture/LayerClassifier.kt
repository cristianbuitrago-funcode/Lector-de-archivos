package com.funcodestudiocb.analyzer.engine.architecture

import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.DataEntity
import com.funcodestudiocb.analyzer.engine.api.DataEntityKind
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo

/** Capa asignada a un archivo, con su certeza y el motivo (en español, para la ficha). */
internal class Assignment(val layer: ArchLayer, val confidence: Confidence, val reason: String)

/**
 * Decide la capa de cada archivo, de la evidencia más fuerte a la más débil:
 *
 * 1. Pruebas y configuración (función del archivo según el inventario).
 * 2. Marcas explícitas en el código (`@RestController`, `models.Model`, `@Entity`, `@Composable`…): confirmada.
 * 3. Lo que el análisis de framework demostró: define una tabla (modelo), declara rutas de la API, atiende una ruta
 *    (controlador), declara una página (frontend): confirmada.
 * 4. Convenciones de carpetas y nombres (`controllers/`, `UserService`, `models.py`, `components/`…): probable.
 * 5. Función del archivo según el inventario (interfaz, base de datos, servidor, autenticación): probable.
 * 6. Llama a la API del propio proyecto: posible frontend.
 * 7. Si nada de lo anterior: "compartido" (utilidades, scripts), con certeza posible.
 */
internal class LayerClassifier(
    private val roles: Map<String, Set<FileRole>>,
    private val marks: Map<String, Map<ArchLayer, String>>,
    endpoints: List<Endpoint>,
    entities: List<DataEntity>,
    private val httpCallers: Set<String>,
) {
    private val routeFiles = HashMap<String, Endpoint>()
    private val handlerFiles = HashMap<String, Endpoint>()
    private val pageFiles = HashMap<String, Endpoint>()
    private val entityFiles = HashMap<String, DataEntity>()

    /** El proyecto tiene rutas HTTP: solo entonces una carpeta `api/` o `client/` habla de una API web. */
    private val web = endpoints.isNotEmpty()

    init {
        for (e in endpoints) {
            if (e.kind == EndpointKind.API) {
                routeFiles.putIfAbsent(e.file, e)
                e.handlerFile?.takeIf { it != e.file }?.let { handlerFiles.putIfAbsent(it, e) }
            } else {
                pageFiles.putIfAbsent(e.file, e)
                e.handlerFile?.let { pageFiles.putIfAbsent(it, e) }
            }
        }
        entities.forEach { e -> e.file?.let { entityFiles.putIfAbsent(it, e) } }
    }

    fun classify(path: String): Assignment {
        val info = PathInfo(path)
        val fileRoles = roles[path].orEmpty()
        if (FileRole.TEST in fileRoles) return Assignment(ArchLayer.TEST, Confidence.PROBABLE, "archivo de pruebas")
        val marked = marks[path]
        if (marked == null && fileRoles.any { it == FileRole.CONFIGURATION || it == FileRole.BUILD || it == FileRole.DEPENDENCIES }) {
            return Assignment(ArchLayer.CONFIG, Confidence.PROBABLE, "configuración, compilación o manifiesto")
        }
        marked?.entries?.firstOrNull()?.let { (layer, mark) ->
            val what = if (layer == ArchLayer.CONTROLLER && mark.contains("ViewModel")) "ViewModel" else "marca"
            return Assignment(layer, Confidence.CONFIRMED, "$what «${mark.trim()}» en el código")
        }
        entityFiles[path]?.let { e ->
            val what = if (e.kind == DataEntityKind.COLLECTION) "la colección" else "la tabla"
            val layer = if (info.ext == "sql" || isMigration(info)) ArchLayer.DATABASE else ArchLayer.MODEL
            return Assignment(layer, Confidence.CONFIRMED, "define $what «${e.name}»")
        }
        routeFiles[path]?.let { e -> return Assignment(ArchLayer.API, Confidence.CONFIRMED, "declara la ruta ${e.method} ${e.path}") }
        handlerFiles[path]?.let { e -> return Assignment(ArchLayer.CONTROLLER, Confidence.CONFIRMED, "atiende la ruta ${e.method} ${e.path}") }
        pageFiles[path]?.let { e -> return Assignment(ArchLayer.FRONTEND, Confidence.CONFIRMED, "página ${e.path}") }
        convention(info)?.let { found ->
            // Un "api.js" que llama a las rutas del proyecto y no declara ninguna es el cliente del frontend.
            if (found.layer == ArchLayer.API && path in httpCallers) {
                return Assignment(ArchLayer.FRONTEND, Confidence.PROBABLE, "cliente de la API: llama a las rutas del proyecto")
            }
            return found
        }
        when {
            FileRole.UI in fileRoles -> return Assignment(ArchLayer.FRONTEND, Confidence.PROBABLE, "archivo de interfaz")
            FileRole.DATABASE in fileRoles -> return Assignment(ArchLayer.MODEL, Confidence.PROBABLE, "archivo de base de datos")
            FileRole.SERVER in fileRoles && web -> return Assignment(ArchLayer.API, Confidence.PROBABLE, "archivo del servidor")
            FileRole.AUTHENTICATION in fileRoles -> return Assignment(ArchLayer.SERVICE, Confidence.POSSIBLE, "autenticación")
        }
        if (path in httpCallers) return Assignment(ArchLayer.FRONTEND, Confidence.POSSIBLE, "llama a la API del propio proyecto")
        return Assignment(ArchLayer.SHARED, Confidence.POSSIBLE, "sin una capa clara (utilidades, código compartido o scripts)")
    }

    private fun isMigration(info: PathInfo) = info.lowerDirs.any { it == "migrations" || it == "migration" || it == "migrate" }

    /** Convenciones de carpetas y nombres (certeza probable). */
    private fun convention(info: PathInfo): Assignment? {
        val dirs = info.lowerDirs
        val stem = info.stem.replace("-", "").replace("_", "").replace(".", "")
        fun dir(vararg names: String) = dirs.lastOrNull { it in names }
        fun probable(layer: ArchLayer, why: String) = Assignment(layer, Confidence.PROBABLE, why)

        if (info.ext == "sql" || info.ext == "prisma" || isMigration(info)) return probable(ArchLayer.DATABASE, "esquema o migración de la base de datos")
        if (info.ext == "py") {
            when (info.stem) {
                "views" -> return probable(ArchLayer.CONTROLLER, "views.py (vistas de Django)")
                "urls" -> return probable(ArchLayer.API, "urls.py (rutas de Django)")
                "models" -> return probable(ArchLayer.MODEL, "models.py (modelos)")
                "serializers" -> return probable(ArchLayer.CONTROLLER, "serializers.py")
            }
        }
        if (info.ext == "py" && "views" in dirs) return probable(ArchLayer.CONTROLLER, "carpeta «views» (vistas de Django)")
        if (info.ext == "xml" && dirs.any { it.startsWith("layout") } && "res" in dirs) return probable(ArchLayer.FRONTEND, "diseño de pantalla de Android")
        val byName = listOf(
            ArchLayer.CONTROLLER to listOf("controller", "handler", "viewmodel", "presenter"),
            ArchLayer.SERVICE to listOf("service", "usecase", "interactor"),
            ArchLayer.MODEL to listOf("repository", "repo", "dao", "entity", "model", "schema"),
            ArchLayer.API to listOf("routes", "router", "endpoints", "api"),
        )
        for ((layer, suffixes) in byName) {
            if (layer == ArchLayer.API && !web) continue
            val s = suffixes.firstOrNull { stem.endsWith(it) && stem.length > it.length || stem == it || stem == it + "s" }
            if (s != null) return probable(layer, "nombre «${info.name}»")
        }
        val byDir = listOf(
            ArchLayer.CONTROLLER to arrayOf("controllers", "controller", "handlers", "handler", "viewmodels", "viewmodel", "presenters", "presentation"),
            ArchLayer.SERVICE to arrayOf("services", "service", "usecases", "usecase", "domain", "business", "interactors", "logic"),
            ArchLayer.MODEL to arrayOf("models", "model", "entities", "entity", "repositories", "repository", "repos", "dao", "daos", "persistence", "schemas", "db", "database", "data"),
            ArchLayer.API to arrayOf("routes", "routers", "router", "api", "endpoints"),
            ArchLayer.FRONTEND to arrayOf(
                "components", "component", "pages", "screens", "ui", "views", "layouts", "templates", "static", "public", "styles",
                "widgets", "activities", "fragments", "frontend", "client", "web",
            ),
        )
        // La carpeta más cercana al archivo manda (src/api/services/x.ts es un servicio).
        var best: Pair<Int, Assignment>? = null
        for ((layer, names) in byDir) {
            val d = dir(*names) ?: continue
            if (!web && (layer == ArchLayer.API || d in WEB_ONLY_DIRS)) continue
            val depth = dirs.lastIndexOf(d)
            if (best == null || depth > best.first) best = depth to probable(layer, "carpeta «$d»")
        }
        best?.let { return it.second }
        if (info.ext in FRONTEND_EXTENSIONS) return probable(ArchLayer.FRONTEND, "archivo de interfaz (.${info.ext})")
        return null
    }

    private companion object {
        /** Carpetas que solo indican frontend en un proyecto web. */
        val WEB_ONLY_DIRS = setOf("client", "web", "public", "static")

        val FRONTEND_EXTENSIONS = setOf("html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "jsx", "tsx", "xaml", "razor", "cshtml", "ejs", "hbs")
    }
}
