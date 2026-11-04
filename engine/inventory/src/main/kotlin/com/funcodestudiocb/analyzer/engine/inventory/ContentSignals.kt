package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.google.re2j.Pattern

/** Patrón encontrado en el contenido de un archivo. [label] lo redacta el motor; nunca es texto del proyecto. */
data class SignalHit(val role: FileRole?, val entry: EntryPointKind?, val label: String, val line: Int)

/**
 * Señales de contenido por lenguaje: patrones de texto que indican la función de un archivo (servidor, base de
 * datos, autenticación, interfaz) o un punto de entrada (main, arranque de un servidor…).
 *
 * Son patrones de texto, no análisis sintáctico (llega en las fases 4–5): por eso todo lo que producen tiene
 * certeza "probable". Se ignoran las líneas comentadas. Se usa RE2/J (tiempo lineal) sobre el contenido hostil.
 */
internal object ContentSignals {

    private class Signal(
        val role: FileRole?,
        val entry: EntryPointKind?,
        val label: String,
        /**
         * Textos literales, separados por `|`, de los que al menos uno aparece en cualquier coincidencia del patrón:
         * si no está ninguno, el patrón no se evalúa (RE2/J no tiene autómata determinista y es lento en archivos
         * grandes). Nunca vacío.
         */
        trigger: String,
        regex: String,
    ) {
        val pattern: Pattern = Pattern.compile(regex)
        val triggers: List<String> = trigger.split('|').also { list -> require(list.all { it.length >= 2 }) { "Aviso demasiado corto: $label" } }

        fun mayMatch(text: String): Boolean = triggers.any { text.contains(it) }
    }

    private fun role(role: FileRole, label: String, trigger: String, regex: String) = Signal(role, null, label, trigger, regex)
    private fun entry(kind: EntryPointKind, label: String, trigger: String, regex: String) = Signal(null, kind, label, trigger, regex)

    private val JVM = listOf(
        role(FileRole.SERVER, "la anotación @RestController (Spring)", "@RestController", """@RestController\b"""),
        role(FileRole.SERVER, "la anotación @Controller (Spring)", "@Controller", """@Controller\b"""),
        role(FileRole.SERVER, "rutas HTTP de Spring (@GetMapping, @PostMapping…)", "Mapping", """@(?:Get|Post|Put|Delete|Patch|Request)Mapping\b"""),
        role(FileRole.SERVER, "rutas de Ktor (routing { … })", "routing", """\brouting\s*\{"""),
        role(FileRole.SERVER, "rutas JAX-RS (@Path)", "@Path", """@Path\s*\("""),
        role(FileRole.DATABASE, "la anotación @Entity", "@Entity", """@Entity\b"""),
        role(FileRole.DATABASE, "la anotación @Dao (Room)", "@Dao", """@Dao\b"""),
        role(FileRole.DATABASE, "la anotación @Database (Room)", "@Database", """@Database\s*\("""),
        role(FileRole.DATABASE, "consultas @Query", "@Query", """@Query\s*\("""),
        role(FileRole.DATABASE, "la anotación @Table (JPA)", "@Table", """@Table\s*\("""),
        role(FileRole.DATABASE, "la anotación @Repository (Spring)", "@Repository", """@Repository\b"""),
        role(FileRole.DATABASE, "SQLiteOpenHelper", "SQLiteOpenHelper", """\bSQLiteOpenHelper\b"""),
        role(FileRole.DATABASE, "Room.databaseBuilder", "databaseBuilder", """Room\.databaseBuilder\s*\("""),
        role(FileRole.DATABASE, "Firebase Firestore o Realtime Database", "Firebase", """\bFirebase(?:Firestore|Database)\b"""),
        role(FileRole.DATABASE, "JdbcTemplate o DriverManager (JDBC)", "JdbcTemplate|DriverManager", """\bJdbcTemplate\b|DriverManager\.getConnection\s*\("""),
        role(FileRole.AUTHENTICATION, "la configuración de Spring Security", "Security", """@EnableWebSecurity\b|\bSecurityFilterChain\b"""),
        role(FileRole.AUTHENTICATION, "Firebase Authentication", "FirebaseAuth", """\bFirebaseAuth\b"""),
        role(FileRole.AUTHENTICATION, "BiometricPrompt (autenticación biométrica)", "BiometricPrompt", """\bBiometricPrompt\b"""),
        role(FileRole.AUTHENTICATION, "anotaciones de autorización (@PreAuthorize/@Secured)", "PreAuthorize|Secured|RolesAllowed", """@(?:PreAuthorize|Secured|RolesAllowed)\b"""),
        role(FileRole.UI, "funciones @Composable (Jetpack Compose)", "@Composable", """@Composable\b"""),
        role(FileRole.UI, "setContentView (pantalla de Android)", "setContentView", """\bsetContentView\s*\("""),
        role(FileRole.UI, "una Activity de Android", "Activity", """:\s*(?:AppCompatActivity|ComponentActivity|FragmentActivity|Activity)\s*\(|\bextends\s+(?:AppCompatActivity|ComponentActivity|FragmentActivity|Activity)\b"""),
        role(FileRole.UI, "un Fragment de Android", "Fragment", """:\s*Fragment\s*\(|\bextends\s+Fragment\b"""),
        role(FileRole.UI, "ventanas de Swing/JavaFX", "JFrame|JPanel|JDialog|javafx.scene", """\bextends\s+(?:JFrame|JPanel|JDialog)\b|\bjavafx\.scene\b"""),
        entry(EntryPointKind.MAIN_FUNCTION, "método main de Java", "main", """\bpublic\s+static\s+void\s+main\s*\("""),
        entry(EntryPointKind.MAIN_FUNCTION, "función main de Kotlin", "main", """(?m)^(?:suspend\s+)?fun\s+main\s*\("""),
        entry(EntryPointKind.SERVER_APP, "clase @SpringBootApplication", "@SpringBootApplication", """@SpringBootApplication\b"""),
        entry(EntryPointKind.SERVER_APP, "servidor Ktor (embeddedServer)", "embeddedServer", """\bembeddedServer\s*\("""),
    )

    private val JS = listOf(
        role(FileRole.SERVER, "Express", "express", """require\(\s*["']express["']\s*\)|from\s+["']express["']"""),
        role(FileRole.SERVER, "Fastify o Koa", "fastify|koa", """require\(\s*["'](?:fastify|koa)["']\s*\)|from\s+["'](?:fastify|koa)["']"""),
        role(FileRole.SERVER, "NestJS", "@nestjs", """from\s+["']@nestjs/"""),
        role(FileRole.SERVER, "definición de rutas HTTP", ".get|.post|.put|.delete|.patch", """\b(?:app|router|server|api)\.(?:get|post|put|delete|patch)\s*\(\s*["'`/]"""),
        role(FileRole.SERVER, "http.createServer", "createServer", """\bcreateServer\s*\("""),
        role(FileRole.DATABASE, "Mongoose (MongoDB)", "mongoose", """mongoose\.(?:Schema|model|connect)\s*\("""),
        role(FileRole.DATABASE, "Sequelize", "equelize", """new\s+Sequelize\s*\(|sequelize\.define\s*\("""),
        role(FileRole.DATABASE, "Prisma Client", "PrismaClient", """\bPrismaClient\b"""),
        role(FileRole.DATABASE, "TypeORM", "typeorm", """from\s+["']typeorm["']"""),
        role(FileRole.DATABASE, "Knex", "knex", """require\(\s*["']knex["']\s*\)|from\s+["']knex["']"""),
        role(FileRole.DATABASE, "Firebase Firestore o Realtime Database", "getFirestore|firebase/|getDatabase", """\bgetFirestore\s*\(|firebase/firestore|firebase/database|\bgetDatabase\s*\("""),
        role(FileRole.DATABASE, "un cliente de MySQL/PostgreSQL/Redis", "mysql|pg|redis|sqlite3|mongodb", """(?:require\(\s*|from\s+)["'](?:mysql2?|pg|redis|ioredis|sqlite3|better-sqlite3|mongodb)["']"""),
        role(FileRole.AUTHENTICATION, "Passport", "passport", """passport\.(?:use|authenticate)\s*\("""),
        role(FileRole.AUTHENTICATION, "JSON Web Tokens", "jsonwebtoken|jwt.", """\bjsonwebtoken\b|\bjwt\.(?:sign|verify)\s*\("""),
        role(FileRole.AUTHENTICATION, "Firebase Authentication", "getAuth|firebase/auth|signInWith", """\bgetAuth\s*\(|firebase/auth|\bsignInWith[A-Z]\w*\s*\("""),
        role(FileRole.AUTHENTICATION, "bcrypt", "bcrypt", """\bbcrypt\b"""),
        role(FileRole.AUTHENTICATION, "NextAuth", "NextAuth|next-auth", """\bNextAuth\s*\(|next-auth"""),
        role(FileRole.UI, "React", "react", """from\s+["']react["']|require\(\s*["']react["']\s*\)"""),
        role(FileRole.UI, "un componente de Angular", "@Component", """@Component\s*\(\s*\{"""),
        role(FileRole.UI, "un componente de Vue", "defineComponent|<template", """\bdefineComponent\s*\(|<template\b"""),
        role(FileRole.UI, "manipulación del DOM", "document.", """\bdocument\.(?:getElementById|querySelector|querySelectorAll|createElement)\s*\("""),
        entry(EntryPointKind.SERVER_APP, "arranque de NestJS", "NestFactory", """\bNestFactory\.create\s*\("""),
        entry(EntryPointKind.WEB_BOOTSTRAP, "montaje de la aplicación en la página", "ReactDOM.render|createRoot|hydrateRoot|createApp|bootstrapApplication|platformBrowserDynamic|Vue", """\bReactDOM\.render\s*\(|\bcreateRoot\s*\(|\bhydrateRoot\s*\(|\bcreateApp\s*\(|\bbootstrapApplication\s*\(|platformBrowserDynamic\s*\(\s*\)\s*\.bootstrapModule|\bnew\s+Vue\s*\("""),
        entry(EntryPointKind.ELECTRON_MAIN, "creación de ventanas de Electron (proceso principal)", "BrowserWindow", """\bnew\s+BrowserWindow\s*\("""),
    )

    /** En JavaScript, un servidor (Express, http…) que además escucha en un puerto es un punto de entrada. */
    private val JS_LISTEN = Pattern.compile("""\.listen\s*\(""")

    private val PY = listOf(
        role(FileRole.SERVER, "rutas HTTP (@app.route, @router.get…)", ".route|.get|.post|.put|.delete|.patch", """@(?:app|bp|blueprint|router|api)\.(?:route|get|post|put|delete|patch)\s*\("""),
        role(FileRole.SERVER, "Django (vistas o URLs)", "django", """from\s+django\.(?:urls|http|views|shortcuts)\b|from\s+rest_framework\b"""),
        role(FileRole.DATABASE, "modelos de Django (models.Model)", "models.Model", """\bmodels\.Model\b"""),
        role(FileRole.DATABASE, "SQLAlchemy", "declarative_base|SQLAlchemy|db.Column|DeclarativeBase", """\bdeclarative_base\s*\(|\bSQLAlchemy\s*\(|\bdb\.Column\s*\(|\bDeclarativeBase\b"""),
        role(FileRole.DATABASE, "un cliente de base de datos", "connect|MongoClient|Redis", """\b(?:sqlite3|psycopg2?|pymysql|pymongo|redis)\.(?:connect|MongoClient|Redis)\s*\(|\bMongoClient\s*\("""),
        role(FileRole.AUTHENTICATION, "autenticación de Django", "django.contrib.auth|login_required", """django\.contrib\.auth|@login_required\b"""),
        role(FileRole.AUTHENTICATION, "autenticación (flask_login, JWT, passlib, bcrypt)", "flask_login|jwt.|passlib|bcrypt", """\bflask_login\b|\bjwt\.(?:encode|decode)\s*\(|\bpasslib\b|\bbcrypt\b"""),
        role(FileRole.UI, "una interfaz de escritorio (Tkinter, Qt, Kivy)", "tkinter|PyQt|PySide|kivy", """^\s*(?:import|from)\s+(?:tkinter|PyQt[56]|PySide[26]|kivy)\b"""),
        entry(EntryPointKind.MAIN_FUNCTION, "bloque if __name__ == \"__main__\"", "__main__", """(?m)^if\s+__name__\s*==\s*["']__main__["']\s*:"""),
        entry(EntryPointKind.MAIN_FUNCTION, "consola de gestión de Django", "execute_from_command_line", """\bexecute_from_command_line\s*\("""),
        entry(EntryPointKind.SERVER_APP, "creación de la aplicación Flask o FastAPI", "Flask|FastAPI", """(?m)^\s*\w+\s*=\s*(?:Flask|FastAPI)\s*\("""),
        entry(EntryPointKind.SERVER_APP, "punto de entrada WSGI/ASGI", "_application", """\bget_(?:wsgi|asgi)_application\s*\("""),
    )

    private val PHP = listOf(
        role(FileRole.SERVER, "rutas de Laravel (Route::…)", "Route::", """Route::(?:get|post|put|patch|delete|resource|apiResource|middleware|group|prefix)\s*\("""),
        role(FileRole.SERVER, "un controlador", "Controller", """\bextends\s+(?:\w+\\)*Controller\b"""),
        role(FileRole.SERVER, "lectura de parámetros de la petición (\$_GET/\$_POST)", "\$_", """\${'$'}_(?:GET|POST|REQUEST)\b"""),
        role(FileRole.DATABASE, "un modelo Eloquent", "Model", """\bextends\s+(?:\w+\\)*Model\b"""),
        role(FileRole.DATABASE, "migraciones o consultas de Laravel", "Schema::create|DB::", """Schema::create\s*\(|DB::(?:table|select|insert|update|delete|statement)\s*\("""),
        role(FileRole.DATABASE, "una conexión a base de datos (PDO/mysqli)", "PDO|mysqli", """\bnew\s+PDO\s*\(|\bmysqli_connect\s*\(|\bnew\s+mysqli\s*\("""),
        role(FileRole.AUTHENTICATION, "autenticación de Laravel", "Auth::", """Auth::(?:attempt|user|check|login|guard|logout)\s*\("""),
        role(FileRole.AUTHENTICATION, "contraseñas o sesiones de PHP", "password_|session_start", """\bpassword_(?:hash|verify)\s*\(|\bsession_start\s*\("""),
        entry(EntryPointKind.PHP_FRONT_CONTROLLER, "carga del autoload de Composer", "autoload.php", """vendor/autoload\.php"""),
    )

    private val CS = listOf(
        role(FileRole.SERVER, "un controlador o rutas de ASP.NET", "ApiController|Controller|app.Map", """\[ApiController\]|:\s*ControllerBase\b|:\s*Controller\b|\bapp\.Map(?:Get|Post|Put|Delete|Patch)\s*\("""),
        role(FileRole.DATABASE, "Entity Framework o ADO.NET", "DbContext|DbSet<|SqlConnection", """:\s*DbContext\b|\bDbSet<|\bnew\s+SqlConnection\s*\("""),
        role(FileRole.AUTHENTICATION, "autenticación de ASP.NET", "[Authorize|AddAuthentication|UserManager<|SignInManager<|JwtSecurityToken", """\[Authorize\b|\bAddAuthentication\s*\(|\bUserManager<|\bSignInManager<|\bJwtSecurityToken\b"""),
        role(FileRole.UI, "una ventana o página (WPF, WinForms, MAUI)", "Window|Form|UserControl|ContentPage", """:\s*(?:Window|Form|UserControl|ContentPage)\b"""),
        entry(EntryPointKind.MAIN_FUNCTION, "método Main", "Main", """\bstatic\s+(?:async\s+)?(?:void|int|Task(?:<int>)?)\s+Main\s*\("""),
        entry(EntryPointKind.SERVER_APP, "arranque de ASP.NET", "WebApplication.CreateBuilder|Host.CreateDefaultBuilder|CreateHostBuilder", """\bWebApplication\.CreateBuilder\s*\(|\bHost\.CreateDefaultBuilder\s*\(|\bCreateHostBuilder\s*\("""),
    )

    private val GO = listOf(
        role(FileRole.SERVER, "un servidor HTTP (net/http, Gin, Echo, Fiber…)", "http.HandleFunc|http.ListenAndServe|gin.|echo.New|mux.NewRouter|fiber.New", """\bhttp\.(?:HandleFunc|ListenAndServe)\s*\(|\bgin\.(?:Default|New)\s*\(|\becho\.New\s*\(|\bmux\.NewRouter\s*\(|\bfiber\.New\s*\("""),
        role(FileRole.DATABASE, "una conexión a base de datos", "sql.Open|gorm.Open|mongo.Connect", """\bsql\.Open\s*\(|\bgorm\.Open\s*\(|\bmongo\.Connect\s*\("""),
    )
    private val GO_PACKAGE_MAIN = Pattern.compile("""(?m)^package\s+main\b""")
    private val GO_FUNC_MAIN = Pattern.compile("""(?m)^func\s+main\s*\(\s*\)""")

    private val RB = listOf(
        role(FileRole.SERVER, "Rails o Sinatra", "ApplicationController|Rails.application.routes|Sinatra", """<\s*ApplicationController\b|Rails\.application\.routes|\bSinatra\b"""),
        role(FileRole.DATABASE, "ActiveRecord", "Active", """<\s*(?:ApplicationRecord|ActiveRecord::Base|ActiveRecord::Migration)"""),
        role(FileRole.AUTHENTICATION, "Devise o has_secure_password", "devise|has_secure_password", """\bdevise\b|\bhas_secure_password\b"""),
    )

    private val DART = listOf(
        role(FileRole.UI, "widgets de Flutter", "Widget", """\bextends\s+(?:StatelessWidget|StatefulWidget|State<)"""),
        role(FileRole.DATABASE, "almacenamiento de datos (sqflite, Firestore, Hive)", "sqflite|FirebaseFirestore|Hive.", """\bsqflite\b|\bFirebaseFirestore\b|\bHive\."""),
        role(FileRole.AUTHENTICATION, "Firebase Authentication", "FirebaseAuth", """\bFirebaseAuth\b"""),
        entry(EntryPointKind.MAIN_FUNCTION, "función main de Dart", "main", """(?m)^\s*(?:Future<void>\s+|void\s+)?main\s*\("""),
    )

    private val SWIFT = listOf(
        role(FileRole.UI, "interfaz SwiftUI o UIKit", "SwiftUI|UIViewController", """\bimport\s+SwiftUI\b|:\s*UIViewController\b"""),
        entry(EntryPointKind.MAIN_FUNCTION, "atributo @main o UIApplicationMain", "@main|UIApplicationMain", """(?m)^\s*@main\b|@UIApplicationMain\b|\bUIApplicationMain\s*\("""),
    )

    private val CPP = listOf(
        entry(EntryPointKind.MAIN_FUNCTION, "función main", "main", """(?m)^\s*(?:int|void|auto)\s+(?:w?main|_tmain)\s*\(|\bw?WinMain\s*\("""),
    )

    private val RUST = listOf(
        role(FileRole.SERVER, "un servidor web (Actix, Axum, Rocket, Warp)", "actix_web::|axum::|rocket::|warp::", """\b(?:actix_web|axum|rocket|warp)::"""),
        entry(EntryPointKind.MAIN_FUNCTION, "función main de Rust", "main", """(?m)^\s*(?:pub\s+)?(?:async\s+)?fn\s+main\s*\("""),
    )

    private val BY_EXTENSION: Map<String, List<Signal>> = buildMap {
        for (e in listOf("kt", "kts", "java", "scala", "groovy")) put(e, JVM)
        for (e in listOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "vue", "svelte")) put(e, JS)
        put("py", PY)
        put("php", PHP)
        put("cs", CS)
        put("go", GO)
        put("rb", RB)
        put("dart", DART)
        put("swift", SWIFT)
        for (e in listOf("c", "cc", "cpp", "cxx")) put(e, CPP)
        put("rs", RUST)
    }

    private val COMMENT_PREFIXES = listOf("//", "#", "*", "/*", "--", "<!--")

    fun scan(info: PathInfo, text: String, lineStarts: IntArray): List<SignalHit> {
        val signals = BY_EXTENSION[info.ext] ?: return emptyList()
        val hits = mutableListOf<SignalHit>()
        for (signal in signals) {
            if (!signal.mayMatch(text)) continue
            firstUncommentedMatch(signal.pattern, text, lineStarts)?.let { line ->
                hits += SignalHit(signal.role, signal.entry, signal.label, line)
            }
        }
        if (signals === JS && hits.any { it.role == FileRole.SERVER }) {
            firstUncommentedMatch(JS_LISTEN, text, lineStarts)?.let { line ->
                hits += SignalHit(null, EntryPointKind.SERVER_APP, "un servidor que escucha en un puerto (.listen)", line)
            }
        }
        if (signals === GO) {
            val pkg = firstUncommentedMatch(GO_PACKAGE_MAIN, text, lineStarts)
            val main = firstUncommentedMatch(GO_FUNC_MAIN, text, lineStarts)
            if (pkg != null && main != null) hits += SignalHit(null, EntryPointKind.MAIN_FUNCTION, "paquete main con func main()", main)
        }
        return hits
    }

    /** Línea (desde 1) de la primera coincidencia que no está en una línea comentada. */
    private fun firstUncommentedMatch(pattern: Pattern, text: String, lineStarts: IntArray): Int? {
        val m = pattern.matcher(text)
        var tries = 0
        while (m.find() && tries++ < MAX_TRIES) {
            val line = lineOf(lineStarts, m.start())
            val start = lineStarts[line - 1]
            var i = start
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (COMMENT_PREFIXES.none { text.startsWith(it, i) }) return line
        }
        return null
    }

    fun lineOf(lineStarts: IntArray, offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    /** Número máximo de coincidencias comentadas que se saltan antes de rendirse. */
    private const val MAX_TRIES = 200
}
