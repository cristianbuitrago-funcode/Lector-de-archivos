package com.funcodestudiocb.analyzer.engine.relations.framework

/**
 * Inyección de dependencias: dónde el framework entrega una dependencia por su tipo (constructores de clases
 * gestionadas, `@Autowired`, `@Inject`, `Depends`) y los registros explícitos de implementaciones. Al enlazarlos, las
 * conexiones son siempre "probables" o "no verificadas": la configuración real se decide al ejecutar.
 */
internal object Injection {

    private val SPRING_MANAGED = listOf("@Service", "@Component", "@RestController", "@Controller", "@Repository", "@Configuration", "@HiltViewModel", "@Singleton")
    private val TS_MANAGED = listOf("@Injectable(", "@Controller(", "@Component(", "@Resolver(", "@Directive(", "@Pipe(", "@Processor(", "@WebSocketGateway(")
    private val IGNORED_TYPES = setOf(
        "String", "Int", "Long", "Boolean", "Double", "Float", "Object", "Any", "Context", "Application", "number", "string", "boolean", "any",
        "Integer", "List", "Map", "Set", "Array", "ILogger", "Logger", "IConfiguration", "IOptions", "HttpClient", "Request", "Response",
    )

    fun extract(src: Src, out: FrameworkFacts) {
        when (src.language) {
            "java", "kotlin" -> jvm(src, out)
            "typescript", "tsx", "javascript" -> typescript(src, out)
            "csharp" -> csharp(src, out)
            "php" -> php(src, out)
            "python" -> python(src, out)
        }
    }

    private fun add(out: FrameworkFacts, src: Src, type: String, at: Int, via: String) {
        val t = type.trim().removeSuffix("?").substringBefore('<').substringAfterLast('.').substringAfterLast('\\')
        if (t.isEmpty() || t in IGNORED_TYPES || !t.first().isLetter() || out.injections.size >= MAX) return
        if (out.injections.any { it.type == t && it.via == via }) return
        out.injections += InjectFact(t, src.line(at), via)
    }

    // ------------------------------------------------------------------ Spring, Hilt/Dagger, Koin

    private fun jvm(src: Src, out: FrameworkFacts) {
        val text = src.text
        val kotlin = src.language == "kotlin"
        // Campos: @Autowired / @Inject
        for (ann in listOf("@Autowired", "@Inject")) {
            src.each(ann) { at ->
                val after = at + ann.length
                if (after < text.length && Src.isIdent(text[after])) return@each
                if (!src.annotationAt(at)) return@each
                val decl = text.substring(after, minOf(text.length, after + 200)).substringBefore('\n').trim()
                if (decl.startsWith("constructor") || decl.isEmpty()) {
                    // @Inject constructor(...) en la línea siguiente o la misma: lo trata el bloque de constructores.
                    return@each
                }
                val type = if (kotlin) Regexes.KT_PROPERTY_TYPE.matcher(decl).let { if (it.find()) it.group(1) else null } else Regexes.JAVA_FIELD_TYPE.matcher(decl).let { if (it.find()) it.group(1) else null }
                type?.let { add(out, src, it, at, if (ann == "@Autowired") "@Autowired" else "@Inject") }
            }
        }
        // Constructores de clases gestionadas por el framework e @Inject constructor
        val managed = SPRING_MANAGED.any { a -> src.has(a) } || src.has("@Inject")
        if (managed) {
            val classes = Regexes.CLASS_DECL.matcher(text)
            while (classes.find()) {
                if (!src.isCode(classes.start())) continue
                val head = Regexes.annotationBlock(text, classes.start())
                val isManaged = SPRING_MANAGED.any { head.contains(it) } || text.startsWith("@Inject", src.skipWs(classes.end()))
                val name = classes.group(2)
                var k = src.skipWs(classes.end())
                if (kotlin) {
                    // class X @Inject constructor(...) o class X(private val a: A)
                    if (text.startsWith("@Inject", k)) k = src.skipWs(k + 7)
                    if (text.startsWith("constructor", k)) k = src.skipWs(k + 11)
                    if (text.getOrNull(k) != '(' || !isManaged && !text.substring(classes.end(), k).contains("@Inject")) continue
                    val args = src.args(k) ?: continue
                    for (a in args) {
                        val p = src.slice(a)
                        if (p.contains("@Value")) continue
                        Regexes.KT_PARAM_TYPE.matcher(p).let { if (it.find()) add(out, src, it.group(1), a.first, if (isManaged) "constructor" else "@Inject constructor") }
                    }
                } else {
                    if (!isManaged && !head.contains("@Inject")) {
                        // Java: @Inject en el constructor dentro del cuerpo.
                        javaInjectConstructor(src, out, name, classes.end())
                        continue
                    }
                    javaConstructor(src, out, name, classes.end(), if (isManaged) "constructor" else "@Inject")
                    // Lombok: @RequiredArgsConstructor + campos final
                    if (head.contains("@RequiredArgsConstructor") || head.contains("@AllArgsConstructor")) {
                        val body = text.substring(classes.end(), minOf(text.length, classes.end() + 4_000))
                        val f = Regexes.JAVA_FINAL_FIELD.matcher(body)
                        while (f.find()) add(out, src, f.group(1), classes.end() + f.start(), "Lombok")
                    }
                }
            }
        }
        // Registros: @Binds abstract fun bind(impl: C): I  /  @Provides fun provide(): I  /  @Bean
        for (ann in listOf("@Binds", "@Provides", "@Bean")) {
            src.each(ann) { at ->
                val after = at + ann.length
                if (after < text.length && Src.isIdent(text[after])) return@each
                if (!src.annotationAt(at)) return@each
                val decl = text.substring(after, minOf(text.length, after + 300))
                if (kotlin) {
                    val m = Regexes.KT_FUN_SIGNATURE.matcher(decl)
                    if (!m.find()) return@each
                    val params = m.group(1)
                    val ret = m.group(2)?.trim()?.substringBefore('<') ?: return@each
                    if (ann == "@Binds") {
                        Regexes.KT_PARAM_TYPE.matcher(params).let { if (it.find()) out.binds += BindFact(ret, it.group(1).substringBefore('<'), src.line(at), "@Binds") }
                    } else {
                        out.binds += BindFact(ret, "", src.line(at), ann)
                    }
                } else {
                    val m = Regexes.JAVA_METHOD_SIGNATURE.matcher(decl)
                    if (!m.find()) return@each
                    val ret = m.group(1).substringBefore('<')
                    if (ann == "@Binds") {
                        Regexes.JAVA_PARAM_TYPE.matcher(m.group(2)).let { if (it.find()) out.binds += BindFact(ret, it.group(1), src.line(at), "@Binds") }
                    } else {
                        out.binds += BindFact(ret, "", src.line(at), ann)
                    }
                }
            }
        }
        // Koin: single<I> { C(get()) }, by inject<T>(), get<T>()
        if (src.has("koin")) {
            for (fn in listOf("single<", "factory<", "scoped<")) {
                src.eachWord(fn) { at ->
                    val close = src.close(at + fn.length - 1)
                    if (close < 0) return@eachWord
                    val contract = text.substring(at + fn.length, close).trim()
                    val impl = Regexes.firstClassAfterBrace(text.substring(close, minOf(text.length, close + 200))) ?: return@eachWord
                    out.binds += BindFact(contract, impl, src.line(at), "Koin")
                }
            }
            for (fn in listOf("inject<", "get<", "koinInject<", "koinViewModel<")) {
                src.eachWord(fn) { at ->
                    val close = src.close(at + fn.length - 1)
                    if (close > 0) add(out, src, text.substring(at + fn.length, close), at, "Koin")
                }
            }
        }
    }

    private fun javaConstructor(src: Src, out: FrameworkFacts, cls: String, from: Int, via: String) {
        val text = src.text
        var i = text.indexOf("$cls(", from)
        var guard = 0
        while (i >= 0 && guard++ < 5) {
            val before = text.substring(maxOf(0, i - 20), i)
            if (before.trimEnd().endsWith("new")) {
                i = text.indexOf("$cls(", i + 1)
                continue
            }
            val args = src.args(i + cls.length) ?: return
            for (a in args) Regexes.JAVA_PARAM_TYPE.matcher(src.slice(a)).let { if (it.find()) add(out, src, it.group(1), a.first, via) }
            return
        }
    }

    private fun javaInjectConstructor(src: Src, out: FrameworkFacts, cls: String, from: Int) {
        val i = src.text.indexOf("@Inject", from)
        if (i < 0 || i > from + 6_000) return
        javaConstructor(src, out, cls, i, "@Inject")
    }

    // ------------------------------------------------------------------ NestJS y Angular

    private fun typescript(src: Src, out: FrameworkFacts) {
        val text = src.text
        if (TS_MANAGED.any { src.has(it) }) {
            src.each("constructor(") { at ->
                if (at > 0 && Src.isIdent(text[at - 1])) return@each
                // Solo en clases con decoradores de inyección.
                val classAt = text.lastIndexOf("class ", at)
                if (classAt < 0 || TS_MANAGED.none { text.substring(maxOf(0, classAt - 800), classAt).contains(it) }) return@each
                val args = src.args(at + 11) ?: return@each
                for (a in args) {
                    val p = src.slice(a)
                    val injectToken = Regexes.TS_INJECT_TOKEN.matcher(p).let { if (it.find()) it.group(1) else null }
                    val type = injectToken ?: Regexes.TS_PARAM_TYPE.matcher(p).let { if (it.find()) it.group(1) else null }
                    type?.let { add(out, src, it, a.first, "constructor") }
                }
            }
        }
        src.eachWord("inject(") { at ->
            if (!src.has("@angular")) return@eachWord
            val t = src.args(at + 6)?.firstOrNull()?.let { src.slice(it) } ?: return@eachWord
            if (t.all { Src.isIdent(it) }) add(out, src, t, at, "inject()")
        }
        // { provide: I, useClass: C }
        src.each("provide:") { at ->
            val open = text.lastIndexOf('{', at).takeIf { it >= 0 && at - it < 100 } ?: return@each
            val end = src.close(open)
            if (end < 0) return@each
            val parts = src.splitTop(open + 1, end, ',')
            val contract = Routes.namedArg(src, parts, "provide")?.let { src.slice(it) } ?: return@each
            val impl = Routes.namedArg(src, parts, "useClass", "useExisting")?.let { src.slice(it) } ?: return@each
            if (contract.all { Src.isIdent(it) } && impl.all { Src.isIdent(it) }) out.binds += BindFact(contract, impl, src.line(at), "provider")
        }
    }

    // ------------------------------------------------------------------ ASP.NET Core

    private fun csharp(src: Src, out: FrameworkFacts) {
        val text = src.text
        // services.AddScoped<IUserService, UserService>()
        for (fn in listOf("AddScoped<", "AddTransient<", "AddSingleton<", "TryAddScoped<", "TryAddTransient<", "TryAddSingleton<", "AddHttpClient<")) {
            src.each(".$fn") { at ->
                val open = at + fn.length
                val close = src.close(open)
                if (close < 0) return@each
                val parts = text.substring(open + 1, close).split(',').map { it.trim() }
                if (parts.size == 2) out.binds += BindFact(parts[0], parts[1], src.line(at), fn.removeSuffix("<"))
            }
        }
        // Constructores con parámetros de interfaz (I + mayúscula) o de servicios registrados.
        val classes = Regexes.CS_CLASS.matcher(text)
        while (classes.find()) {
            if (!src.isCode(classes.start())) continue
            val name = classes.group(1)
            // Constructor principal (C# 12): class X(IService s)
            val k = src.skipWs(classes.end())
            if (text.getOrNull(k) == '(') {
                src.args(k)?.forEach { a -> Regexes.CS_PARAM_TYPE.matcher(src.slice(a)).let { if (it.find()) add(out, src, it.group(1), a.first, "constructor") } }
            }
            var i = text.indexOf("public $name(", classes.end())
            if (i < 0) i = text.indexOf("protected $name(", classes.end())
            if (i < 0 || i - classes.end() > 20_000) continue
            val open = text.indexOf('(', i)
            src.args(open)?.forEach { a -> Regexes.CS_PARAM_TYPE.matcher(src.slice(a)).let { if (it.find()) add(out, src, it.group(1), a.first, "constructor") } }
        }
        src.each("[FromServices]") { at ->
            Regexes.CS_PARAM_TYPE.matcher(text.substring(at + 14, minOf(text.length, at + 120))).let { if (it.find()) add(out, src, it.group(1), at, "[FromServices]") }
        }
    }

    // ------------------------------------------------------------------ Laravel

    private fun php(src: Src, out: FrameworkFacts) {
        val text = src.text
        src.each("function __construct(") { at ->
            val args = src.args(at + "function __construct".length) ?: return@each
            for (a in args) Regexes.PHP_PARAM_TYPE.matcher(src.slice(a)).let { if (it.find()) add(out, src, it.group(1), a.first, "constructor") }
        }
        for (fn in listOf("->bind(", "->singleton(", "->scoped(")) {
            src.each(fn) { at ->
                val args = src.args(at + fn.length - 1) ?: return@each
                if (args.size < 2) return@each
                val contract = src.slice(args[0]).removeSuffix("::class").trim().substringAfterLast('\\')
                val impl = src.slice(args[1]).removeSuffix("::class").trim().substringAfterLast('\\')
                if (contract.all { Src.isIdent(it) } && impl.all { Src.isIdent(it) } && impl.isNotEmpty()) out.binds += BindFact(contract, impl, src.line(at), fn.trim('-', '>', '('))
            }
        }
        if (text.contains("app()->make(")) {
            src.each("app()->make(") { at ->
                src.args(at + 11)?.firstOrNull()?.let { src.slice(it).removeSuffix("::class") }?.let { add(out, src, it, at, "app()->make") }
            }
        }
    }

    // ------------------------------------------------------------------ FastAPI

    private fun python(src: Src, out: FrameworkFacts) {
        if (!src.has("Depends(")) return
        src.eachWord("Depends(") { at ->
            val t = src.args(at + 7)?.firstOrNull()?.let { src.slice(it) } ?: return@eachWord
            if (t.isNotEmpty() && t.all { Src.isIdent(it) || it == '.' }) {
                if (out.injections.size < MAX) out.injections += InjectFact(t, src.line(at), "Depends")
            }
        }
    }

    private const val MAX = 500
}
