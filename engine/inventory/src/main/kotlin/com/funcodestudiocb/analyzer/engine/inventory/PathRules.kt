package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileRole

/** Indicio de un rol, con su motivo redactado por el motor. */
internal data class RoleHint(
    val role: FileRole,
    val confidence: Confidence,
    val kind: EvidenceKind,
    val detail: String,
    val path: String? = null,
    val line: Int? = null,
)

/** Partes de una ruta del comprimido que usan las reglas. */
class PathInfo(val path: String) {
    val name: String = path.substringAfterLast('/')
    val lower: String = name.lowercase()

    /** Extensión en minúsculas; vacía para archivos sin extensión y para los que empiezan por punto sin otra (".gitignore"). */
    val ext: String = lower.lastIndexOf('.').let { if (it <= 0) "" else lower.substring(it + 1) }
    val stem: String = if (ext.isEmpty()) lower else lower.substring(0, lower.length - ext.length - 1)
    val directory: String = path.substringBeforeLast('/', "")
    val dirs: List<String> = if (directory.isEmpty()) emptyList() else directory.split('/')
    val lowerDirs: List<String> = dirs.map { it.lowercase() }
    val parent: String = lowerDirs.lastOrNull().orEmpty()
}

/** Reglas de rol por nombre, extensión y ubicación (sin leer el contenido). */
internal object PathRules {

    private fun hint(role: FileRole, detail: String, confidence: Confidence = Confidence.CONFIRMED, kind: EvidenceKind = EvidenceKind.FILE_NAME) =
        RoleHint(role, confidence, kind, detail)

    private fun conf(detail: String) = hint(FileRole.CONFIGURATION, detail)
    private fun deps(detail: String) = hint(FileRole.DEPENDENCIES, detail)
    private fun build(detail: String) = hint(FileRole.BUILD, detail)
    private fun doc(detail: String) = hint(FileRole.DOCUMENTATION, detail)

    /** Nombres de archivo estándar (en minúsculas). */
    private val EXACT_NAMES: Map<String, List<RoleHint>> = buildMap {
        // Dependencias y construcción
        put("package.json", listOf(deps("Manifiesto de dependencias de npm"), conf("Configuración del paquete npm (scripts y metadatos)")))
        for (n in listOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "npm-shrinkwrap.json")) put(n, listOf(deps("Versiones exactas de las dependencias de npm (archivo de bloqueo generado)")))
        for (n in listOf("build.gradle", "build.gradle.kts")) put(n, listOf(build("Script de construcción de Gradle"), deps("Declara las dependencias del módulo (Gradle)")))
        for (n in listOf("settings.gradle", "settings.gradle.kts")) put(n, listOf(build("Configuración de Gradle: módulos del proyecto")))
        put("gradle.properties", listOf(conf("Propiedades de Gradle")))
        put("local.properties", listOf(conf("Configuración local de la máquina (ruta del SDK de Android)")))
        put("libs.versions.toml", listOf(deps("Catálogo de versiones de dependencias de Gradle")))
        put("verification-metadata.xml", listOf(deps("Sumas de verificación de las dependencias de Gradle")))
        for (n in listOf("gradlew", "gradlew.bat")) put(n, listOf(build("Lanzador de Gradle (wrapper)")))
        for (n in listOf("mvnw", "mvnw.cmd")) put(n, listOf(build("Lanzador de Maven (wrapper)")))
        put("gradle-wrapper.properties", listOf(build("Versión de Gradle del wrapper")))
        put("pom.xml", listOf(deps("Manifiesto de dependencias de Maven"), build("Construcción con Maven")))
        for (n in listOf("requirements.txt", "requirements-dev.txt", "dev-requirements.txt", "constraints.txt")) put(n, listOf(deps("Dependencias de Python (pip)")))
        put("pyproject.toml", listOf(deps("Proyecto de Python: dependencias y construcción"), conf("Configuración de herramientas de Python")))
        for (n in listOf("setup.py", "setup.cfg")) put(n, listOf(deps("Paquete de Python: dependencias y empaquetado"), build("Empaquetado de Python")))
        for (n in listOf("pipfile", "pipfile.lock", "poetry.lock", "uv.lock")) put(n, listOf(deps("Dependencias de Python")))
        put("environment.yml", listOf(deps("Entorno de Conda (dependencias)")))
        put("composer.json", listOf(deps("Manifiesto de dependencias de PHP (Composer)")))
        put("composer.lock", listOf(deps("Versiones exactas de las dependencias de PHP (bloqueo generado)")))
        put("pubspec.yaml", listOf(deps("Manifiesto de Dart/Flutter: dependencias y recursos")))
        put("pubspec.lock", listOf(deps("Versiones exactas de las dependencias de Dart (bloqueo generado)")))
        put("gemfile", listOf(deps("Dependencias de Ruby (Bundler)")))
        put("gemfile.lock", listOf(deps("Versiones exactas de las dependencias de Ruby (bloqueo generado)")))
        put("go.mod", listOf(deps("Módulo de Go y sus dependencias")))
        put("go.sum", listOf(deps("Sumas de verificación de las dependencias de Go (generado)")))
        put("cargo.toml", listOf(deps("Manifiesto de Rust (Cargo): dependencias y construcción")))
        put("cargo.lock", listOf(deps("Versiones exactas de las dependencias de Rust (bloqueo generado)")))
        put("podfile", listOf(deps("Dependencias de iOS (CocoaPods)")))
        put("podfile.lock", listOf(deps("Versiones exactas de CocoaPods (bloqueo generado)")))
        put("package.swift", listOf(deps("Manifiesto de Swift Package Manager")))
        put("packages.config", listOf(deps("Dependencias de NuGet (.NET)")))
        put("packages.lock.json", listOf(deps("Versiones exactas de NuGet (bloqueo generado)")))
        put("directory.build.props", listOf(build("Propiedades de construcción comunes de .NET")))
        put("vcpkg.json", listOf(deps("Dependencias de C/C++ (vcpkg)")))
        put("conanfile.txt", listOf(deps("Dependencias de C/C++ (Conan)")))
        put("cmakelists.txt", listOf(build("Construcción con CMake")))
        put("makefile", listOf(build("Construcción con Make")))
        put("dockerfile", listOf(build("Imagen de contenedor (Docker)"), conf("Configuración del contenedor")))
        put(".dockerignore", listOf(conf("Archivos que Docker ignora")))
        for (n in listOf("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")) put(n, listOf(conf("Servicios de Docker Compose"), build("Despliegue con Docker Compose")))
        put("jenkinsfile", listOf(build("Integración continua (Jenkins)")))
        put(".gitlab-ci.yml", listOf(build("Integración continua (GitLab)")))
        put("azure-pipelines.yml", listOf(build("Integración continua (Azure Pipelines)")))
        put("procfile", listOf(conf("Procesos de despliegue (Heroku y similares)")))
        put("artisan", listOf(build("Consola de Laravel")))
        put("manage.py", listOf(build("Consola de gestión de Django")))
        // Configuración
        put("androidmanifest.xml", listOf(conf("Manifiesto de Android: componentes, permisos y puntos de entrada")))
        put("project.godot", listOf(conf("Configuración del proyecto Godot")))
        put("projectversion.txt", listOf(conf("Versión del editor de Unity")))
        put("google-services.json", listOf(conf("Configuración de Firebase para Android (contiene identificadores del proyecto)")))
        put("googleservice-info.plist", listOf(conf("Configuración de Firebase para iOS")))
        put("firebase.json", listOf(conf("Configuración de Firebase")))
        put(".firebaserc", listOf(conf("Proyecto de Firebase asociado")))
        put("proguard-rules.pro", listOf(conf("Reglas de ofuscación y reducción (R8/ProGuard)")))
        put("network_security_config.xml", listOf(conf("Configuración de seguridad de red de Android")))
        for (n in listOf("tsconfig.json", "jsconfig.json")) put(n, listOf(conf("Configuración del compilador de TypeScript/JavaScript")))
        put("angular.json", listOf(conf("Configuración del espacio de trabajo de Angular")))
        put("nx.json", listOf(conf("Configuración del monorepo Nx")))
        put("turbo.json", listOf(conf("Configuración de Turborepo")))
        put("lerna.json", listOf(conf("Configuración del monorepo Lerna")))
        put(".editorconfig", listOf(conf("Estilo de código para editores")))
        put(".gitignore", listOf(conf("Archivos que Git ignora")))
        put(".gitattributes", listOf(conf("Atributos de Git")))
        put(".gitmodules", listOf(conf("Submódulos de Git")))
        put(".npmrc", listOf(conf("Configuración de npm (puede contener tokens)")))
        put(".nvmrc", listOf(conf("Versión de Node.js")))
        put(".node-version", listOf(conf("Versión de Node.js")))
        put(".python-version", listOf(conf("Versión de Python")))
        put(".ruby-version", listOf(conf("Versión de Ruby")))
        put(".htaccess", listOf(conf("Configuración del servidor web Apache")))
        put("web.config", listOf(conf("Configuración de IIS / ASP.NET")))
        put("nginx.conf", listOf(conf("Configuración del servidor web nginx")))
        put("settings.py", listOf(conf("Configuración de Django/Python")))
        put("vercel.json", listOf(conf("Configuración de despliegue (Vercel)")))
        put("netlify.toml", listOf(conf("Configuración de despliegue (Netlify)")))
        put("app.json", listOf(conf("Configuración de la aplicación (Expo/React Native u otra)")))
        put("manifest.json", listOf(conf("Manifiesto (aplicación web, extensión o paquetes de Unity)")))
        put("info.plist", listOf(conf("Configuración de la app de iOS/macOS")))
        put("alembic.ini", listOf(conf("Configuración de migraciones de BD (Alembic)"), hint(FileRole.DATABASE, "Migraciones de base de datos (Alembic)")))
        put("schema.prisma", listOf(hint(FileRole.DATABASE, "Esquema de la base de datos (Prisma)")))
        put("ormconfig.json", listOf(hint(FileRole.DATABASE, "Configuración del ORM (TypeORM)"), conf("Configuración de la conexión a la BD")))
        // Documentación
        for (n in listOf("readme", "changelog", "license", "licence", "contributing", "authors", "notice", "security", "code_of_conduct", "history", "copying")) {
            put(n, listOf(doc("Documentación del proyecto")))
        }
    }

    /** Nombres que empiezan por un prefijo conocido (en minúsculas). */
    private val NAME_PREFIXES: List<Pair<String, List<RoleHint>>> = listOf(
        ".env" to listOf(conf("Variables de entorno (suelen contener secretos)")),
        ".eslintrc" to listOf(conf("Reglas del analizador de estilo ESLint")),
        ".prettierrc" to listOf(conf("Formato de código (Prettier)")),
        ".babelrc" to listOf(conf("Configuración de Babel")),
        ".stylelintrc" to listOf(conf("Reglas de estilo CSS (Stylelint)")),
        "webpack.config" to listOf(build("Empaquetado con webpack")),
        "vite.config" to listOf(build("Empaquetado con Vite"), conf("Configuración de Vite")),
        "rollup.config" to listOf(build("Empaquetado con Rollup")),
        "babel.config" to listOf(conf("Configuración de Babel")),
        "jest.config" to listOf(hint(FileRole.TEST, "Configuración de pruebas (Jest)"), conf("Configuración de Jest")),
        "vitest.config" to listOf(hint(FileRole.TEST, "Configuración de pruebas (Vitest)")),
        "karma.conf" to listOf(hint(FileRole.TEST, "Configuración de pruebas (Karma)")),
        "playwright.config" to listOf(hint(FileRole.TEST, "Configuración de pruebas (Playwright)")),
        "cypress.config" to listOf(hint(FileRole.TEST, "Configuración de pruebas (Cypress)")),
        "next.config" to listOf(conf("Configuración de Next.js")),
        "nuxt.config" to listOf(conf("Configuración de Nuxt")),
        "svelte.config" to listOf(conf("Configuración de Svelte")),
        "astro.config" to listOf(conf("Configuración de Astro")),
        "vue.config" to listOf(conf("Configuración de Vue CLI")),
        "tailwind.config" to listOf(conf("Configuración de Tailwind CSS"), hint(FileRole.UI, "Estilos (Tailwind CSS)", Confidence.PROBABLE)),
        "postcss.config" to listOf(conf("Configuración de PostCSS")),
        "tsconfig." to listOf(conf("Configuración del compilador de TypeScript")),
        "appsettings" to listOf(conf("Configuración de la aplicación .NET")),
        "application" to emptyList(), // application.yml/properties: se resuelve abajo según la extensión
        "docker-compose" to listOf(conf("Servicios de Docker Compose")),
        "knexfile" to listOf(hint(FileRole.DATABASE, "Conexión y migraciones de BD (Knex)")),
        "gulpfile" to listOf(build("Tareas de construcción (Gulp)")),
        "gruntfile" to listOf(build("Tareas de construcción (Grunt)")),
        "rakefile" to listOf(build("Tareas de construcción (Rake)")),
    )

    /** Lenguaje de cada extensión de código fuente. */
    val LANGUAGES: Map<String, String> = buildMap {
        for (e in listOf("kt", "kts")) put(e, "Kotlin")
        put("java", "Java")
        for (e in listOf("js", "mjs", "cjs")) put(e, "JavaScript")
        put("jsx", "JavaScript (JSX)")
        for (e in listOf("ts", "mts", "cts")) put(e, "TypeScript")
        put("tsx", "TypeScript (TSX)")
        for (e in listOf("py", "pyw")) put(e, "Python")
        put("php", "PHP")
        put("rb", "Ruby")
        put("go", "Go")
        put("rs", "Rust")
        for (e in listOf("c", "h")) put(e, "C")
        for (e in listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx", "ino", "inl")) put(e, "C++")
        put("cs", "C#")
        put("dart", "Dart")
        put("swift", "Swift")
        put("m", "Objective-C")
        put("mm", "Objective-C++")
        put("scala", "Scala")
        for (e in listOf("groovy", "gradle")) put(e, "Groovy")
        put("lua", "Lua")
        put("gd", "GDScript")
        put("r", "R")
        for (e in listOf("pl", "pm")) put(e, "Perl")
        for (e in listOf("ex", "exs")) put(e, "Elixir")
        put("erl", "Erlang")
        put("hs", "Haskell")
        for (e in listOf("clj", "cljs")) put(e, "Clojure")
        for (e in listOf("fs", "fsx")) put(e, "F#")
        put("vb", "Visual Basic")
        for (e in listOf("sh", "bash", "zsh", "fish")) put(e, "Shell")
        put("ps1", "PowerShell")
        for (e in listOf("bat", "cmd")) put(e, "Batch")
        put("vue", "Vue")
        put("svelte", "Svelte")
        for (e in listOf("shader", "hlsl", "glsl", "cginc", "compute", "gdshader", "wgsl", "frag", "vert")) put(e, "Shader")
        put("sql", "SQL")
        put("aidl", "AIDL (interfaz entre procesos de Android)")
        put("proto", "Protocol Buffers")
        put("graphql", "GraphQL")
        put("gql", "GraphQL")
    }

    private val SCRIPT_LANGUAGES = setOf("Shell", "PowerShell", "Batch")

    private fun ext(role: FileRole, detail: String, confidence: Confidence = Confidence.CONFIRMED) = RoleHint(role, confidence, EvidenceKind.EXTENSION, detail)

    private val EXTENSIONS: Map<String, List<RoleHint>> = buildMap {
        for (e in listOf("jsx", "tsx")) put(e, listOf(ext(FileRole.UI, "Componente de interfaz (JSX)", Confidence.PROBABLE)))
        for (e in listOf("vue", "svelte")) put(e, listOf(ext(FileRole.UI, "Componente de interfaz")))
        for (e in listOf("html", "htm", "xhtml")) put(e, listOf(ext(FileRole.UI, "Página HTML")))
        for (e in listOf("css", "scss", "sass", "less", "styl")) put(e, listOf(ext(FileRole.UI, "Hoja de estilos")))
        for (e in listOf("ejs", "hbs", "handlebars", "mustache", "pug", "jade", "twig", "njk", "liquid", "jinja", "jinja2", "j2", "erb", "cshtml", "razor", "jsp", "aspx", "gohtml", "tmpl")) {
            put(e, listOf(ext(FileRole.UI, "Plantilla de vista", Confidence.PROBABLE)))
        }
        for (e in listOf("xaml", "axaml")) put(e, listOf(ext(FileRole.UI, "Interfaz XAML")))
        put("fxml", listOf(ext(FileRole.UI, "Interfaz JavaFX")))
        put("ui", listOf(ext(FileRole.UI, "Interfaz de Qt Designer", Confidence.PROBABLE)))
        for (e in listOf("storyboard", "xib")) put(e, listOf(ext(FileRole.UI, "Interfaz de iOS/macOS")))
        for (e in listOf("uxml", "uss")) put(e, listOf(ext(FileRole.UI, "Interfaz de Unity (UI Toolkit)")))
        for (e in listOf("tscn", "scn")) put(e, listOf(ext(FileRole.UI, "Escena de Godot (objetos e interfaz del juego)")))
        put("tres", listOf(ext(FileRole.RESOURCE, "Recurso de Godot")))
        put("unity", listOf(ext(FileRole.UI, "Escena de Unity")))
        put("prefab", listOf(ext(FileRole.UI, "Prefab de Unity (objeto reutilizable de una escena)")))
        put("umap", listOf(ext(FileRole.UI, "Nivel de Unreal Engine (binario: no se analiza)")))
        put("uasset", listOf(ext(FileRole.MEDIA, "Recurso de Unreal Engine (binario: no se analiza)")))
        for (e in listOf("mat", "anim", "controller", "asset", "physicmaterial", "mixer", "spriteatlas", "shadergraph", "overridecontroller")) {
            put(e, listOf(ext(FileRole.RESOURCE, "Recurso de Unity", Confidence.PROBABLE)))
        }
        put("meta", listOf(ext(FileRole.RESOURCE, "Metadatos de Unity (identificador del recurso)", Confidence.PROBABLE)))
        put("sql", listOf(ext(FileRole.DATABASE, "Script SQL")))
        for (e in listOf("sqlite", "sqlite3", "db", "db3", "mdb", "accdb", "realm")) put(e, listOf(ext(FileRole.DATABASE, "Base de datos incluida en el proyecto"), ext(FileRole.DATA, "Datos de una base de datos")))
        put("prisma", listOf(ext(FileRole.DATABASE, "Esquema de base de datos (Prisma)")))
        put("dbml", listOf(ext(FileRole.DATABASE, "Esquema de base de datos (DBML)")))
        put("json", listOf(ext(FileRole.DATA, "Datos estructurados (JSON)", Confidence.PROBABLE)))
        for (e in listOf("yaml", "yml")) put(e, listOf(ext(FileRole.CONFIGURATION, "Configuración (YAML)", Confidence.PROBABLE)))
        put("toml", listOf(ext(FileRole.CONFIGURATION, "Configuración (TOML)", Confidence.PROBABLE)))
        for (e in listOf("ini", "cfg", "conf", "properties")) put(e, listOf(ext(FileRole.CONFIGURATION, "Archivo de configuración")))
        put("plist", listOf(ext(FileRole.CONFIGURATION, "Lista de propiedades de Apple", Confidence.PROBABLE)))
        for (e in listOf("csv", "tsv", "xlsx", "xls", "ods", "parquet", "ndjson", "jsonl", "geojson", "arff", "feather")) put(e, listOf(ext(FileRole.DATA, "Datos tabulares o estructurados")))
        for (e in listOf("md", "markdown", "rst", "adoc", "asciidoc", "txt", "rtf")) put(e, listOf(ext(FileRole.DOCUMENTATION, "Texto o documentación", Confidence.PROBABLE)))
        for (e in listOf("pdf", "doc", "docx", "odt", "ppt", "pptx")) put(e, listOf(ext(FileRole.DOCUMENTATION, "Documento")))
        for (e in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "tif", "tiff", "psd", "xcf", "heic", "avif", "svg", "ai", "eps")) put(e, listOf(ext(FileRole.MEDIA, "Imagen")))
        for (e in listOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "mid", "midi", "opus", "wma", "aif", "aiff")) put(e, listOf(ext(FileRole.MEDIA, "Audio")))
        for (e in listOf("mp4", "mov", "avi", "mkv", "webm", "wmv", "m4v", "flv")) put(e, listOf(ext(FileRole.MEDIA, "Vídeo")))
        for (e in listOf("ttf", "otf", "woff", "woff2", "eot", "fnt")) put(e, listOf(ext(FileRole.MEDIA, "Fuente tipográfica")))
        for (e in listOf("fbx", "blend", "gltf", "glb", "dae", "3ds", "max", "ma", "mb", "stl", "ply", "usdz", "usd")) put(e, listOf(ext(FileRole.MEDIA, "Modelo 3D")))
        for (e in listOf("tga", "dds", "exr", "hdr", "ktx", "ktx2", "astc", "pvr")) put(e, listOf(ext(FileRole.MEDIA, "Textura")))
        for (e in listOf("class", "o", "pyc", "pyo", "dex", "so", "dll", "exe", "dylib", "a", "lib", "jar", "aar", "apk", "aab", "ipa", "wasm", "pdb", "ilk", "elf", "bin", "msi", "deb", "rpm", "appx", "xpi")) {
            put(e, listOf(ext(FileRole.BINARY, "Binario o artefacto compilado (no se analiza su contenido)")))
        }
        for (e in listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "cab", "iso")) put(e, listOf(ext(FileRole.BINARY, "Comprimido dentro del proyecto (no se expande)")))
        for (e in listOf("keystore", "jks", "p12", "pfx", "pem", "key", "crt", "cer", "der", "p8", "mobileprovision", "asc", "gpg", "ppk")) {
            put(e, listOf(ext(FileRole.AUTHENTICATION, "Material criptográfico: claves o certificados (sensible)"), ext(FileRole.CONFIGURATION, "Certificado o almacén de claves")))
        }
        for (e in listOf("resx", "strings", "po", "pot", "arb", "xliff", "xlf", "stringsdict")) put(e, listOf(ext(FileRole.RESOURCE, "Textos traducibles de la interfaz")))
        put("iml", listOf(ext(FileRole.CONFIGURATION, "Configuración del módulo en IntelliJ IDEA / Android Studio")))
        for (e in listOf("sln")) put(e, listOf(ext(FileRole.BUILD, "Solución de Visual Studio")))
        for (e in listOf("csproj", "fsproj", "vbproj", "vcxproj")) put(e, listOf(ext(FileRole.BUILD, "Proyecto de .NET/Visual Studio"), ext(FileRole.DEPENDENCIES, "Declara las dependencias (NuGet) del proyecto")))
        put("uproject", listOf(ext(FileRole.CONFIGURATION, "Proyecto de Unreal Engine (módulos y plugins)")))
        put("uplugin", listOf(ext(FileRole.CONFIGURATION, "Plugin de Unreal Engine")))
        put("pro", listOf(ext(FileRole.CONFIGURATION, "Proyecto de Qt o reglas de ProGuard", Confidence.PROBABLE)))
        put("cmake", listOf(ext(FileRole.BUILD, "Módulo de CMake")))
        put("mk", listOf(ext(FileRole.BUILD, "Reglas de Make")))
        put("lock", listOf(ext(FileRole.DEPENDENCIES, "Archivo de bloqueo de dependencias (generado)", Confidence.PROBABLE)))
        put("log", listOf(ext(FileRole.DATA, "Registro de ejecución (log)")))
    }

    private fun loc(role: FileRole, detail: String) = RoleHint(role, Confidence.PROBABLE, EvidenceKind.LOCATION, detail)

    private val TEST_DIRS = setOf("test", "tests", "__tests__", "spec", "specs", "androidtest", "testing", "e2e", "cypress", "integration_tests", "unittest", "unittests", "testfixtures")
    private val DB_DIRS = setOf("migrations", "migration", "db", "database", "databases", "seeds", "seeders", "schema", "schemas", "entities", "entity", "dao", "daos", "repositories", "repository", "persistence", "prisma")
    private val AUTH_DIRS = setOf("auth", "authentication", "authorization", "login", "oauth", "identity", "security", "accounts")
    private val SERVER_DIRS = setOf("server", "backend", "api", "apis", "routes", "router", "routers", "controllers", "controller", "handlers", "middleware", "middlewares", "endpoints", "lambda", "lambdas", "serverless")
    private val UI_DIRS = setOf("components", "component", "views", "view", "pages", "screens", "screen", "ui", "widgets", "templates", "layouts", "styles", "css", "scss", "sass", "theme", "themes", "fragments", "activities", "composables", "frontend", "presentation")
    private val DOC_DIRS = setOf("docs", "doc", "documentation", "wiki", "manual")
    private val CONFIG_DIRS = setOf("config", "configs", "conf", "configuration", "settings", ".vscode", "environments")
    private val BUILD_DIRS = setOf("scripts", "tools", "ci", ".github", ".circleci", ".gitlab", "buildsrc", "build-logic")
    private val RESOURCE_DIRS = setOf("assets", "resources", "res", "static", "public", "media", "images", "img", "icons", "fonts", "sounds", "audio", "sprites", "textures", "raw", "locales", "locale", "i18n", "l10n", "lang", "translations", "streamingassets")
    private val DATA_DIRS = setOf("data", "datasets", "fixtures", "samples", "sample-data", "seed-data")

    /** Última palabra del nombre (UserLoginController → controller). */
    private val UI_SUFFIXES = setOf("activity", "fragment", "screen", "page", "view", "component", "widget", "dialog", "adapter", "holder", "layout")
    private val SERVER_SUFFIXES = setOf("controller", "handler", "router", "routes", "route", "endpoint", "endpoints", "middleware", "resolver")
    private val DB_SUFFIXES = setOf("repository", "repo", "dao", "entity", "migration", "database", "seeder", "schema")

    /** Extensiones cuyo indicio es genérico y se omite si el nombre ya es conocido. */
    private val GENERIC_EXTENSIONS = setOf("json", "yaml", "yml", "toml", "txt", "md", "xml", "properties", "ini", "cfg", "conf", "lock", "log", "plist")
    private val AUTH_WORDS = setOf("auth", "authentication", "authenticator", "authorization", "login", "logout", "signin", "signup", "oauth", "oauth2", "jwt", "passport", "credential", "credentials", "password", "sso", "saml", "otp", "mfa", "biometric", "biometrics", "keychain")

    private val DOC_NAMES = setOf("readme", "changelog", "license", "licence", "contributing", "authors", "notice", "security", "code_of_conduct", "history", "copying")

    private val GENERATED_LOCKS = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json", "composer.lock", "cargo.lock", "poetry.lock",
        "pipfile.lock", "gemfile.lock", "pubspec.lock", "go.sum", "podfile.lock", "packages.lock.json", "uv.lock",
    )

    fun isLockFile(info: PathInfo): Boolean = info.lower in GENERATED_LOCKS

    /** Nombre de archivo estándar (manifiestos, licencias, README…): sus copias en varios módulos son normales. */
    fun isWellKnownName(info: PathInfo): Boolean =
        info.lower in EXACT_NAMES || info.stem.substringBefore('.').substringBefore('-') in DOC_NAMES

    fun isSourceExtension(ext: String): Boolean = ext in LANGUAGES

    fun hints(info: PathInfo, kind: FileKind): List<RoleHint> {
        val out = mutableListOf<RoleHint>()
        val baseName = info.stem.substringBefore('.')
        EXACT_NAMES[info.lower]?.let { out += it }
        if (info.ext in setOf("md", "txt", "rst", "adoc", "") && baseName in DOC_NAMES) {
            out += doc("Documentación del proyecto")
        }
        NAME_PREFIXES.forEach { (prefix, hints) -> if (info.lower.startsWith(prefix)) out += hints }
        if (info.lower.startsWith("application") && info.ext in setOf("yml", "yaml", "properties")) out += conf("Configuración de la aplicación (Spring u otro framework)")
        if (info.lower.startsWith("dockerfile") && info.lower != "dockerfile") out += build("Imagen de contenedor (Docker)")
        // Si el nombre ya dice qué es, no se añade el indicio genérico de la extensión ("datos JSON" para package.json).
        val knownByName = out.isNotEmpty()

        val language = LANGUAGES[info.ext]
        when {
            language == null || language == "SQL" -> {}
            language in SCRIPT_LANGUAGES -> {
                out += ext(FileRole.BUILD, "Script de $language", Confidence.PROBABLE)
                out += ext(FileRole.SOURCE, "Código de $language")
            }
            else -> out += ext(FileRole.SOURCE, "Código fuente $language")
        }
        if (info.ext.isNotEmpty() && !(knownByName && info.ext in GENERIC_EXTENSIONS)) {
            EXTENSIONS[info.ext]?.let { hints -> out += hints.map { adjustByKind(it, kind) } }
        }
        if (info.lower.endsWith(".blade.php")) out += ext(FileRole.UI, "Plantilla de vista (Blade)")
        if (info.lower.endsWith(".d.ts")) out += ext(FileRole.SOURCE, "Declaraciones de tipos de TypeScript")

        locationHints(info, language != null, out)
        nameHints(info, language != null, out)
        return out
    }

    /** Un archivo con extensión de imagen que en realidad es texto (o al revés) baja a "probable". */
    private fun adjustByKind(hint: RoleHint, kind: FileKind): RoleHint {
        if (hint.role != FileRole.MEDIA && hint.role != FileRole.BINARY) return hint
        val consistent = kind == FileKind.BINARY || kind == FileKind.ARCHIVE || kind == FileKind.UNKNOWN ||
            (kind == FileKind.TEXT && hint.detail == "Imagen")
        return if (consistent) hint else hint.copy(confidence = Confidence.PROBABLE)
    }

    private fun locationHints(info: PathInfo, isCode: Boolean, out: MutableList<RoleHint>) {
        val dirs = info.lowerDirs
        // Recursos de Android: res/<tipo>[-calificadores]/archivo
        val resIndex = dirs.lastIndexOf("res")
        if (resIndex >= 0 && resIndex == dirs.size - 2) {
            val type = dirs.last().substringBefore('-')
            when (type) {
                "layout" -> out += loc(FileRole.UI, "Diseño de pantalla de Android (res/layout)")
                "menu", "navigation" -> out += loc(FileRole.UI, "Menú o navegación de Android (res/$type)")
                "anim", "animator", "transition" -> out += loc(FileRole.UI, "Animación de Android (res/$type)")
                "values" -> out += loc(FileRole.RESOURCE, "Recursos de Android: textos, colores, estilos (res/values)")
                "drawable", "mipmap" -> out += loc(FileRole.MEDIA, "Imagen o icono de Android (res/$type)")
                "font" -> out += loc(FileRole.MEDIA, "Fuente de Android (res/font)")
                "xml" -> out += loc(FileRole.CONFIGURATION, "Configuración XML de Android (res/xml)")
                "raw" -> out += loc(FileRole.RESOURCE, "Recurso sin procesar de Android (res/raw)")
            }
        }
        fun first(set: Set<String>): String? = dirs.firstOrNull { it in set }
        first(TEST_DIRS)?.let { out += loc(FileRole.TEST, "Está en una carpeta de pruebas ($it)") }
        first(DB_DIRS)?.let { out += loc(FileRole.DATABASE, "Está en una carpeta de base de datos ($it)") }
        first(AUTH_DIRS)?.let { out += loc(FileRole.AUTHENTICATION, "Está en una carpeta de autenticación ($it)") }
        first(SERVER_DIRS)?.let { out += loc(FileRole.SERVER, "Está en una carpeta de servidor o API ($it)") }
        if (resIndex < 0 || resIndex != dirs.size - 2) first(UI_DIRS)?.let { out += loc(FileRole.UI, "Está en una carpeta de interfaz ($it)") }
        first(DOC_DIRS)?.let { out += loc(FileRole.DOCUMENTATION, "Está en una carpeta de documentación ($it)") }
        first(CONFIG_DIRS)?.let { out += loc(FileRole.CONFIGURATION, "Está en una carpeta de configuración ($it)") }
        if (dirs.size >= 2 && dirs[dirs.size - 2] == ".github" && dirs.last() == "workflows") out += loc(FileRole.BUILD, "Flujo de integración continua (GitHub Actions)")
        else first(BUILD_DIRS)?.let { if (!isCode || info.ext in setOf("sh", "bat", "cmd", "ps1", "py", "js", "mjs", "ts", "kts", "gradle")) out += loc(FileRole.BUILD, "Está en una carpeta de scripts o herramientas ($it)") }
        if (!isCode) {
            first(RESOURCE_DIRS)?.let { out += loc(FileRole.RESOURCE, "Está en una carpeta de recursos ($it)") }
            first(DATA_DIRS)?.let { out += loc(FileRole.DATA, "Está en una carpeta de datos ($it)") }
        }
    }

    private fun nameHints(info: PathInfo, isCode: Boolean, out: MutableList<RoleHint>) {
        fun name(role: FileRole, detail: String) = RoleHint(role, Confidence.PROBABLE, EvidenceKind.FILE_NAME, detail)
        val stem = info.stem
        val words = words(info.name.substringBefore('.'))
        val last = words.lastOrNull().orEmpty()
        val testName = last in setOf("test", "tests", "spec", "specs") || words.firstOrNull() == "test" ||
            info.lower.contains(".test.") || info.lower.contains(".spec.") || stem == "conftest"
        if (testName && (isCode || info.ext.isEmpty())) out += name(FileRole.TEST, "Nombre típico de un archivo de pruebas")
        if (!isCode) return
        if (words.takeLast(2) == listOf("view", "model")) {
            out += name(FileRole.UI, "Lógica de presentación (ViewModel)")
        } else if (last in UI_SUFFIXES) {
            out += name(FileRole.UI, "Nombre típico de un elemento de interfaz (…$last)")
        }
        if (last in SERVER_SUFFIXES) out += name(FileRole.SERVER, "Nombre típico de servidor o API (…$last)")
        if (last in DB_SUFFIXES || words.takeLast(2) == listOf("db", "helper")) out += name(FileRole.DATABASE, "Nombre típico de acceso a datos (…$last)")
        if (info.ext == "py") {
            when (stem) {
                "urls", "views", "wsgi", "asgi", "routes" -> out += name(FileRole.SERVER, "Archivo típico de un servidor web en Python ($stem.py)")
                "models" -> out += name(FileRole.DATABASE, "Modelos de datos (models.py)")
            }
        }
        val authWord = words.firstOrNull { it in AUTH_WORDS }
            ?: if (words.zipWithNext().any { (a, b) -> a == "sign" && (b == "in" || b == "up") || a == "log" && (b == "in" || b == "out") }) "sign-in" else null
        if (authWord != null) out += name(FileRole.AUTHENTICATION, "Nombre relacionado con la autenticación ($authWord)")
    }

    /** Palabras de un nombre: `UserLoginController` → [user, login, controller]; `auth_service` → [auth, service]. */
    fun words(name: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) out += current.toString().lowercase()
            current.setLength(0)
        }
        for (i in name.indices) {
            val ch = name[i]
            when {
                !ch.isLetterOrDigit() -> flush()
                ch.isUpperCase() && i > 0 && (name[i - 1].isLowerCase() || name[i - 1].isDigit()) -> {
                    flush()
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        flush()
        return out
    }
}
