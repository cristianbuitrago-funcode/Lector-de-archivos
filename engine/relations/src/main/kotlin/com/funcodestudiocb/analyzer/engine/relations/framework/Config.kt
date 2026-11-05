package com.funcodestudiocb.analyzer.engine.relations.framework

/**
 * Claves de configuración: dónde se definen y quién las lee. Solo se guardan los nombres de las claves, nunca sus
 * valores (pueden ser secretos; además el texto ya llega redactado).
 */
internal object Config {

    /** Variables que pone el sistema o la plataforma, no el proyecto. */
    val STANDARD_ENV = setOf(
        "NODE_ENV", "PORT", "HOME", "PATH", "USER", "PWD", "CI", "TZ", "LANG", "HOSTNAME", "TERM", "SHELL", "TMPDIR", "TEMP", "TMP", "APPDATA",
        "USERPROFILE", "JAVA_HOME", "ANDROID_HOME", "VERCEL", "VERCEL_URL", "NETLIFY", "GITHUB_ACTIONS", "DYNO", "K_SERVICE", "AWS_REGION",
        "AWS_LAMBDA_FUNCTION_NAME", "ASPNETCORE_ENVIRONMENT", "DOTNET_ENVIRONMENT", "FLASK_ENV", "DJANGO_SETTINGS_MODULE", "PYTHONPATH",
        "BASE_URL", "MODE", "DEV", "PROD", "SSR",
    )

    /** Campos que Gradle genera siempre en BuildConfig. */
    private val BUILD_CONFIG_DEFAULTS = setOf("DEBUG", "APPLICATION_ID", "BUILD_TYPE", "FLAVOR", "VERSION_CODE", "VERSION_NAME", "LIBRARY_PACKAGE_NAME")

    fun extract(src: Src, out: FrameworkFacts) {
        definitions(src, out)
        uses(src, out)
    }

    private fun isEnvFile(name: String) = name == ".env" || name.startsWith(".env.") || name.endsWith(".env")

    // ------------------------------------------------------------------ definiciones

    private fun definitions(src: Src, out: FrameworkFacts) {
        val name = src.info.lower
        when {
            isEnvFile(name) -> envFile(src, out)
            name.startsWith("application") && name.endsWith(".properties") -> properties(src, out)
            name.startsWith("application") && (name.endsWith(".yml") || name.endsWith(".yaml")) || name.startsWith("bootstrap") && name.endsWith(".yml") -> yaml(src, out)
            name.startsWith("appsettings") && name.endsWith(".json") -> json(src, out)
            name.startsWith("docker-compose") || name == "compose.yml" || name == "compose.yaml" -> compose(src, out)
            name == "dockerfile" || name.endsWith(".dockerfile") -> dockerfile(src, out)
            name == "build.gradle" || name == "build.gradle.kts" -> buildConfigFields(src, out)
            src.language == "php" && src.info.lowerDirs.lastOrNull() == "config" -> laravelConfig(src, out)
            src.language == "python" && (name == "settings.py" || src.info.lowerDirs.lastOrNull() == "settings") -> djangoSettings(src, out)
        }
    }

    private fun lines(src: Src, action: (line: String, offset: Int) -> Unit) {
        var start = 0
        val text = src.text
        var n = 0
        while (start <= text.length && n++ < 20_000) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            action(text.substring(start, end), start)
            if (end >= text.length) break
            start = end + 1
        }
    }

    private fun envFile(src: Src, out: FrameworkFacts) = lines(src) { line, off ->
        val t = line.trim().removePrefix("export ").trim()
        if (t.startsWith("#") || !t.contains('=')) return@lines
        val key = t.substringBefore('=').trim()
        if (validKey(key)) out.configDefs += ConfigDefFact(key, ConfigKind.ENV, src.line(off), "archivo .env")
    }

    private fun properties(src: Src, out: FrameworkFacts) = lines(src) { line, off ->
        val t = line.trim()
        if (t.startsWith("#") || t.startsWith("!")) return@lines
        val sep = t.indexOfFirst { it == '=' || it == ':' }
        if (sep <= 0) return@lines
        val key = t.substring(0, sep).trim()
        if (!validKey(key)) return@lines
        out.configDefs += ConfigDefFact(key, ConfigKind.SPRING, src.line(off), "application.properties")
        contextPath(src, out, key, t.substring(sep + 1).trim(), off)
        placeholders(src, out, t.substring(sep + 1), off)
    }

    /** El prefijo de las rutas de Spring no es un secreto: es lo único que se lee de un valor. */
    private fun contextPath(src: Src, out: FrameworkFacts, key: String, value: String, off: Int) {
        if (key == "server.servlet.context-path" || key == "server.servlet.contextPath" || key == "spring.mvc.servlet.path") {
            val v = value.trim('"', '\'')
            if (v.startsWith("/") && v.none { it == '$' || it.isWhitespace() }) out.globalPrefixes += GlobalPrefixFact("Spring", v, src.line(off))
        }
    }

    /** `${DB_PASSWORD}` o `${DB_PASSWORD:valor}` dentro de un valor: la configuración lee una variable de entorno. */
    private fun placeholders(src: Src, out: FrameworkFacts, value: String, off: Int) {
        var i = value.indexOf("\${")
        while (i >= 0) {
            val end = value.indexOf('}', i)
            if (end < 0) break
            val key = value.substring(i + 2, end).substringBefore(':').trim()
            if (key.isNotEmpty() && key.all { it.isUpperCase() || it.isDigit() || it == '_' } && validKey(key)) {
                out.configUses += ConfigUseFact(key, ConfigKind.ENV, src.line(off), "\${$key}")
            }
            i = value.indexOf("\${", end)
        }
    }

    private fun yaml(src: Src, out: FrameworkFacts) {
        val stack = ArrayDeque<Pair<Int, String>>()
        lines(src) { line, off ->
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed == "---") return@lines
            val indent = line.length - trimmed.length
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@lines
            val key = trimmed.substring(0, colon).trim().trim('"', '\'')
            if (!validKey(key)) return@lines
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            val full = (stack.map { it.second } + key).joinToString(".")
            val value = trimmed.substring(colon + 1).trim()
            if (value.isEmpty() || value.startsWith("#")) {
                stack.addLast(indent to key)
            } else {
                out.configDefs += ConfigDefFact(full, ConfigKind.SPRING, src.line(off), src.info.name)
                contextPath(src, out, full, value, off)
                placeholders(src, out, value, off)
            }
        }
    }

    /** `appsettings.json`: claves de .NET con `:` entre secciones (`ConnectionStrings:Default`). */
    private fun json(src: Src, out: FrameworkFacts) {
        val path = ArrayDeque<String?>()
        var pendingKey: String? = null
        var i = 0
        val text = src.text
        var n = 0
        while (i < text.length && n++ < 200_000) {
            val c = text[i]
            when {
                c == '"' -> {
                    val lit = src.literal(i) ?: return
                    val after = src.skipWs(lit.end)
                    if (text.getOrNull(after) == ':') {
                        pendingKey = lit.value
                        val v = src.skipWs(after + 1)
                        if (text.getOrNull(v) != '{' && text.getOrNull(v) != '[') {
                            val full = (path.filterNotNull() + lit.value).joinToString(":")
                            if (validKey(full)) out.configDefs += ConfigDefFact(full, ConfigKind.DOTNET, src.line(i), src.info.name)
                            pendingKey = null
                        }
                        i = after + 1
                        continue
                    }
                    i = lit.end
                    continue
                }
                c == '{' -> {
                    path.addLast(pendingKey)
                    pendingKey = null
                }
                c == '[' -> {
                    // Los elementos de una lista no son claves con nombre: se salta la lista.
                    val end = src.close(i, 2_000_000)
                    if (end < 0) return
                    pendingKey = null
                    i = end + 1
                    continue
                }
                c == '}' -> if (path.isNotEmpty()) path.removeLast()
            }
            i++
        }
    }

    /** `environment:` de docker-compose: `- KEY=valor` o `KEY: valor`. */
    private fun compose(src: Src, out: FrameworkFacts) {
        var envIndent = -1
        lines(src) { line, off ->
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (trimmed.startsWith("environment:")) {
                envIndent = indent
                return@lines
            }
            if (envIndent < 0 || trimmed.isEmpty() || trimmed.startsWith("#")) return@lines
            if (indent <= envIndent) {
                envIndent = -1
                return@lines
            }
            val entry = trimmed.removePrefix("- ").trim().trim('"', '\'')
            val key = entry.substringBefore('=').substringBefore(':').trim()
            if (validKey(key) && key.all { it.isLetterOrDigit() || it == '_' }) out.configDefs += ConfigDefFact(key, ConfigKind.ENV, src.line(off), "docker-compose")
            placeholders(src, out, entry.substringAfter('=', entry.substringAfter(':', "")), off)
        }
    }

    private fun dockerfile(src: Src, out: FrameworkFacts) = lines(src) { line, off ->
        val t = line.trim()
        val upper = t.uppercase()
        if (!upper.startsWith("ENV ") && !upper.startsWith("ARG ")) return@lines
        val rest = t.substring(4).trim()
        // ENV A=1 B=2  o  ENV A 1
        val keys = if (rest.contains('=')) rest.split(' ').filter { it.contains('=') }.map { it.substringBefore('=') } else listOf(rest.substringBefore(' '))
        keys.filter { validKey(it) }.forEach { out.configDefs += ConfigDefFact(it, ConfigKind.ENV, src.line(off), "Dockerfile") }
    }

    private fun buildConfigFields(src: Src, out: FrameworkFacts) {
        src.each("buildConfigField") { at ->
            var k = src.skipWs(at + "buildConfigField".length)
            if (src.text.getOrNull(k) == '(') k++
            val first = src.literal(k) ?: return@each
            val comma = src.skipWs(first.end)
            if (src.text.getOrNull(comma) != ',') return@each
            val name = src.literal(comma + 1)?.value ?: return@each
            if (validKey(name)) out.configDefs += ConfigDefFact(name, ConfigKind.BUILD_CONFIG, src.line(at), "buildConfigField de Gradle")
        }
    }

    /** `config/app.php`: `return ['name' => …, 'connections' => ['mysql' => […]]]` → `app.name`, `app.connections.mysql`… */
    private fun laravelConfig(src: Src, out: FrameworkFacts) {
        val text = src.text
        val ret = text.indexOf("return [").takeIf { it >= 0 } ?: text.indexOf("return array(").takeIf { it >= 0 } ?: return
        val open = text.indexOf(if (text.startsWith("return [", ret)) '[' else '(', ret)
        walkPhpArray(src, out, open, listOf(src.info.stem), 0)
    }

    private fun walkPhpArray(src: Src, out: FrameworkFacts, open: Int, prefix: List<String>, depth: Int) {
        if (depth > 6 || out.configDefs.size > 5_000) return
        val end = src.close(open, 2_000_000)
        if (end < 0) return
        for (item in src.splitTop(open + 1, end, ',')) {
            val s = src.skipWs(item.first)
            val key = src.literal(s) ?: continue
            val arrow = src.skipWs(key.end)
            if (!src.text.startsWith("=>", arrow)) continue
            val full = prefix + key.value
            out.configDefs += ConfigDefFact(full.joinToString("."), ConfigKind.LARAVEL, src.line(s), "config/${src.info.name}")
            val v = src.skipWs(arrow + 2)
            if (src.text.getOrNull(v) == '[') walkPhpArray(src, out, v, full, depth + 1)
        }
    }

    private fun djangoSettings(src: Src, out: FrameworkFacts) = lines(src) { line, off ->
        if (line.isEmpty() || line[0].isWhitespace()) return@lines
        val eq = line.indexOf('=')
        if (eq <= 0 || line.getOrNull(eq + 1) == '=') return@lines
        val key = line.substring(0, eq).trim()
        if (key.isNotEmpty() && key.all { it.isUpperCase() || it.isDigit() || it == '_' } && key.first().isLetter()) {
            out.configDefs += ConfigDefFact(key, ConfigKind.DJANGO, src.line(off), "settings de Django")
        }
    }

    private fun validKey(key: String): Boolean = key.length in 1..150 && key.none { it.isWhitespace() || it.isISOControl() || it == '"' || it == '\'' } && !key.startsWith("-")

    // ------------------------------------------------------------------ lecturas

    private fun uses(src: Src, out: FrameworkFacts) {
        when (src.language) {
            "javascript", "typescript", "tsx", "vue", "svelte", "html" -> {
                member(src, out, "process.env.", ConfigKind.ENV, "process.env")
                indexed(src, out, "process.env[", ConfigKind.ENV, "process.env")
                member(src, out, "import.meta.env.", ConfigKind.ENV, "import.meta.env")
                member(src, out, "Bun.env.", ConfigKind.ENV, "Bun.env")
                call(src, out, "Deno.env.get(", ConfigKind.ENV, "Deno.env.get")
            }
            "python" -> {
                indexed(src, out, "os.environ[", ConfigKind.ENV, "os.environ")
                call(src, out, "os.environ.get(", ConfigKind.ENV, "os.environ.get")
                call(src, out, "os.getenv(", ConfigKind.ENV, "os.getenv")
                if (src.has("decouple")) call(src, out, "config(", ConfigKind.ENV, "python-decouple", word = true)
                if (src.has("environ")) {
                    call(src, out, "env(", ConfigKind.ENV, "django-environ", word = true)
                    for (t in listOf("str", "int", "bool", "list", "db", "url")) call(src, out, "env.$t(", ConfigKind.ENV, "django-environ", word = true)
                }
                if (src.has("django.conf")) member(src, out, "settings.", ConfigKind.DJANGO, "settings", upperOnly = true)
            }
            "php" -> {
                call(src, out, "env(", ConfigKind.ENV, "env()", word = true)
                call(src, out, "getenv(", ConfigKind.ENV, "getenv()", word = true)
                indexed(src, out, "\$_ENV[", ConfigKind.ENV, "\$_ENV")
                call(src, out, "config(", ConfigKind.LARAVEL, "config()", word = true)
                call(src, out, "Config::get(", ConfigKind.LARAVEL, "Config::get")
            }
            "java", "kotlin" -> {
                call(src, out, "System.getenv(", ConfigKind.ENV, "System.getenv")
                springValue(src, out)
                for (recv in listOf("environment.getProperty(", "env.getProperty(", "environment.getRequiredProperty(")) call(src, out, recv, ConfigKind.SPRING, "Environment")
                src.each("@ConfigurationProperties(") { at ->
                    if (!src.annotationAt(at)) return@each
                    val args = src.args(at + "@ConfigurationProperties".length) ?: return@each
                    val prefix = (Routes.namedArg(src, args, "prefix", "value") ?: args.firstOrNull())?.let { src.literalArg(it)?.value } ?: return@each
                    out.configUses += ConfigUseFact("$prefix.*", ConfigKind.SPRING, src.line(at), "@ConfigurationProperties")
                }
                member(src, out, "BuildConfig.", ConfigKind.BUILD_CONFIG, "BuildConfig", upperOnly = true)
            }
            "csharp" -> {
                call(src, out, "Environment.GetEnvironmentVariable(", ConfigKind.ENV, "Environment.GetEnvironmentVariable")
                for (recv in listOf("Configuration[", "configuration[", "_configuration[", "_config[", "config[", "_configuration.GetValue", "Configuration.GetValue")) {
                    if (recv.endsWith("[")) indexed(src, out, recv, ConfigKind.DOTNET, "IConfiguration", member = true) else genericCall(src, out, recv, ConfigKind.DOTNET)
                }
                call(src, out, ".GetConnectionString(", ConfigKind.DOTNET, "GetConnectionString", prefix = "ConnectionStrings:")
                src.each(".GetSection(") { at ->
                    val key = src.args(at + ".GetSection".length)?.firstOrNull()?.let { src.literalArg(it) }?.takeIf { !it.dynamic }?.value ?: return@each
                    out.configUses += ConfigUseFact("$key:*", ConfigKind.DOTNET, src.line(at), "GetSection")
                }
            }
            "dart" -> {
                indexed(src, out, "dotenv.env[", ConfigKind.ENV, "flutter_dotenv")
                call(src, out, "dotenv.get(", ConfigKind.ENV, "flutter_dotenv")
                call(src, out, "String.fromEnvironment(", ConfigKind.ENV, "--dart-define")
                indexed(src, out, "Platform.environment[", ConfigKind.ENV, "Platform.environment")
            }
            "go" -> call(src, out, "os.Getenv(", ConfigKind.ENV, "os.Getenv")
            "gdscript" -> call(src, out, "OS.get_environment(", ConfigKind.ENV, "OS.get_environment")
        }
    }

    private fun add(out: FrameworkFacts, src: Src, key: String, kind: ConfigKind, at: Int, via: String) {
        if (!validKey(key) || out.configUses.size >= MAX_USES) return
        if (kind == ConfigKind.BUILD_CONFIG && key in BUILD_CONFIG_DEFAULTS) return
        out.configUses += ConfigUseFact(key, kind, src.line(at), via)
    }

    private fun member(src: Src, out: FrameworkFacts, prefix: String, kind: ConfigKind, via: String, upperOnly: Boolean = false) {
        src.each(prefix) { at ->
            if (at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
            val key = src.wordAt(at + prefix.length) ?: return@each
            if (upperOnly && !key.first().isUpperCase()) return@each
            add(out, src, key, kind, at, via)
        }
    }

    /** @param member el objeto puede ser un miembro (`builder.Configuration["A:B"]`). */
    private fun indexed(src: Src, out: FrameworkFacts, prefix: String, kind: ConfigKind, via: String, member: Boolean = false) {
        src.each(prefix) { at ->
            if (at > 0 && (Src.isIdent(src.text[at - 1]) && !prefix.startsWith("_") || src.text[at - 1] == '.' && !member)) return@each
            val lit = src.literal(at + prefix.length) ?: return@each
            if (!lit.dynamic) add(out, src, lit.value, kind, at, via)
        }
    }

    private fun call(src: Src, out: FrameworkFacts, fn: String, kind: ConfigKind, via: String, word: Boolean = false, prefix: String = "") {
        val action: (Int) -> Unit = { at ->
            val args = src.args(at + fn.length - 1)
            val lit = args?.firstOrNull()?.let { src.literalArg(it) }
            if (lit != null && !lit.dynamic) add(out, src, prefix + lit.value, kind, at, via)
        }
        if (word) src.eachWord(fn, action) else src.each(fn, action)
    }

    /** `GetValue<string>("A:B")`. */
    private fun genericCall(src: Src, out: FrameworkFacts, fn: String, kind: ConfigKind) {
        src.each(fn) { at ->
            var k = at + fn.length
            if (src.text.getOrNull(k) == '<') k = src.close(k).let { if (it < 0) return@each else it + 1 }
            if (src.text.getOrNull(k) != '(') return@each
            val lit = src.args(k)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
            if (!lit.dynamic) add(out, src, lit.value, kind, at, "IConfiguration")
        }
    }

    /** `@Value("${a.b}")`, `@Value("\${a.b:valor}")` de Spring. */
    private fun springValue(src: Src, out: FrameworkFacts) {
        src.each("@Value(") { at ->
            if (!src.annotationAt(at)) return@each
            val end = src.close(at + 6)
            if (end < 0) return@each
            val inner = src.text.substring(at + 7, end)
            var i = inner.indexOf("{")
            while (i >= 0) {
                val close = inner.indexOf('}', i)
                if (close < 0) break
                val key = inner.substring(i + 1, close).substringBefore(':').trim()
                if (key.isNotEmpty() && !key.startsWith("#")) add(out, src, key, ConfigKind.SPRING, at, "@Value")
                i = inner.indexOf("{", close)
            }
        }
    }

    private const val MAX_USES = 2_000
}
