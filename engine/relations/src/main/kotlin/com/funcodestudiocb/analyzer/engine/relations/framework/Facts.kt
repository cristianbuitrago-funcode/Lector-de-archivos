package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.api.DataEntityKind
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.RelationKind

/** Cómo se nombra en el código lo que atiende una ruta (o el componente de una página). */
internal class HandlerRef(
    /** Nombre local (`views`, `UserController`, `usersRouter`) o módulo escrito (`app.views`, `./routes/users`). */
    val name: String,
    /** Miembro: función o método (`detail`, `index`). */
    val member: String? = null,
    val style: Style,
) {
    enum class Style {
        /** Nombre ligado en el archivo por una importación (JavaScript, Python) o declarado en él. */
        BINDING,

        /** Ruta de módulo escrita tal cual (`require('./x')`, `import('./x')`, `include('app.urls')`). */
        MODULE,

        /** Nombre de clase (PHP, JVM, C#, Dart): se busca por las importaciones, el paquete y el proyecto. */
        CLASS,
    }

    override fun toString(): String = if (member != null) "$name.$member" else name
}

/** Ruta declarada: del backend (API) o del enrutador del frontend (página). */
internal class RouteFact(
    val kind: EndpointKind,
    val method: String,
    /** Ruta tal como se escribió, con los prefijos ya conocidos dentro del mismo archivo. */
    val path: String,
    val line: Int,
    val framework: String,
    val handler: HandlerRef? = null,
    /** Texto del controlador para mostrarlo (`UserController@index`, `views.detail`). */
    val handlerLabel: String? = null,
    /** Objeto sobre el que se declara (`router`, `bp`): su prefijo llega por el montaje. */
    val group: String? = null,
    /** La ruta es una expresión regular (Django `re_path`): no se compara con las llamadas. */
    val regex: Boolean = false,
)

/** Un enrutador se monta en otro con un prefijo (`app.use('/api', r)`, `include('x.urls')`, `register_blueprint`). */
internal class MountFact(
    val prefix: String,
    val target: HandlerRef,
    val line: Int,
    val framework: String,
    /** Objeto en el que se monta (para encadenar prefijos dentro del mismo archivo). */
    val group: String? = null,
)

/** Prefijo de un grupo de rutas al crearlo (`Blueprint(url_prefix=…)`, `APIRouter(prefix=…)`) o raíz de la aplicación. */
internal class GroupFact(val name: String, val prefix: String, val root: Boolean, val framework: String)

/** Prefijo global de un framework (`setGlobalPrefix`, `server.servlet.context-path`, `UsePathBase`). */
internal class GlobalPrefixFact(val framework: String, val prefix: String, val line: Int)

/** Llamada HTTP desde el código (cliente). La URL lleva [DYNAMIC] donde hay partes que solo se conocen al ejecutar. */
internal class HttpCallFact(
    val method: String,
    val url: String,
    val line: Int,
    val client: String,
    /** Campos del cuerpo enviados (claves de un objeto literal). */
    val fields: List<String> = emptyList(),
    /** Se resuelve contra la URL base de Retrofit (ruta relativa a `baseUrl`). */
    val retrofit: Boolean = false,
)

/** Tabla o colección definida (SQL, migración, modelo de un ORM). */
internal class TableDefFact(
    val name: String,
    val kind: DataEntityKind,
    val line: Int,
    val source: String,
    /** Clase del modelo, si la tabla se define con un ORM. */
    val model: String? = null,
    /** El nombre de la tabla sale de la convención del ORM, no está escrito. */
    val conventional: Boolean = false,
)

/** Acceso a una tabla (SQL escrito) o a un modelo del ORM. */
internal class DbUseFact(
    val table: String?,
    val model: String?,
    val operation: String,
    val line: Int,
    val via: String,
    val kind: DataEntityKind = DataEntityKind.TABLE,
)

internal enum class ConfigKind { ENV, SPRING, LARAVEL, DOTNET, DJANGO, BUILD_CONFIG }

/** Clave de configuración definida (nunca su valor). */
internal class ConfigDefFact(val key: String, val kind: ConfigKind, val line: Int, val source: String)

/** Lectura de una clave de configuración. */
internal class ConfigUseFact(val key: String, val kind: ConfigKind, val line: Int, val via: String)

/** Punto donde el framework inyecta una dependencia de un tipo. */
internal class InjectFact(val type: String, val line: Int, val via: String)

/** Registro explícito de una implementación (`AddScoped<I, C>`, `bind(I::class, C::class)`, `@Binds`). */
internal class BindFact(val contract: String, val implementation: String, val line: Int, val via: String)

/** Referencia a un recurso con nombre: `R.layout.x`, `@drawable/x`, `ActivityMainBinding`. */
internal class ResUseFact(val type: String, val name: String, val line: Int, val via: String)

/** Recurso de valores definido (`<string name="x">` en res/values). */
internal class ResDefFact(val type: String, val name: String, val line: Int)

/** Clase nombrada en un archivo de configuración o en una llamada: manifiesto, layout, `fx:controller`, Intent. */
internal class ClassRefFact(val name: String, val line: Int, val kind: RelationKind, val via: String)

/** Ruta a otro archivo escrita en un archivo que no es código (escenas, CMake, Qt…). */
internal class PathRefFact(
    val value: String,
    val line: Int,
    val kind: RelationKind,
    val via: String,
    val relative: Boolean = true,
    /** La ruta está escrita tal cual (no se construyó con variables): si no existe, es un posible error. */
    val certain: Boolean = true,
)

/** GUID de Unity: definido en un `.meta` o usado en una escena, prefab o asset. */
internal class GuidUseFact(val guid: String, val line: Int, val via: String)

/** Extremo de un canal de mensajes (IPC de Electron, socket.io, MethodChannel de Flutter, broadcasts de Android). */
internal class ChannelFact(
    val bus: String,
    val name: String,
    val send: Boolean,
    val line: Int,
    val via: String,
    /** Clase que atiende el canal cuando se declara en otro archivo (receptor del manifiesto de Android). */
    val owner: String? = null,
)

/** Todo lo que los analizadores de framework encontraron en un archivo. */
internal class FrameworkFacts {
    val routes = mutableListOf<RouteFact>()
    val mounts = mutableListOf<MountFact>()
    val groups = mutableListOf<GroupFact>()
    val globalPrefixes = mutableListOf<GlobalPrefixFact>()
    val calls = mutableListOf<HttpCallFact>()
    val baseUrls = mutableListOf<Pair<String, Int>>()
    val tables = mutableListOf<TableDefFact>()
    val dbUses = mutableListOf<DbUseFact>()
    val configDefs = mutableListOf<ConfigDefFact>()
    val configUses = mutableListOf<ConfigUseFact>()
    val injections = mutableListOf<InjectFact>()
    val binds = mutableListOf<BindFact>()
    val resUses = mutableListOf<ResUseFact>()
    val resDefs = mutableListOf<ResDefFact>()
    val classRefs = mutableListOf<ClassRefFact>()
    val pathRefs = mutableListOf<PathRefFact>()
    val guidUses = mutableListOf<GuidUseFact>()
    val channels = mutableListOf<ChannelFact>()

    /** Nombre local → módulo importado y miembro (JavaScript, Python, PHP). */
    val bindings = HashMap<String, Pair<String, String?>>()

    /** Métodos públicos declarados (para comprobar `[Controller::class, 'método']`). */
    val methods = HashSet<String>()

    /** GUID de Unity de este recurso (si es un `.meta`). */
    var guid: String? = null

    /** Paquete del manifiesto de Android (`package="…"`) o `namespace` de Gradle. */
    var androidPackage: String? = null

    fun isEmpty(): Boolean =
        routes.isEmpty() && mounts.isEmpty() && groups.isEmpty() && globalPrefixes.isEmpty() && calls.isEmpty() && baseUrls.isEmpty() &&
            tables.isEmpty() && dbUses.isEmpty() && configDefs.isEmpty() && configUses.isEmpty() && injections.isEmpty() && binds.isEmpty() &&
            resUses.isEmpty() && resDefs.isEmpty() && classRefs.isEmpty() && pathRefs.isEmpty() && guidUses.isEmpty() && channels.isEmpty() &&
            guid == null && androidPackage == null

    /** Número de hechos, para el límite por archivo. */
    fun size(): Int = routes.size + mounts.size + calls.size + tables.size + dbUses.size + configDefs.size + configUses.size + injections.size +
        binds.size + resUses.size + resDefs.size + classRefs.size + pathRefs.size + guidUses.size + channels.size

    companion object {
        const val MAX_PER_FILE = 3_000
    }
}
