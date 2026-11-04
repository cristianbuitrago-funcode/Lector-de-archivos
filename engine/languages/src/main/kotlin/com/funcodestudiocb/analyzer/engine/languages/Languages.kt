package com.funcodestudiocb.analyzer.engine.languages

/** Cómo se escriben los comentarios y las cadenas de un lenguaje (para el recuento léxico). */
data class CommentSyntax(
    val line: List<String> = emptyList(),
    val block: List<Pair<String, String>> = emptyList(),
    val quotes: String = "\"'",
)

enum class LanguageKind { PROGRAMMING, MARKUP, DATA, PROSE }

/**
 * Un lenguaje reconocido. [grammar] es la gramática de tree-sitter (si la hay); [level] es el nivel de soporte de la
 * arquitectura (1 = máximo, 0 = solo inventario, líneas y secretos); [importTypes] son los nodos de importación.
 */
data class Language(
    val id: String,
    val name: String,
    val kind: LanguageKind,
    val level: Int,
    val grammar: String?,
    val syntax: CommentSyntax,
    val importTypes: List<String> = emptyList(),
)

/** Detección del lenguaje de un archivo por su nombre, su extensión, su línea `#!` y, si es ambiguo, su contenido. */
object Languages {
    private val C_STYLE = CommentSyntax(listOf("//"), listOf("/*" to "*/"))
    private val C_STYLE_TEMPLATES = CommentSyntax(listOf("//"), listOf("/*" to "*/"), "\"'`")
    private val HASH = CommentSyntax(listOf("#"))
    private val XML_LIKE = CommentSyntax(block = listOf("<!--" to "-->"), quotes = "")
    private val NONE = CommentSyntax(quotes = "")

    private val JS_IMPORTS = listOf("import_statement", "export_statement")

    val JAVASCRIPT = Language("javascript", "JavaScript", LanguageKind.PROGRAMMING, 1, "javascript", C_STYLE_TEMPLATES, JS_IMPORTS)
    val TYPESCRIPT = Language("typescript", "TypeScript", LanguageKind.PROGRAMMING, 1, "typescript", C_STYLE_TEMPLATES, JS_IMPORTS)
    val TSX = Language("tsx", "TypeScript (TSX)", LanguageKind.PROGRAMMING, 1, "tsx", C_STYLE_TEMPLATES, JS_IMPORTS)
    val PYTHON = Language("python", "Python", LanguageKind.PROGRAMMING, 1, "python", HASH, listOf("import_statement", "import_from_statement"))
    val JAVA = Language("java", "Java", LanguageKind.PROGRAMMING, 1, "java", C_STYLE, listOf("import_declaration"))
    val KOTLIN = Language("kotlin", "Kotlin", LanguageKind.PROGRAMMING, 1, "kotlin", C_STYLE, listOf("import"))
    val PHP = Language(
        "php", "PHP", LanguageKind.PROGRAMMING, 1, "php", CommentSyntax(listOf("//", "#"), listOf("/*" to "*/")),
        listOf("namespace_use_declaration", "include_expression", "include_once_expression", "require_expression", "require_once_expression"),
    )
    val CSHARP = Language("csharp", "C#", LanguageKind.PROGRAMMING, 2, "c_sharp", C_STYLE, listOf("using_directive"))
    val DART = Language("dart", "Dart", LanguageKind.PROGRAMMING, 2, "dart", C_STYLE, listOf("library_import", "library_export"))
    val HTML = Language("html", "HTML", LanguageKind.MARKUP, 2, "html", XML_LIKE)
    val CSS = Language("css", "CSS", LanguageKind.MARKUP, 2, "css", CommentSyntax(block = listOf("/*" to "*/")), listOf("import_statement"))
    val SQL = Language("sql", "SQL", LanguageKind.PROGRAMMING, 2, "sql", CommentSyntax(listOf("--"), listOf("/*" to "*/"), "'"))
    val GDSCRIPT = Language("gdscript", "GDScript", LanguageKind.PROGRAMMING, 2, "gdscript", HASH)
    val C = Language("c", "C", LanguageKind.PROGRAMMING, 3, "c", C_STYLE, listOf("preproc_include"))
    val CPP = Language("cpp", "C++", LanguageKind.PROGRAMMING, 3, "cpp", C_STYLE, listOf("preproc_include"))

    private fun lexical(id: String, name: String, syntax: CommentSyntax, kind: LanguageKind = LanguageKind.PROGRAMMING) =
        Language(id, name, kind, 0, null, syntax)

    val GO = lexical("go", "Go", C_STYLE_TEMPLATES)
    val RUST = lexical("rust", "Rust", C_STYLE)
    val RUBY = lexical("ruby", "Ruby", CommentSyntax(listOf("#"), listOf("=begin" to "=end")))
    val SWIFT = lexical("swift", "Swift", C_STYLE)
    val OBJC = lexical("objc", "Objective-C", C_STYLE)
    val MATLAB = lexical("matlab", "MATLAB", CommentSyntax(listOf("%"), listOf("%{" to "%}")))
    val SCALA = lexical("scala", "Scala", C_STYLE)
    val GROOVY = lexical("groovy", "Groovy", C_STYLE)
    val LUA = lexical("lua", "Lua", CommentSyntax(listOf("--"), listOf("--[[" to "]]")))
    val SHELL = lexical("shell", "Shell", HASH)
    val POWERSHELL = lexical("powershell", "PowerShell", CommentSyntax(listOf("#"), listOf("<#" to "#>")))
    val BATCH = lexical("batch", "Batch", CommentSyntax(listOf("::", "REM ", "rem "), quotes = "\""))
    val R = lexical("r", "R", HASH)
    val PERL = lexical("perl", "Perl", HASH)
    val ELIXIR = lexical("elixir", "Elixir", HASH)
    val HASKELL = lexical("haskell", "Haskell", CommentSyntax(listOf("--"), listOf("{-" to "-}")))
    val CLOJURE = lexical("clojure", "Clojure", CommentSyntax(listOf(";"), quotes = "\""))
    val FSHARP = lexical("fsharp", "F#", CommentSyntax(listOf("//"), listOf("(*" to "*)")))
    val VB = lexical("vb", "Visual Basic", CommentSyntax(listOf("'"), quotes = "\""))
    val SHADER = lexical("shader", "Shader", C_STYLE)
    val VUE = lexical("vue", "Vue", CommentSyntax(listOf("//"), listOf("<!--" to "-->", "/*" to "*/"), "\"'`"))
    val SVELTE = lexical("svelte", "Svelte", CommentSyntax(listOf("//"), listOf("<!--" to "-->", "/*" to "*/"), "\"'`"))
    val SCSS = lexical("scss", "SCSS/Sass/Less", C_STYLE, LanguageKind.MARKUP)
    val XML = lexical("xml", "XML", XML_LIKE, LanguageKind.MARKUP)
    val YAML = lexical("yaml", "YAML", HASH, LanguageKind.DATA)
    val TOML = lexical("toml", "TOML", HASH, LanguageKind.DATA)
    val JSON = lexical("json", "JSON", NONE, LanguageKind.DATA)
    val PROPERTIES = lexical("properties", "Properties/INI", CommentSyntax(listOf("#", ";"), quotes = ""), LanguageKind.DATA)
    val MARKDOWN = lexical("markdown", "Markdown", XML_LIKE, LanguageKind.PROSE)
    val TEXT = lexical("text", "Texto", NONE, LanguageKind.PROSE)
    val DOCKERFILE = lexical("dockerfile", "Dockerfile", HASH)
    val MAKEFILE = lexical("makefile", "Makefile", HASH)
    val CMAKE = lexical("cmake", "CMake", HASH)
    val GRADLE = lexical("gradle", "Gradle (Groovy)", C_STYLE)

    private val BY_EXTENSION: Map<String, Language> = buildMap {
        for (e in listOf("js", "mjs", "cjs", "jsx")) put(e, JAVASCRIPT)
        for (e in listOf("ts", "mts", "cts")) put(e, TYPESCRIPT)
        put("tsx", TSX)
        for (e in listOf("py", "pyw", "pyi")) put(e, PYTHON)
        put("java", JAVA)
        for (e in listOf("kt", "kts")) put(e, KOTLIN)
        for (e in listOf("php", "phtml")) put(e, PHP)
        for (e in listOf("cs", "csx")) put(e, CSHARP)
        put("dart", DART)
        for (e in listOf("html", "htm", "xhtml")) put(e, HTML)
        put("css", CSS)
        put("sql", SQL)
        put("gd", GDSCRIPT)
        put("c", C)
        for (e in listOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino", "inl", "ipp")) put(e, CPP)
        put("go", GO)
        put("rs", RUST)
        for (e in listOf("rb", "rake", "gemspec")) put(e, RUBY)
        put("swift", SWIFT)
        put("mm", OBJC)
        put("scala", SCALA)
        put("groovy", GROOVY)
        put("gradle", GRADLE)
        put("lua", LUA)
        for (e in listOf("sh", "bash", "zsh", "fish", "ksh")) put(e, SHELL)
        for (e in listOf("ps1", "psm1")) put(e, POWERSHELL)
        for (e in listOf("bat", "cmd")) put(e, BATCH)
        put("r", R)
        for (e in listOf("pl", "pm")) put(e, PERL)
        for (e in listOf("ex", "exs")) put(e, ELIXIR)
        put("hs", HASKELL)
        for (e in listOf("clj", "cljs", "cljc", "edn")) put(e, CLOJURE)
        for (e in listOf("fs", "fsx", "fsi")) put(e, FSHARP)
        put("vb", VB)
        for (e in listOf("shader", "hlsl", "glsl", "cginc", "compute", "gdshader", "wgsl", "frag", "vert")) put(e, SHADER)
        put("vue", VUE)
        put("svelte", SVELTE)
        for (e in listOf("scss", "sass", "less", "styl")) put(e, SCSS)
        for (e in listOf("xml", "xaml", "axaml", "fxml", "xsd", "xsl", "svg", "plist", "csproj", "vbproj", "fsproj", "props", "targets", "resx", "storyboard", "xib")) put(e, XML)
        for (e in listOf("yml", "yaml")) put(e, YAML)
        put("toml", TOML)
        for (e in listOf("json", "jsonc", "json5", "arb")) put(e, JSON)
        for (e in listOf("properties", "ini", "cfg", "conf", "env")) put(e, PROPERTIES)
        for (e in listOf("md", "markdown", "mdx")) put(e, MARKDOWN)
        for (e in listOf("txt", "rst", "adoc")) put(e, TEXT)
        put("cmake", CMAKE)
    }

    private val BY_NAME: Map<String, Language> = mapOf(
        "dockerfile" to DOCKERFILE, "makefile" to MAKEFILE, "gnumakefile" to MAKEFILE, "cmakelists.txt" to CMAKE,
        "jenkinsfile" to GROOVY, "gemfile" to RUBY, "rakefile" to RUBY, "podfile" to RUBY, "fastfile" to RUBY,
        "build.gradle" to GRADLE, "settings.gradle" to GRADLE, "vagrantfile" to RUBY,
    )

    private val SHEBANGS = listOf(
        "python" to PYTHON, "node" to JAVASCRIPT, "deno" to TYPESCRIPT, "bash" to SHELL, "zsh" to SHELL, "/sh" to SHELL,
        "ruby" to RUBY, "perl" to PERL, "php" to PHP, "pwsh" to POWERSHELL,
    )

    /** Todos los lenguajes con gramática de tree-sitter. */
    val withGrammar: List<Language> = listOf(JAVASCRIPT, TYPESCRIPT, TSX, PYTHON, JAVA, KOTLIN, PHP, CSHARP, DART, HTML, CSS, SQL, GDSCRIPT, C, CPP)

    /**
     * @param head primeros caracteres del archivo (para `#!` y para los casos ambiguos)
     * @param cppProject el proyecto tiene fuentes C++ (decide si un `.h` es C o C++)
     */
    fun detect(path: String, head: String, cppProject: Boolean): Language? {
        val name = path.substringAfterLast('/').lowercase()
        BY_NAME[name]?.let { return it }
        if (name.startsWith("dockerfile")) return DOCKERFILE
        if (name.endsWith(".gradle.kts")) return KOTLIN
        if (name.startsWith(".env")) return PROPERTIES
        val ext = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }.orEmpty()
        when (ext) {
            "h" -> return if (cppProject) CPP else C
            "m" -> return if (head.contains("#import") || head.contains("@interface") || head.contains("@implementation")) OBJC else MATLAB
            "ts" -> if (head.trimStart().startsWith("<?xml") || head.contains("<TS ")) return XML
        }
        BY_EXTENSION[ext]?.let { return it }
        if (ext.isEmpty() && head.startsWith("#!")) {
            val first = head.lineSequence().first()
            SHEBANGS.firstOrNull { (key, _) -> first.contains(key) }?.let { return it.second }
        }
        return null
    }

    /** Lenguaje por su identificador (para recuperar uno ya detectado). */
    fun byId(id: String): Language? = (BY_EXTENSION.values + BY_NAME.values + listOf(DOCKERFILE, KOTLIN, PROPERTIES, MATLAB, XML)).firstOrNull { it.id == id }
}
