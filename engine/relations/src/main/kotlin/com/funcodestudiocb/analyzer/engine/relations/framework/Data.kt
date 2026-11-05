package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.api.DataEntityKind
import com.google.re2j.Pattern

/** Tablas y colecciones: dónde se definen (SQL, migraciones, modelos de ORM) y quién las lee o escribe. */
internal object Data {

    const val READ = "lectura"
    const val WRITE = "escritura"
    const val DELETE = "borrado"
    const val ACCESS = "acceso"

    private val CREATE_TABLE = Pattern.compile("""(?i)\bcreate\s+(?:temporary\s+|temp\s+|virtual\s+)?table\s+(?:if\s+not\s+exists\s+)?([`"\[]?[A-Za-z_][\w.$]*[`"\]]?)""")
    private val SQL_START = Pattern.compile("""(?i)^\s*(select|insert|update|delete|with|merge|replace|upsert)\b""")
    private val SQL_TABLES = Pattern.compile("""(?i)\b(from|join|into|update)\s+([`"\[]?[A-Za-z_][\w.$]*[`"\]]?)""")
    private val UPDATE_SET = Pattern.compile("""(?i)^\s*update\s+\S+\s+set\b""")
    private val SQL_SHAPE = Pattern.compile("""(?i)(\*|=|\?|:\w|\$\d|\bwhere\b|\bvalues\b|\bset\b|,|\blimit\b|\border\s+by\b|\bgroup\s+by\b|\bjoin\b)""")
    private val UPPER_SQL = Pattern.compile("""^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b[\s\S]*\b(FROM|INTO|SET)\b""")
    private val CODE = setOf(
        "javascript", "typescript", "tsx", "python", "java", "kotlin", "php", "csharp", "dart", "go", "ruby", "swift", "c", "cpp", "rust", "scala",
        "groovy", "vue", "svelte", "gdscript",
    )
    private val NOT_TABLES = setOf("select", "set", "where", "values", "dual", "lateral", "unnest", "only", "the", "a", "an", "this", "my", "your", "our", "and", "or", "to")

    private val ORM_READ = setOf(
        "filter", "get", "all", "exclude", "count", "first", "last", "values", "values_list", "exists", "aggregate", "annotate", "order_by",
        "where", "find", "findAll", "findOne", "findByPk", "findById", "findMany", "findFirst", "findUnique", "paginate", "pluck", "with",
        "query", "select", "countDocuments", "distinct", "latest", "oldest", "findOrFail", "firstOrFail", "orderBy", "whereIn", "has",
    )
    private val ORM_WRITE = setOf(
        "create", "update", "save", "bulk_create", "bulk_update", "get_or_create", "update_or_create", "insert", "insertMany", "insertOne",
        "updateOne", "updateMany", "upsert", "firstOrCreate", "updateOrCreate", "createMany", "findOneAndUpdate", "findByIdAndUpdate", "increment",
        "decrement", "add", "set",
    )
    private val ORM_DELETE = setOf("delete", "destroy", "deleteOne", "deleteMany", "remove", "findOneAndDelete", "findByIdAndDelete", "truncate", "forceDelete")

    fun operationOf(method: String): String = when (method) {
        in ORM_DELETE -> DELETE
        in ORM_WRITE -> WRITE
        in ORM_READ -> READ
        else -> ACCESS
    }

    fun extract(src: Src, out: FrameworkFacts) {
        val lang = src.language
        if (lang == "sql") {
            sqlFile(src, out)
            return
        }
        // SQL solo en código (no en documentación, datos ni esquemas exportados).
        if (lang !in CODE) return
        if (src.text.indexOf("TABLE") >= 0 || src.text.indexOf("table") >= 0 || src.text.indexOf("Table") >= 0) createTables(src, out, "SQL en el código")
        sqlLiterals(src, out)
        when (lang) {
            "python" -> python(src, out)
            "java", "kotlin" -> jvm(src, out)
            "csharp" -> csharp(src, out)
            "php" -> php(src, out)
            "javascript", "typescript", "tsx", "vue", "svelte" -> javascript(src, out)
            "dart" -> collections(src, out)
        }
        if (src.info.lower == "schema.prisma" || src.info.ext == "prisma") prisma(src, out)
    }

    // ------------------------------------------------------------------ SQL

    private fun clean(name: String): String = name.trim('`', '"', '[', ']').substringAfterLast('.').trim('`', '"', '[', ']')

    private fun createTables(src: Src, out: FrameworkFacts, source: String) {
        val m = CREATE_TABLE.matcher(src.text)
        while (m.find() && out.tables.size < MAX) {
            if (!src.isCode(m.start()) && src.language != "sql") continue
            // En el código, el SQL va en una cadena que empieza por CREATE TABLE (no dentro de otro texto).
            if (src.inString(m.start()) && !src.startsString(m.start())) continue
            val name = clean(m.group(1))
            // `CREATE TABLE IF NOT EXISTS ${nombre}`: el nombre se calcula al ejecutar.
            if (name.isEmpty() || name.lowercase() in NOT_TABLES || name.equals("if", true) || '$' in name || DYNAMIC in name) continue
            out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(m.start()), source)
        }
    }

    private fun sqlFile(src: Src, out: FrameworkFacts) {
        createTables(src, out, "CREATE TABLE")
        // Sentencias que leen o escriben datos (semillas, consultas guardadas).
        var start = 0
        val text = src.text
        var n = 0
        while (start < text.length && n++ < 5_000) {
            val end = text.indexOf(';', start).let { if (it < 0) text.length else it }
            val stmt = text.substring(start, end)
            val lead = stmt.trimStart()
            if (SQL_START.matcher(lead).find() && src.isCode(start + (stmt.length - lead.length))) {
                tablesOf(lead).forEach { (t, op) -> out.dbUses += DbUseFact(t, null, op, src.line(start + (stmt.length - lead.length)), "SQL") }
            }
            start = end + 1
        }
    }

    /** Tablas de una sentencia SQL y la operación sobre cada una. */
    fun tablesOf(sql: String): List<Pair<String, String>> {
        val first = SQL_START.matcher(sql).let { if (it.find()) it.group(1).lowercase() else return emptyList() }
        // Forma de SQL de verdad (no un texto como "Update available" o "Delete from cart").
        val lower = sql.lowercase()
        val shaped = when (first) {
            "select", "with" -> lower.contains(" from ") || lower.contains("\nfrom ")
            "insert", "replace", "merge", "upsert" -> lower.contains("into ")
            "update" -> UPDATE_SET.matcher(sql).find()
            else -> lower.contains("from ")
        }
        if (!shaped || !(UPPER_SQL.matcher(sql).find() || SQL_SHAPE.matcher(sql).find())) return emptyList()
        val op = when (first) {
            "select", "with" -> READ
            "delete" -> DELETE
            else -> WRITE
        }
        val out = mutableListOf<Pair<String, String>>()
        val m = SQL_TABLES.matcher(sql)
        while (m.find() && out.size < 10) {
            val kw = m.group(1).lowercase()
            val t = clean(m.group(2))
            if (t.isEmpty() || t.lowercase() in NOT_TABLES || DYNAMIC in t) continue
            // Una función (FROM generate_series(…)) no es una tabla.
            if (sql.getOrNull(m.end())?.let { it == '(' } == true) continue
            val thisOp = if (op == READ || (kw == "from" || kw == "join") && op != DELETE) READ else op
            out += t to (if (kw == "from" && op == DELETE) DELETE else thisOp)
        }
        return out.distinct()
    }

    /** SQL escrito en cadenas del código: `"SELECT … FROM users"`, `@Query("…")`, `execSQL("…")`. */
    private fun sqlLiterals(src: Src, out: FrameworkFacts) {
        val text = src.text
        var n = 0
        var i = 0
        // Una sola pasada: comillas seguidas (tras espacios) de SELECT, INSERT, UPDATE, DELETE o WITH.
        while (i < text.length && n < 2_000) {
            val c = text[i]
            if (c != '"' && c != '\'' && c != '`') {
                i++
                continue
            }
            var k = i + 1
            while (k < text.length && k - i < 40 && (text[k] == ' ' || text[k] == '\n' || text[k] == '\t' || text[k] == '\r')) k++
            if (k >= text.length || text[k].lowercaseChar() !in "siudw" || !SQL_WORDS.any { w -> text.regionMatches(k, w, 0, w.length, ignoreCase = true) && text.getOrNull(k + w.length)?.isWhitespace() == true }) {
                i++
                continue
            }
            n++
            val quoteAt = i
            i++
            if (src.inString(quoteAt)) continue
            if (!src.isCode(quoteAt)) continue
            // Comillas triples ("""…""") y prefijos (f"…", r"…", @"…", $"…").
            var at = quoteAt
            val q = src.text[quoteAt]
            if (at >= 2 && src.text[at - 1] == q && src.text[at - 2] == q) at -= 2
            while (at > 0 && src.text[at - 1] in "rRbBfFuU@$" && (at < 2 || !Src.isIdent(src.text[at - 2]))) at--
            val lit = src.literal(at) ?: continue
            tablesOf(lit.value).forEach { (t, op) -> out.dbUses += DbUseFact(t, null, op, src.line(quoteAt), "SQL") }
            i = maxOf(i, lit.end)
        }
    }

    private val SQL_WORDS = listOf("select", "insert", "update", "delete", "with")

    // ------------------------------------------------------------------ Python: Django, SQLAlchemy

    private fun python(src: Src, out: FrameworkFacts) {
        val text = src.text
        val classes = Regexes.PY_CLASS.matcher(text)
        while (classes.find()) {
            if (!src.isCode(classes.start()) || src.inString(classes.start())) continue
            val name = classes.group(1)
            val bases = classes.group(2)
            val body = text.substring(classes.end(), nextTopLevel(text, classes.end()))
            val tablename = Regexes.PY_TABLENAME.matcher(body).let { if (it.find()) it.group(1) else null }
            when {
                bases.contains("models.Model") || (bases.trim() == "Model" && src.has("django")) -> {
                    val dbTable = Regexes.PY_DB_TABLE.matcher(body).let { if (it.find()) it.group(1) else null }
                    val app = src.info.directory.substringAfterLast('/').let { if (it == "models") src.info.directory.substringBeforeLast('/').substringAfterLast('/') else it }
                    out.tables += TableDefFact(dbTable ?: "${app}_${name.lowercase()}", DataEntityKind.TABLE, src.line(classes.start()), "modelo de Django", name, dbTable == null)
                }
                tablename != null -> out.tables += TableDefFact(tablename, DataEntityKind.TABLE, src.line(classes.start()), "modelo de SQLAlchemy", name)
                bases.contains("db.Model") -> out.tables += TableDefFact(snake(name), DataEntityKind.TABLE, src.line(classes.start()), "modelo de Flask-SQLAlchemy", name, true)
            }
        }
        // Model.objects.filter(…)
        src.each(".objects.") { at ->
            val model = src.receiverBefore(at)?.substringAfterLast('.') ?: return@each
            if (!model.first().isUpperCase()) return@each
            val method = src.wordAt(at + 9) ?: return@each
            out.dbUses += DbUseFact(null, model, operationOf(method), src.line(at), "Django ORM")
        }
        // session.query(Item), db.query(Item), select(Item)
        for (fn in listOf("query(", "select(")) {
            src.each(fn) { at ->
                if (at > 0 && Src.isIdent(text[at - 1])) return@each
                val args = src.args(at + fn.length - 1) ?: return@each
                val model = args.firstOrNull()?.let { src.slice(it) }?.takeIf { it.isNotEmpty() && it.first().isUpperCase() && it.all { c -> Src.isIdent(c) } } ?: return@each
                out.dbUses += DbUseFact(null, model, READ, src.line(at), "SQLAlchemy")
            }
        }
        src.each(".query.") { at ->
            val model = src.receiverBefore(at) ?: return@each
            if (model.first().isUpperCase()) out.dbUses += DbUseFact(null, model, operationOf(src.wordAt(at + 7) ?: ""), src.line(at), "Flask-SQLAlchemy")
        }
        collections(src, out)
    }

    /** Fin del cuerpo de una clase de Python: la siguiente línea sin sangría. */
    private fun nextTopLevel(text: String, from: Int): Int {
        var i = text.indexOf('\n', from)
        while (i in 0 until text.length - 1) {
            val c = text[i + 1]
            if (!c.isWhitespace() && c != '#') return i + 1
            i = text.indexOf('\n', i + 1)
        }
        return text.length
    }

    // ------------------------------------------------------------------ JVM: JPA, Room, Spring Data

    private fun jvm(src: Src, out: FrameworkFacts) {
        val text = src.text
        src.each("@Entity") { at ->
            val after = at + 7
            if (after < text.length && Src.isIdent(text[after])) return@each
            if (!src.annotationAt(at)) return@each
            val args = if (text.getOrNull(src.skipWs(after)) == '(') src.args(src.skipWs(after)) else null
            val explicit = args?.let { Routes.namedArg(src, it, "tableName", "name") }?.let { src.literalArg(it)?.value }
            val cls = Regexes.CLASS_DECL.matcher(text.substring(after, minOf(text.length, after + 800))).let { if (it.find()) it.group(2) else null } ?: return@each
            val table = explicit ?: Regexes.lastMapping(text.substring(at, minOf(text.length, at + 600)).substringBefore("class "), "Table")?.takeIf { it.isNotEmpty() }
                ?: tableNameArg(src, at)
            val room = src.has("androidx.room")
            out.tables += TableDefFact(table ?: cls, DataEntityKind.TABLE, src.line(at), if (room) "entidad de Room" else "entidad JPA (@Entity)", cls, table == null)
        }
        // interface UserRepository extends JpaRepository<User, Long>
        val repo = Regexes.SPRING_REPOSITORY.matcher(text)
        while (repo.find()) {
            if (src.isCode(repo.start()) && !src.inString(repo.start())) out.dbUses += DbUseFact(null, repo.group(1), ACCESS, src.line(repo.start()), "Spring Data")
        }
        // DAO de Room: @Insert / @Update / @Delete fun x(user: User)
        for ((ann, op) in listOf("@Insert" to WRITE, "@Update" to WRITE, "@Delete" to DELETE, "@Upsert" to WRITE)) {
            src.each(ann) { at ->
                val after = at + ann.length
                if (after < text.length && Src.isIdent(text[after])) return@each
                if (!src.annotationAt(at)) return@each
                val paren = text.indexOf('(', text.indexOf("fun ", after).takeIf { it in after..after + 300 } ?: return@each)
                val type = Regexes.PARAM_TYPE.matcher(text.substring(paren, minOf(text.length, paren + 200))).let { if (it.find()) it.group(1) ?: it.group(2) else null } ?: return@each
                out.dbUses += DbUseFact(null, type, op, src.line(at), "Room")
            }
        }
        collections(src, out)
    }

    /** `@Table(name = "x")` cerca de la entidad. */
    private fun tableNameArg(src: Src, at: Int): String? {
        val i = src.text.indexOf("@Table(", maxOf(0, at - 300)).takeIf { it in (at - 300) until at + 400 } ?: return null
        val args = src.args(i + 6) ?: return null
        return Routes.namedArg(src, args, "name")?.let { src.literalArg(it)?.value }
    }

    // ------------------------------------------------------------------ C#: Entity Framework

    private fun csharp(src: Src, out: FrameworkFacts) {
        val m = Regexes.DBSET.matcher(src.text)
        while (m.find()) {
            if (src.isCode(m.start()) && !src.inString(m.start())) out.tables += TableDefFact(m.group(2), DataEntityKind.TABLE, src.line(m.start()), "DbSet de Entity Framework", m.group(1), true)
        }
        src.each("[Table(") { at ->
            if (!src.annotationAt(at)) return@each
            val name = src.args(at + 6)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
            val cls = Regexes.CS_CLASS.matcher(src.text.substring(at, minOf(src.text.length, at + 400))).let { if (it.find()) it.group(1) else null }
            out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(at), "[Table] de Entity Framework", cls)
        }
        src.each(".ToTable(") { at ->
            val name = src.args(at + 8)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
            val cls = src.text.lastIndexOf("Entity<", at).takeIf { it >= 0 && at - it < 300 }?.let { e -> src.wordAt(e + 7) }
            out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(at), "ToTable de Entity Framework", cls)
        }
        for (recv in listOf("_context.", "context.", "_db.", "db.", "_dbContext.", "dbContext.", "Context.")) {
            src.each(recv) { at ->
                if (at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
                val prop = src.wordAt(at + recv.length) ?: return@each
                if (!prop.first().isUpperCase() || prop in setOf("SaveChanges", "SaveChangesAsync", "Database", "Entry", "Set", "Add", "Remove", "Update", "Attach")) return@each
                val method = src.text.getOrNull(at + recv.length + prop.length).takeIf { it == '.' }?.let { src.wordAt(at + recv.length + prop.length + 1) } ?: ""
                out.dbUses += DbUseFact(prop, null, csOperation(method), src.line(at), "Entity Framework")
            }
            src.each("${recv}Set<") { at ->
                val type = src.wordAt(at + recv.length + 4) ?: return@each
                out.dbUses += DbUseFact(null, type, ACCESS, src.line(at), "Entity Framework")
            }
        }
        collections(src, out)
    }

    private fun csOperation(method: String): String = when {
        method.startsWith("Add") || method.startsWith("Update") || method.startsWith("Attach") -> WRITE
        method.startsWith("Remove") -> DELETE
        method.isEmpty() -> ACCESS
        else -> READ
    }

    // ------------------------------------------------------------------ PHP: Eloquent, migraciones, DB::table

    private fun php(src: Src, out: FrameworkFacts) {
        val text = src.text
        val m = Regexes.ELOQUENT_MODEL.matcher(text)
        while (m.find()) {
            if (!src.isCode(m.start()) || src.inString(m.start())) continue
            val cls = m.group(1)
            val explicit = Regexes.PHP_TABLE.matcher(text.substring(m.end(), minOf(text.length, m.end() + 3_000))).let { if (it.find()) it.group(1) else null }
            out.tables += TableDefFact(explicit ?: plural(snake(cls)), DataEntityKind.TABLE, src.line(m.start()), "modelo de Eloquent", cls, explicit == null)
        }
        src.each("Schema::create(") { at ->
            val name = src.args(at + 14)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
            out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(at), "migración de Laravel")
        }
        src.each("DB::table(") { at ->
            val name = src.args(at + 9)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
            out.dbUses += DbUseFact(name, null, chainOperation(src, at), src.line(at), "Query Builder de Laravel")
        }
        // Model::where(…), Model::create(…)
        src.eachMember("::") { model, method, at ->
            if (model.isEmpty() || !model.first().isUpperCase() || model in PHP_FACADES || model.contains('.')) return@eachMember
            if (method !in ORM_READ && method !in ORM_WRITE && method !in ORM_DELETE) return@eachMember
            out.dbUses += DbUseFact(null, model, operationOf(method), src.line(at), "Eloquent")
        }
    }

    private val PHP_FACADES = setOf(
        "Route", "DB", "Http", "Schema", "Cache", "Auth", "Log", "Storage", "Str", "Arr", "Validator", "Hash", "Mail", "Event", "Gate", "Config", "Session",
        "Request", "Response", "View", "Redirect", "URL", "Artisan", "Queue", "Bus", "Notification", "Password", "Broadcast", "App", "File", "Lang",
        "Crypt", "Cookie", "Blade", "Carbon", "Inertia", "self", "static", "parent", "Collection", "Process", "RateLimiter",
    )

    /** Operación de una cadena de consulta: `->get()`, `->insert(…)`, `->delete()`. */
    private fun chainOperation(src: Src, at: Int): String {
        val end = src.text.indexOf(';', at).let { if (it < 0) minOf(src.text.length, at + 400) else minOf(it, at + 400) }
        val chain = src.text.substring(at, end)
        return when {
            ORM_DELETE.any { chain.contains("->$it(") || chain.contains(".$it(") } -> DELETE
            ORM_WRITE.any { chain.contains("->$it(") || chain.contains(".$it(") } -> WRITE
            else -> READ
        }
    }

    // ------------------------------------------------------------------ JavaScript: Sequelize, Mongoose, TypeORM, Prisma, Knex, Supabase

    private fun javascript(src: Src, out: FrameworkFacts) {
        val text = src.text
        src.each(".define(") { at ->
            if (!src.has("sequelize") && !src.has("Sequelize")) return@each
            val args = src.args(at + 7) ?: return@each
            val model = args.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
            val table = args.getOrNull(2)?.let { opts -> optionLiteral(src, opts, "tableName") }
            out.tables += TableDefFact(table ?: plural(model), DataEntityKind.TABLE, src.line(at), "modelo de Sequelize", model, table == null)
        }
        src.each(".init(") { at ->
            if (!src.has("sequelize") && !src.has("Sequelize")) return@each
            val model = src.receiverBefore(at) ?: return@each
            if (!model.first().isUpperCase()) return@each
            val args = src.args(at + 5) ?: return@each
            val table = args.getOrNull(1)?.let { optionLiteral(src, it, "tableName") }
            out.tables += TableDefFact(table ?: plural(model), DataEntityKind.TABLE, src.line(at), "modelo de Sequelize", model, table == null)
        }
        for (fn in listOf("mongoose.model(", "model(")) {
            src.eachWord(fn) { at ->
                if (fn == "model(" && !src.has("mongoose")) return@eachWord
                val args = src.args(at + fn.length - 1) ?: return@eachWord
                if (args.size < 2) return@eachWord
                val model = args.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@eachWord
                val collection = args.getOrNull(2)?.let { src.literalArg(it)?.value }
                out.tables += TableDefFact(collection ?: plural(model.lowercase()), DataEntityKind.COLLECTION, src.line(at), "modelo de Mongoose", model, collection == null)
            }
        }
        src.each("@Entity(") { at ->
            if (!src.annotationAt(at)) return@each
            val args = src.args(at + 7) ?: return@each
            val name = args.firstOrNull()?.let { a -> src.literalArg(a)?.value ?: optionLiteral(src, a, "name") }
            val cls = Regexes.CLASS_NAME.matcher(text.substring(at, minOf(text.length, at + 600))).let { if (it.find()) it.group(1) else null } ?: return@each
            out.tables += TableDefFact(name ?: cls, DataEntityKind.TABLE, src.line(at), "entidad de TypeORM", cls, name == null)
        }
        // Modelo.findAll(…), db.User.create(…)
        src.eachMember(".") { recv, method, at ->
            val model = recv.substringAfterLast('.')
            if (model.isEmpty() || !model.first().isUpperCase() || model in JS_GLOBALS) return@eachMember
            if (method !in ORM_READ && method !in ORM_WRITE && method !in ORM_DELETE) return@eachMember
            if (text.getOrNull(at + 1 + method.length) != '(') return@eachMember
            out.dbUses += DbUseFact(null, model, operationOf(method), src.line(at), "ORM")
        }
        for (fn in listOf("getRepository(", "InjectRepository(", "getCustomRepository(")) {
            src.each(fn) { at ->
                val model = src.args(at + fn.length - 1)?.firstOrNull()?.let { src.slice(it) }?.takeIf { it.isNotEmpty() && it.first().isUpperCase() && it.all { c -> Src.isIdent(c) } } ?: return@each
                out.dbUses += DbUseFact(null, model, ACCESS, src.line(at), "TypeORM")
            }
        }
        // prisma.user.findMany(…)
        for (recv in listOf("prisma.", "this.prisma.", "db.")) {
            src.each(recv) { at ->
                if (at > 0 && (Src.isIdent(text[at - 1]) || (text[at - 1] == '.' && !recv.startsWith("this.")))) return@each
                if (recv == "db." && !src.has("prisma")) return@each
                val model = src.wordAt(at + recv.length) ?: return@each
                if (model.startsWith("$")) return@each
                val method = text.getOrNull(at + recv.length + model.length).takeIf { it == '.' }?.let { src.wordAt(at + recv.length + model.length + 1) } ?: return@each
                out.dbUses += DbUseFact(null, model, operationOf(method), src.line(at), "Prisma")
            }
        }
        // Knex y Supabase: knex('users'), supabase.from('users').select()
        if (src.has("knex") || src.has("supabase")) {
            src.each(".from(") { at ->
                val name = src.args(at + 5)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
                out.dbUses += DbUseFact(name, null, chainOperation(src, at), src.line(at), if (src.has("supabase")) "Supabase" else "Knex")
            }
            src.each(".createTable(") { at ->
                val name = src.args(at + 12)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
                out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(at), "migración de Knex")
            }
        }
        if (src.has("queryInterface")) {
            src.each("queryInterface.createTable(") { at ->
                val name = src.args(at + "queryInterface.createTable".length)?.firstOrNull()?.let { src.literalArg(it)?.value } ?: return@each
                out.tables += TableDefFact(name, DataEntityKind.TABLE, src.line(at), "migración de Sequelize")
            }
        }
        collections(src, out)
    }

    private val JS_GLOBALS = setOf(
        "Object", "Array", "Promise", "JSON", "Math", "Date", "String", "Number", "Boolean", "Map", "Set", "Reflect", "Symbol", "Intl", "React", "Vue",
        "Array", "Buffer", "URL", "Error", "Event", "Response", "Request", "Headers", "Document", "Window", "History", "Notification", "WeakMap",
    )

    private fun optionLiteral(src: Src, r: IntRange, key: String): String? {
        val k = src.skipWs(r.first)
        if (src.text.getOrNull(k) != '{') return null
        val end = src.close(k)
        if (end < 0) return null
        return Routes.namedArg(src, src.splitTop(k + 1, end, ','), key)?.let { src.literalArg(it)?.value }
    }

    // ------------------------------------------------------------------ Firestore y MongoDB

    /** `collection('users')` de Firestore o MongoDB: colecciones que se crean al usarlas. */
    private fun collections(src: Src, out: FrameworkFacts) {
        if (!(src.has("collection(") || src.has("Collection("))) return
        val firebase = src.has("firestore") || src.has("Firestore") || src.has("firebase")
        val mongo = src.has("mongo") || src.has("Mongo")
        if (!firebase && !mongo) return
        for (fn in listOf(".collection(", "collection(", ".GetCollection<", ".getCollection(")) {
            src.each(fn) { at ->
                if (fn == "collection(" && at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
                val open = if (fn.endsWith("<")) src.text.indexOf('(', at).takeIf { it in at..at + 120 } ?: return@each else at + fn.length - 1
                val args = src.args(open) ?: return@each
                // collection(db, 'users') del SDK modular de Firebase: el nombre es el último literal.
                // 'users/123/orders' → la colección de primer nivel.
                val name = args.mapNotNull { src.literalArg(it) }.lastOrNull { !it.dynamic }?.value?.substringBefore('/') ?: return@each
                if (name.isEmpty()) return@each
                out.dbUses += DbUseFact(name, null, chainOperation(src, at), src.line(at), if (firebase) "Firestore" else "MongoDB", DataEntityKind.COLLECTION)
            }
        }
    }

    // ------------------------------------------------------------------ Prisma

    private fun prisma(src: Src, out: FrameworkFacts) {
        val m = Regexes.PRISMA_MODEL.matcher(src.text)
        while (m.find()) {
            val body = src.text.substring(m.end(), src.text.indexOf('}', m.end()).let { if (it < 0) src.text.length else it })
            val mapped = Regexes.PRISMA_MAP.matcher(body).let { if (it.find()) it.group(1) else null }
            out.tables += TableDefFact(mapped ?: m.group(1), DataEntityKind.TABLE, src.line(m.start()), "modelo de Prisma", m.group(1), mapped == null)
        }
    }

    // ------------------------------------------------------------------ nombres por convención

    fun snake(name: String): String {
        val sb = StringBuilder()
        name.forEachIndexed { i, c ->
            if (c.isUpperCase() && i > 0 && (name[i - 1].isLowerCase() || name.getOrNull(i + 1)?.isLowerCase() == true)) sb.append('_')
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** Plural inglés sencillo (Eloquent, Sequelize, Mongoose). */
    fun plural(name: String): String = when {
        name.endsWith("s") -> name
        name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" -> name.dropLast(1) + "ies"
        name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh") -> name + "es"
        else -> name + "s"
    }

    /** Nombre comparable: sin mayúsculas, guiones bajos ni comillas (`UserAccount` = `user_account`). */
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private const val MAX = 2_000
}
