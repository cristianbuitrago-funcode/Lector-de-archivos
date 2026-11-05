package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.relations.framework.Src

/**
 * Inyecciones y operaciones peligrosas en el código (§8.2): SQL, comandos, evaluación de código, deserialización,
 * plantillas, rutas de archivo, inclusión, SSRF, redirecciones y XSS. Cada punto peligroso se busca como texto fijo
 * y solo entonces se leen sus argumentos y se sigue el dato hacia atrás ([Taint]).
 */
internal object CodeChecks {

    /**
     * Cuándo hay hallazgo:
     * - [SQL]: la consulta se construye con partes variables y tiene forma de SQL;
     * - [STRICT]: cualquier valor variable (lo peligroso es la operación misma: eval, un comando);
     * - [CONSTRUCTED]: un dato de la petición, o un texto que se construye con partes variables;
     * - [SOURCE]: solo si llega un dato de la petición (operaciones habituales con archivos o URL);
     * - [ALWAYS]: el uso del mecanismo ya es el problema (BinaryFormatter, ObjectInputStream).
     */
    private enum class Mode { SQL, STRICT, CONSTRUCTED, SOURCE, ALWAYS }

    private class Sink(
        val needle: String,
        val rule: String,
        val what: String,
        val mode: Mode,
        /** Argumento que se examina; -1 es el último. */
        val arg: Int = 0,
        /** El texto empieza un nombre (no es un método de otro objeto: `exec(` y no `regex.exec(`). */
        val word: Boolean = false,
        /** El archivo tiene que contener alguno de estos textos (la biblioteca que lo hace peligroso). */
        val requires: List<String> = emptyList(),
        /** Los argumentos tienen que contener este texto (`shell=True`). */
        val requiresArg: String? = null,
        /** Solo cuentan los datos que recibe el servidor (no los del navegador, como location.hash). */
        val server: Boolean = true,
        /** Comprobaciones cercanas que pueden protegerlo: rebajan la certeza a "posible". */
        val mitigations: List<String> = emptyList(),
        /** Los argumentos no pueden contener este texto (`allowed_classes`, `SafeLoader`). */
        val unless: List<String> = emptyList(),
    )

    private const val SQL = "la consulta SQL"
    private const val CMD = "un comando del sistema"
    private const val CODE = "la evaluación de código"
    private const val DESER = "la deserialización"
    private const val TEMPLATE = "el texto de una plantilla"
    private const val FILE = "una operación con archivos"
    private const val URL = "una petición HTTP del servidor"
    private const val REDIRECT = "una redirección"
    private const val HTML = "la respuesta HTML"
    private const val INCLUDE = "la inclusión de un archivo"

    private val PATH_FIX = listOf("basename", "secure_filename", "realpath", "normalize(", "GetFileName", "getCanonicalPath", "Clean(", "startsWith(", "startswith(", "is_relative_to")
    private val REDIRECT_FIX = listOf("url_has_allowed_host_and_scheme", "is_safe_url", "IsLocalUrl", "LocalRedirect", "allowedHosts", "ALLOWED_REDIRECT", "startsWith('/')", "startsWith(\"/\")")
    private val URL_FIX = listOf("allowlist", "whitelist", "ALLOWED_HOSTS", "allowedHosts", "allowed_hosts", "isAllowedUrl", "validateUrl", "validate_url")

    private fun sql(needle: String, arg: Int = 0, word: Boolean = false) = Sink(needle, "FC-SQLI-01", SQL, Mode.SQL, arg, word)

    private val SINKS: Map<String, List<Sink>> = mapOf(
        "js" to listOf(
            sql(".query("), sql(".execute("), sql(".exec("), sql(".raw("), sql(".whereRaw("), sql(".\$queryRawUnsafe("), sql(".\$executeRawUnsafe("),
            sql(".prepare("), sql(".all("), sql(".get("), sql(".run("),
            Sink("exec(", "FC-CMD-01", CMD, Mode.STRICT, word = true, requires = listOf("child_process")),
            Sink("execSync(", "FC-CMD-01", CMD, Mode.STRICT, word = true, requires = listOf("child_process")),
            Sink("child_process.exec(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("child_process.execSync(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("childProcess.exec(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("cp.exec(", "FC-CMD-01", CMD, Mode.STRICT, word = true, requires = listOf("child_process")),
            Sink("cp.execSync(", "FC-CMD-01", CMD, Mode.STRICT, word = true, requires = listOf("child_process")),
            Sink("spawn(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell", requires = listOf("child_process")),
            Sink("eval(", "FC-CODE-01", CODE, Mode.STRICT, word = true, server = false),
            Sink("new Function(", "FC-CODE-01", CODE, Mode.STRICT, arg = -1, server = false),
            Sink("vm.runInNewContext(", "FC-CODE-01", CODE, Mode.STRICT),
            Sink("vm.runInThisContext(", "FC-CODE-01", CODE, Mode.STRICT),
            Sink("vm.runInContext(", "FC-CODE-01", CODE, Mode.STRICT),
            Sink("unserialize(", "FC-DESER-01", DESER, Mode.STRICT, requires = listOf("node-serialize")),
            Sink("ejs.render(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("pug.render(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("pug.compile(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("Handlebars.compile(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("handlebars.compile(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("_.template(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("nunjucks.renderString(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("fs.readFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.readFileSync(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.createReadStream(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.writeFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.writeFileSync(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.unlink(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.unlinkSync(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("fs.promises.readFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("res.sendFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX + "root:"),
            Sink("res.download(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("require(", "FC-PATH-02", INCLUDE, Mode.SOURCE, word = true),
            Sink("fetch(", "FC-SSRF-01", URL, Mode.SOURCE, word = true, mitigations = URL_FIX),
            Sink("axios.get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("axios.post(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("axios(", "FC-SSRF-01", URL, Mode.SOURCE, word = true, mitigations = URL_FIX),
            Sink("http.get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("https.get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("http.request(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("https.request(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("got(", "FC-SSRF-01", URL, Mode.SOURCE, word = true, mitigations = URL_FIX),
            Sink("res.redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, arg = -1, mitigations = REDIRECT_FIX),
            Sink("ctx.redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, arg = -1, mitigations = REDIRECT_FIX),
            Sink("reply.redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, arg = -1, mitigations = REDIRECT_FIX),
            Sink("res.send(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("res.write(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("res.end(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("document.write(", "FC-XSS-01", "el HTML de la página", Mode.STRICT, server = false),
            Sink("document.writeln(", "FC-XSS-01", "el HTML de la página", Mode.STRICT, server = false),
            Sink("insertAdjacentHTML(", "FC-XSS-01", "el HTML de la página", Mode.STRICT, arg = 1, server = false),
            Sink(".html(", "FC-XSS-01", "el HTML de la página", Mode.STRICT, server = false, requires = listOf("jQuery", "\$(")),
            Sink("bypassSecurityTrustHtml(", "FC-XSS-01", "el HTML de la página", Mode.STRICT, server = false),
            Sink("shell.openExternal(", "FC-ELECTRON-02", "shell.openExternal", Mode.STRICT, server = false, mitigations = listOf("startsWith('https", "startsWith(\"https", "protocol", "allowlist", "ALLOWED")),
        ),
        "python" to listOf(
            sql(".execute("), sql(".executescript("), sql(".raw("), sql(".extra("), sql("RawSQL(", word = true), sql("text(", word = true),
            sql("read_sql(", word = false), sql("read_sql_query("), sql(".query("),
            Sink("os.system(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("os.popen(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("subprocess.call(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell=True"),
            Sink("subprocess.run(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell=True"),
            Sink("subprocess.Popen(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell=True"),
            Sink("subprocess.check_output(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell=True"),
            Sink("subprocess.check_call(", "FC-CMD-01", CMD, Mode.STRICT, requiresArg = "shell=True"),
            Sink("subprocess.getoutput(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("subprocess.getstatusoutput(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("eval(", "FC-CODE-01", CODE, Mode.STRICT, word = true),
            Sink("exec(", "FC-CODE-01", CODE, Mode.STRICT, word = true),
            Sink("pickle.loads(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("pickle.load(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("cPickle.loads(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("dill.loads(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("marshal.loads(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("jsonpickle.decode(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("yaml.unsafe_load(", "FC-DESER-01", DESER, Mode.STRICT),
            Sink("yaml.load(", "FC-DESER-01", DESER, Mode.STRICT, unless = listOf("SafeLoader", "CSafeLoader", "BaseLoader")),
            Sink("render_template_string(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED),
            Sink("Template(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED, word = true, requires = listOf("jinja2", "mako")),
            Sink(".from_string(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED, requires = listOf("jinja2")),
            Sink("open(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("send_file(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("FileResponse(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("os.remove(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("os.unlink(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("shutil.rmtree(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("requests.get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("requests.post(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("requests.put(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("requests.head(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("requests.request(", "FC-SSRF-01", URL, Mode.SOURCE, arg = 1, mitigations = URL_FIX),
            Sink("urlopen(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("httpx.get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("httpx.post(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("HttpResponseRedirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("RedirectResponse(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("HttpResponse(", "FC-XSS-02", HTML, Mode.SOURCE, word = true),
            Sink("make_response(", "FC-XSS-02", HTML, Mode.SOURCE, word = true),
            Sink("mark_safe(", "FC-XSS-03", "un HTML marcado como seguro", Mode.CONSTRUCTED, word = true),
            Sink("Markup(", "FC-XSS-03", "un HTML marcado como seguro", Mode.CONSTRUCTED, word = true),
        ),
        "php" to listOf(
            sql("mysqli_query(", arg = 1, word = true), sql("mysql_query(", word = true), sql("->query("), sql("->exec("), sql("->prepare("),
            sql("pg_query(", arg = -1, word = true), sql("sqlsrv_query(", arg = 1, word = true), sql("DB::select("), sql("DB::statement("),
            sql("DB::raw("), sql("DB::unprepared("), sql("->whereRaw("), sql("->selectRaw("), sql("->orderByRaw("), sql("->havingRaw("),
            Sink("exec(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("shell_exec(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("system(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("passthru(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("popen(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("proc_open(", "FC-CMD-01", CMD, Mode.STRICT, word = true),
            Sink("eval(", "FC-CODE-01", CODE, Mode.STRICT, word = true),
            Sink("create_function(", "FC-CODE-01", CODE, Mode.STRICT, word = true, arg = -1),
            Sink("unserialize(", "FC-DESER-01", DESER, Mode.STRICT, word = true, unless = listOf("allowed_classes")),
            Sink("file_get_contents(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("fopen(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("readfile(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("file(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("unlink(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("file_put_contents(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("->download(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("curl_init(", "FC-SSRF-01", URL, Mode.SOURCE, word = true, mitigations = URL_FIX),
            Sink("Http::get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("Http::post(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("header(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, requiresArg = "ocation", mitigations = REDIRECT_FIX),
            Sink("redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("->away(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, mitigations = REDIRECT_FIX),
            Sink("Redirect::to(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, mitigations = REDIRECT_FIX),
        ),
        "jvm" to listOf(
            sql("executeQuery("), sql("executeUpdate("), sql(".execute("), sql("prepareStatement("), sql("createQuery("), sql("createNativeQuery("),
            sql("createSQLQuery("), sql("rawQuery("), sql("execSQL("), sql(".query("), sql(".queryForObject("), sql(".queryForList("), sql(".update("),
            sql(".batchUpdate("), sql("compileStatement("),
            Sink("getRuntime().exec(", "FC-CMD-01", CMD, Mode.STRICT),
            Sink("ProcessBuilder(", "FC-CMD-01", CMD, Mode.CONSTRUCTED, word = true, arg = -1),
            Sink(".eval(", "FC-CODE-01", CODE, Mode.STRICT, requires = listOf("ScriptEngine")),
            Sink(".evaluate(", "FC-CODE-01", CODE, Mode.STRICT, requires = listOf("GroovyShell")),
            Sink("parseExpression(", "FC-CODE-01", CODE, Mode.CONSTRUCTED, requires = listOf("SpelExpressionParser", "ExpressionParser")),
            Sink("ObjectInputStream(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("XMLDecoder(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("enableDefaultTyping(", "FC-DESER-01", DESER, Mode.ALWAYS),
            Sink(".fromXML(", "FC-DESER-01", DESER, Mode.ALWAYS, requires = listOf("XStream")),
            Sink("Velocity.evaluate(", "FC-SSTI-01", TEMPLATE, Mode.CONSTRUCTED, arg = -1),
            Sink("File(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, arg = -1, mitigations = PATH_FIX),
            Sink("Paths.get(", "FC-PATH-01", FILE, Mode.SOURCE, arg = -1, mitigations = PATH_FIX),
            Sink("Path.of(", "FC-PATH-01", FILE, Mode.SOURCE, arg = -1, mitigations = PATH_FIX),
            Sink("FileInputStream(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("FileOutputStream(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("FileReader(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("URL(", "FC-SSRF-01", URL, Mode.SOURCE, word = true, mitigations = URL_FIX),
            Sink(".getForObject(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink(".getForEntity(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink(".postForObject(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("Jsoup.connect(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("URI.create(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("sendRedirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, mitigations = REDIRECT_FIX),
            Sink("RedirectView(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("getWriter().print(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("getWriter().println(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("getWriter().write(", "FC-XSS-02", HTML, Mode.SOURCE),
        ),
        "csharp" to listOf(
            sql("SqlCommand(", word = true), sql("OleDbCommand(", word = true), sql("MySqlCommand(", word = true), sql("NpgsqlCommand(", word = true),
            sql("SqliteCommand(", word = true), sql("SQLiteCommand(", word = true), sql(".ExecuteSqlRaw("), sql(".ExecuteSqlRawAsync("),
            sql(".FromSqlRaw("), sql(".SqlQueryRaw("), sql(".Query("), sql(".QueryAsync("), sql(".Execute("), sql(".ExecuteAsync("),
            sql(".QueryFirstOrDefault("), sql(".QueryFirstOrDefaultAsync("),
            Sink("Process.Start(", "FC-CMD-01", CMD, Mode.CONSTRUCTED, arg = -1),
            Sink("ProcessStartInfo(", "FC-CMD-01", CMD, Mode.CONSTRUCTED, word = true, arg = -1),
            Sink("CSharpScript.EvaluateAsync(", "FC-CODE-01", CODE, Mode.STRICT),
            Sink("BinaryFormatter(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("NetDataContractSerializer(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("LosFormatter(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("SoapFormatter(", "FC-DESER-01", DESER, Mode.ALWAYS, word = true),
            Sink("File.ReadAllText(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("File.ReadAllBytes(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("File.OpenRead(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("File.Open(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("File.WriteAllText(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("File.Delete(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("PhysicalFile(", "FC-PATH-01", FILE, Mode.SOURCE, word = true, mitigations = PATH_FIX),
            Sink("Path.Combine(", "FC-PATH-01", FILE, Mode.SOURCE, arg = -1, mitigations = PATH_FIX),
            Sink(".GetAsync(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink(".GetStringAsync(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink(".PostAsync(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("WebRequest.Create(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink(".DownloadString(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("Redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("RedirectPermanent(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, word = true, mitigations = REDIRECT_FIX),
            Sink("Response.Write(", "FC-XSS-02", HTML, Mode.SOURCE),
            Sink("Html.Raw(", "FC-XSS-03", "un HTML sin escapar", Mode.CONSTRUCTED),
        ),
        "go" to listOf(
            sql(".Query("), sql(".QueryRow("), sql(".Exec("), sql(".QueryContext(", arg = 1), sql(".QueryRowContext(", arg = 1),
            sql(".ExecContext(", arg = 1), sql(".Raw("), sql(".Where("),
            Sink("exec.Command(", "FC-CMD-01", CMD, Mode.CONSTRUCTED, arg = -1),
            Sink("exec.CommandContext(", "FC-CMD-01", CMD, Mode.CONSTRUCTED, arg = -1),
            Sink("os.Open(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("os.ReadFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("ioutil.ReadFile(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("os.Create(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("os.Remove(", "FC-PATH-01", FILE, Mode.SOURCE, mitigations = PATH_FIX),
            Sink("http.ServeFile(", "FC-PATH-01", FILE, Mode.SOURCE, arg = 2, mitigations = PATH_FIX),
            Sink("filepath.Join(", "FC-PATH-01", FILE, Mode.SOURCE, arg = -1, mitigations = PATH_FIX),
            Sink("http.Get(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("http.Post(", "FC-SSRF-01", URL, Mode.SOURCE, mitigations = URL_FIX),
            Sink("http.NewRequest(", "FC-SSRF-01", URL, Mode.SOURCE, arg = 1, mitigations = URL_FIX),
            Sink("http.Redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, arg = 2, mitigations = REDIRECT_FIX),
            Sink("c.Redirect(", "FC-REDIRECT-01", REDIRECT, Mode.SOURCE, arg = 1, mitigations = REDIRECT_FIX),
            Sink("template.HTML(", "FC-XSS-03", "un HTML marcado como seguro", Mode.CONSTRUCTED),
        ),
    )

    /** Asignaciones peligrosas: `el.innerHTML = …`, `cmd.CommandText = …`. */
    private class Assign(val needle: String, val rule: String, val what: String, val mode: Mode, val server: Boolean)

    private val ASSIGNMENTS = mapOf(
        "js" to listOf(
            Assign(".innerHTML", "FC-XSS-01", "el HTML de la página", Mode.STRICT, false),
            Assign(".outerHTML", "FC-XSS-01", "el HTML de la página", Mode.STRICT, false),
        ),
        "csharp" to listOf(Assign(".CommandText", "FC-SQLI-01", SQL, Mode.SQL, true)),
    )

    fun check(a: FileAudit) {
        val fam = a.family ?: return
        val text = a.text
        val traceable = Taint.hasSources(a.src)
        SINKS[fam]?.forEach { s ->
            if (!text.contains(s.needle)) return@forEach
            // Sin ninguna entrada del usuario en el archivo, un punto que solo cuenta con ella no puede dar hallazgo.
            if (s.mode == Mode.SOURCE && !traceable) return@forEach
            if (s.requires.isNotEmpty() && s.requires.none { text.contains(it) }) return@forEach
            a.src.each(s.needle) { at -> call(a, s, at) }
        }
        ASSIGNMENTS[fam]?.forEach { s ->
            if (!text.contains(s.needle)) return@forEach
            a.src.each(s.needle) { at -> assignment(a, s, at) }
        }
        when (fam) {
            "js" -> frameworkHtml(a)
            "php" -> {
                phpInclude(a)
                if (traceable) phpEcho(a)
            }
            "python" -> if (traceable && text.contains("flask")) flaskReturn(a)
        }
    }

    private fun wordStart(text: String, at: Int): Boolean {
        if (at == 0) return true
        val c = text[at - 1]
        return !Src.isIdent(c) && c != '.' && c != '>' && c != ':' && c != '$' && c != '\\'
    }

    private fun call(a: FileAudit, s: Sink, at: Int) {
        val text = a.text
        if (s.word && !wordStart(text, at)) return
        val open = at + s.needle.length - 1
        if (text.getOrNull(open) != '(') return
        // Definiciones (`def open(`, `function exec(`) no son llamadas.
        val before = text.substring(maxOf(0, at - 10), at)
        if (before.endsWith("def ") || before.endsWith("function ") || before.endsWith("fun ")) return
        val close = a.src.close(open)
        if (close < 0) return
        val argsText = text.substring(open, close)
        if (s.requiresArg != null && !argsText.contains(s.requiresArg)) return
        if (s.unless.any { argsText.contains(it) }) return
        val args = a.src.args(open) ?: return
        val line = a.src.line(at)
        val label = s.needle.trimEnd('(').trimStart('.', '-', '>')
        if (s.mode == Mode.ALWAYS) {
            val flow = args.firstOrNull()?.let { Taint.follow(a.src, Taint.expr(a.src, it), line, s.what) }
            val certainty = if (flow?.source != null && !flow.local) Certainty.PROBABLE else Certainty.POSSIBLE
            a.add(
                s.rule, line, certainty, "Se usa $label(); con datos de fuera, puede crear objetos arbitrarios.",
                flow?.steps?.takeIf { flow.source != null } ?: listOf(FlowStep(line, "Se crea $label")),
            )
            return
        }
        val range = (if (s.arg < 0) args.lastOrNull() else args.getOrNull(s.arg)) ?: return
        var e = Taint.expr(a.src, range)
        if (s.mode == Mode.SQL) {
            e = Taint.resolve(a.src, e, line)
            if (!looksSql(e.literal)) return
        }
        report(a, s.rule, s.what, s.mode, s.server, s.mitigations, e, line, "Punto peligroso: $label().")
    }

    private fun assignment(a: FileAudit, s: Assign, at: Int) {
        val text = a.text
        var i = at + s.needle.length
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (text.getOrNull(i) == '+' && text.getOrNull(i + 1) == '=') i++
        if (text.getOrNull(i) != '=' || text.getOrNull(i + 1) == '=') return
        val line = a.src.line(at)
        var e = Taint.expr(a.src, Taint.statement(a.src, i + 1))
        if (s.mode == Mode.SQL) {
            e = Taint.resolve(a.src, e, line)
            if (!looksSql(e.literal)) return
        }
        report(a, s.rule, s.what, s.mode, s.server, emptyList(), e, line, "Se asigna ${s.needle.trimStart('.')}.")
    }

    private fun report(a: FileAudit, rule: String, what: String, mode: Mode, server: Boolean, mitigations: List<String>, e: Expr, line: Int, detail: String) {
        val flow = Taint.follow(a.src, e, line, what) ?: return
        if (server && flow.clientSource) return
        val hasSource = flow.source != null
        when (mode) {
            Mode.SOURCE -> if (!hasSource || flow.local) return
            Mode.CONSTRUCTED -> if (!hasSource && !e.constructed) return
            else -> {}
        }
        if (isSanitizedHtml(rule, e)) return
        var certainty = flow.certainty
        var extra = ""
        val fix = if (mitigations.isNotEmpty()) a.near(line, mitigations, before = 6, after = 2) else null
        if (fix != null && certainty == Certainty.PROBABLE) {
            certainty = Certainty.POSSIBLE
            extra = " Hay una comprobación cerca («${fix.trimEnd('(')}»); no se puede verificar que cubra este uso."
        }
        a.add(rule, line, certainty, detail + extra, flow.steps)
    }

    /** El HTML se limpia antes de insertarlo (DOMPurify, sanitize-html). */
    private fun isSanitizedHtml(rule: String, e: Expr): Boolean =
        rule.startsWith("FC-XSS") && e.parts.any { p -> p.contains("DOMPurify") || p.contains("sanitize") || p.contains("escape") }

    /** El texto fijo tiene forma de sentencia SQL. */
    fun looksSql(literal: String): Boolean {
        val l = literal.lowercase()
        return l.contains("select ") && l.contains(" from") || l.contains("insert into") || l.contains("update ") && l.contains(" set ") ||
            l.contains("delete from") || l.contains(" where ") || l.trimStart().startsWith("where ") || l.contains("order by ") ||
            l.contains("drop table") || l.contains("alter table") || l.contains("create table")
    }

    // ------------------------------------------------------------------ marcos de frontend

    /** `dangerouslySetInnerHTML={{ __html: x }}`, `v-html="x"`, `{@html x}`, `[innerHTML]="x"`. */
    private fun frameworkHtml(a: FileAudit) {
        val text = a.text
        if (text.contains("__html")) {
            a.src.each("__html") { at ->
                var i = at + 6
                while (i < text.length && text[i].isWhitespace()) i++
                if (text.getOrNull(i) != ':') return@each
                val e = Taint.expr(a.src, Taint.statement(a.src, i + 1))
                report(a, "FC-XSS-01", "dangerouslySetInnerHTML", Mode.STRICT, false, emptyList(), e, a.src.line(at), "Se usa dangerouslySetInnerHTML.")
            }
        }
        for ((needle, label) in listOf("v-html=\"" to "v-html", "{@html " to "{@html}")) {
            var i = text.indexOf(needle)
            var n = 0
            while (i >= 0 && n < 200) {
                val end = text.indexOf(if (needle.startsWith("{")) '}' else '"', i + needle.length)
                if (end > 0) {
                    val value = text.substring(i + needle.length, end).trim()
                    if (value.isNotEmpty() && !value.startsWith("'") && !value.contains("sanitize") && !value.contains("DOMPurify")) {
                        val line = a.src.line(i)
                        a.add("FC-XSS-01", line, Certainty.POSSIBLE, "Se usa $label con «${value.take(60)}».", listOf(FlowStep(line, "Un valor variable se inserta como HTML con $label")))
                    }
                }
                n++
                i = text.indexOf(needle, i + needle.length)
            }
        }
    }

    // ------------------------------------------------------------------ PHP

    private val INCLUDES = listOf("include_once", "require_once", "include", "require")

    private fun phpInclude(a: FileAudit) {
        val text = a.text
        for (kw in INCLUDES) {
            if (!text.contains(kw)) continue
            a.src.each(kw) { at ->
                if (!wordStart(text, at) || text.getOrNull(at + kw.length)?.let { Src.isIdent(it) } == true) return@each
                val line = a.src.line(at)
                val e = Taint.expr(a.src, Taint.statement(a.src, at + kw.length, maxLines = 3).let { r -> r.first..minOf(r.last, text.length - 1) })
                report(a, "FC-PATH-02", INCLUDE, Mode.CONSTRUCTED, true, PATH_FIX, e, line, "Se usa $kw con una ruta variable.")
            }
        }
    }

    private fun phpEcho(a: FileAudit) {
        val text = a.text
        for (kw in listOf("echo", "print", "<?=")) {
            if (!text.contains(kw)) continue
            a.src.each(kw) { at ->
                if (kw != "<?=" && (!wordStart(text, at) || text.getOrNull(at + kw.length)?.let { Src.isIdent(it) || it == '_' } == true)) return@each
                val line = a.src.line(at)
                val r = Taint.statement(a.src, at + kw.length, maxLines = 3)
                val e = Taint.expr(a.src, r)
                report(a, "FC-XSS-02", HTML, Mode.SOURCE, true, emptyList(), e, line, "Se escribe con $kw un dato de la petición sin escapar.")
            }
        }
    }

    /** Flask: `return "<h1>" + request.args.get('x')` devuelve HTML con el dato tal cual. */
    private fun flaskReturn(a: FileAudit) {
        a.src.each("return ") { at ->
            if (!wordStart(a.text, at)) return@each
            val e = Taint.expr(a.src, Taint.statement(a.src, at + 7, maxLines = 3))
            if (!e.literal.contains('<')) return@each
            report(a, "FC-XSS-02", HTML, Mode.SOURCE, true, emptyList(), e, a.src.line(at), "La vista devuelve HTML construido con un dato de la petición.")
        }
    }
}
