package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecretType
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.security.SecurityRules
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityAuditTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun cadaReglaTieneSuFichaCompleta() {
        val ids = SecurityRules.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Identificadores repetidos")
        for (r in SecurityRules.ALL) {
            assertTrue(RecordValidator.RULE_ID_PATTERN.matches(r.id), r.id)
            assertTrue(r.name.isNotBlank() && r.explanation.isNotBlank() && r.consequences.isNotBlank() && r.recommendation.isNotBlank(), r.id)
            if (r.category != SecurityCategory.SUPPRESSION) {
                assertNotNull(r.cwe, r.id)
                assertNotNull(r.owasp, r.id)
                assertTrue(r.references.size in 1..RecordValidator.MAX_REFERENCES, r.id)
            }
        }
    }

    @Test
    fun inyeccionesConRecorridoDelDatoYCasosSeguros() {
        val a = audit(
            dir,
            mapOf(
                "src/server.js" to """
                    const express = require('express');
                    const { exec } = require('child_process');
                    const fs = require('fs');
                    const db = require('./db');
                    const router = express.Router();
                    router.get('/users/:id', (req, res) => {
                      const sql = "SELECT * FROM users WHERE id = " + req.params.id;
                      db.query(sql, (err, rows) => res.json(rows));
                    });
                    router.get('/orders', async (req, res) => {
                      const rows = await db.query(`SELECT * FROM orders WHERE owner = '${'$'}{req.query.owner}'`);
                      exec('ls ' + req.query.dir, (e, out) => res.send(out));
                      fs.readFile(req.query.file, 'utf8', (e, data) => res.send(data));
                      fetch(req.query.url).then(r => r.text());
                      res.redirect(req.query.next);
                      res.send('<h1>Hola ' + req.query.name + '</h1>');
                    });
                    function calc(expr) { return eval(expr); }
                    module.exports = router;
                """,
                "src/safe.js" to """
                    const db = require('./db');
                    const { execFile } = require('child_process');
                    async function getUser(req, res) {
                      const rows = await db.query('SELECT * FROM users WHERE id = ?', [req.params.id]);
                      const n = parseInt(req.query.n, 10);
                      const more = await db.query("SELECT * FROM t LIMIT " + n);
                      execFile('ls', ['-la', req.query.dir]);
                      const total = await db.query("SELECT COUNT(*) FROM " + TABLE_NAME);
                      const re = /x/.exec(req.query.s);
                      return rows;
                    }
                """,
                "api/app.py" to """
                    import os, pickle, sqlite3
                    from flask import Flask, request, render_template_string
                    app = Flask(__name__)

                    @app.route('/user')
                    def user():
                        uid = request.args.get('id')
                        cur = sqlite3.connect('a.db').cursor()
                        cur.execute(f"SELECT * FROM users WHERE id = {uid}")
                        cur.execute("SELECT * FROM users WHERE id = ?", (uid,))
                        os.system("ping -c 1 " + request.args['host'])
                        obj = pickle.loads(request.data)
                        return render_template_string("<p>" + request.args.get('name') + "</p>")

                    @app.route('/item/<item_id>')
                    def item(item_id):
                        cur = sqlite3.connect('a.db').cursor()
                        cur.execute("SELECT * FROM items WHERE id = " + item_id)
                        return 'ok'
                """,
                "web/index.php" to """
                    <?php
                    ${'$'}id = ${'$'}_GET['id'];
                    ${'$'}res = mysqli_query(${'$'}conn, "SELECT * FROM products WHERE id = " . ${'$'}id);
                    include ${'$'}_GET['page'] . '.php';
                    echo ${'$'}_GET['q'];
                    echo htmlspecialchars(${'$'}_GET['safe']);
                    ${'$'}data = unserialize(${'$'}_COOKIE['cart']);
                    require __DIR__ . '/vendor/autoload.php';
                    ${'$'}stmt = ${'$'}pdo->prepare('SELECT * FROM t WHERE id = ?');
                """,
                "api/src/main/java/com/demo/UserController.java" to """
                    package com.demo;
                    import org.springframework.web.bind.annotation.*;
                    @RestController
                    public class UserController {
                        @GetMapping("/api/users")
                        public String find(@RequestParam String name) throws Exception {
                            Statement st = c.createStatement();
                            ResultSet rs = st.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
                            PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE name = ?");
                            return "ok";
                        }
                    }
                """,
            ),
        )
        assertCompleteCards(a)
        // JavaScript: la consulta se construye en la línea anterior con req.params.
        val sqli = a.find("FC-SQLI-01", "src/server.js", 8)
        assertEquals(Certainty.PROBABLE, sqli.certainty)
        assertEquals(listOf(7, 8), sqli.flow.map { it.line })
        assertTrue(sqli.snippet!!.contains("db.query(sql"))
        assertEquals(Severity.HIGH, sqli.severity)
        a.find("FC-SQLI-01", "src/server.js", 11)
        assertEquals(Certainty.PROBABLE, a.find("FC-CMD-01", "src/server.js", 12).certainty)
        a.find("FC-PATH-01", "src/server.js", 13)
        a.find("FC-SSRF-01", "src/server.js", 14)
        a.find("FC-REDIRECT-01", "src/server.js", 15)
        a.find("FC-XSS-02", "src/server.js", 16)
        // eval de un parámetro sin origen conocido: posible, no probable.
        assertEquals(Certainty.POSSIBLE, a.find("FC-CODE-01", "src/server.js", 18).certainty)
        // Consultas con parámetros, número convertido, constante, execFile con lista y RegExp.exec: nada.
        assertTrue(a.at("src/safe.js").none { it.category == SecurityCategory.SQL_INJECTION || it.category == SecurityCategory.COMMAND_EXECUTION }, a.describe(a.at("src/safe.js")))

        // Python: f-string, os.system, pickle y plantilla construida; el parámetro de la ruta también es entrada.
        assertEquals(Certainty.PROBABLE, a.find("FC-SQLI-01", "api/app.py", 9).certainty)
        assertTrue(a.at("api/app.py", 10).none { it.ruleId == "FC-SQLI-01" }, "Consulta con parámetros")
        a.find("FC-CMD-01", "api/app.py", 11)
        a.find("FC-DESER-01", "api/app.py", 12)
        a.find("FC-SSTI-01", "api/app.py", 13)
        val route = a.find("FC-SQLI-01", "api/app.py", 18)
        assertEquals(Certainty.PROBABLE, route.certainty)
        assertTrue(route.flow.first().description.contains("parámetro de la ruta"), route.flow.toString())

        // PHP: concatenación con ., include, echo sin escapar (y con escape no), unserialize; require con __DIR__ no.
        a.find("FC-SQLI-01", "web/index.php", 3)
        assertEquals(Severity.CRITICAL, a.find("FC-PATH-02", "web/index.php", 4).severity)
        a.find("FC-XSS-02", "web/index.php", 5)
        assertTrue(a.at("web/index.php", 6).isEmpty(), a.describe(a.at("web/index.php", 6)))
        a.find("FC-DESER-01", "web/index.php", 7)
        assertTrue(a.at("web/index.php", 8).isEmpty() && a.at("web/index.php", 9).isEmpty())

        // Java: @RequestParam es entrada del usuario; prepareStatement con ? no.
        val java = a.find("FC-SQLI-01", "api/src/main/java/com/demo/UserController.java", 8)
        assertEquals(Certainty.PROBABLE, java.certainty)
        assertTrue(java.flow.first().description.contains("@RequestParam"))
        assertNull(java.downgraded, "com/demo es un paquete, no código de ejemplo")
        assertTrue(a.at("api/src/main/java/com/demo/UserController.java", 9).isEmpty())
    }

    @Test
    fun secretosEnmascaradosConHuellaRebajasYNingunValorCompleto() {
        // Valores de prueba con formato real, construidos al ejecutar para que el código fuente no contenga ninguno.
        val stripe = "sk_" + "live_" + "51Habcdefghijklmnopqrstuvwx"
        val github = "ghp" + "_abcdefghijklmnopqrstuvwxyz0123456789"
        val google = "AIza" + "SyD-1234567890abcdefghijklmnopqrstu"
        val password = "Pr0duccion!2024x"
        val signing = "super-secreto-de-firma-2024"
        val flask = "clave-de-sesion-flask-123"
        val a = audit(
            dir,
            mapOf(
                "config/keys.js" to """
                    const stripe = "$stripe";
                    const aws = "AKIAIOSFODNN7EXAMPLE";
                """,
                "src/other.js" to """
                    export const key = "$stripe";
                """,
                ".env" to """
                    DB_HOST=localhost
                    DB_PASSWORD=$password
                    NEXT_PUBLIC_STRIPE_SECRET=abc
                    APP_DEBUG=true
                """,
                ".env.example" to "DB_PASSWORD=changeme",
                "src/auth.js" to """
                    const jwt = require('jsonwebtoken');
                    function token(user) {
                      return jwt.sign({ id: user.id }, '$signing', { expiresIn: '1h' });
                    }
                """,
                "api/app.py" to """
                    from flask import Flask
                    app = Flask(__name__)
                    app.secret_key = '$flask'
                """,
                "test/fixtures.js" to """
                    const githubToken = "$github";
                """,
                "app/google-services.json" to """
                    { "client": [ { "api_key": [ { "current_key": "$google" } ] } ] }
                """,
            ),
            binary = mapOf("release.keystore" to byteArrayOf(-2, -19, -2, -19, 0, 0, 0, 2, 1, 2, 3), "debug.keystore" to byteArrayOf(-2, -19, -2, -19, 0, 1)),
        )
        assertCompleteCards(a)
        for (secret in listOf(stripe, password, signing, flask, github, google)) {
            assertFalse(a.json.contains(secret), "Secreto completo en la salida: ${secret.take(4)}…")
        }
        val s1 = a.find("FC-SECRET-02", "config/keys.js", 1)
        val s2 = a.find("FC-SECRET-02", "src/other.js", 1)
        assertEquals(SecretType.STRIPE_KEY, s1.secretType)
        assertEquals(Severity.HIGH, s1.severity)
        assertEquals(Certainty.CONFIRMED, s1.certainty)
        assertTrue(s1.masked!!.startsWith("sk_l") && s1.masked!!.count { it != RecordValidator.MASK_CHAR } <= 6)
        assertTrue(RecordValidator.FINGERPRINT_PATTERN.matches(s1.fingerprint!!))
        assertEquals(s1.fingerprint, s2.fingerprint, "El mismo secreto en dos archivos tiene la misma huella")
        // Ejemplo de la documentación de AWS: se rebaja a informativo, no se oculta.
        val aws = a.find("FC-SECRET-02", "config/keys.js", 2)
        assertEquals(Severity.INFO, aws.severity)
        assertNotNull(aws.downgraded)
        assertEquals(Severity.HIGH, a.find("FC-SECRET-03", ".env", 2).severity)
        assertEquals(Severity.INFO, a.find("FC-SECRET-03", ".env.example", 1).severity)
        a.find("FC-PUBENV-01", ".env", 3)
        a.find("FC-DEBUG-01", ".env", 4)
        assertEquals(Severity.HIGH, a.find("FC-FILE-01", ".env").severity)
        a.none("FC-FILE-01", ".env.example")
        // Claves de firma: el literal se oculta también en el fragmento.
        val jwt = a.find("FC-AUTH-03", "src/auth.js", 3)
        assertTrue(jwt.snippet!!.contains("jwt.sign") && !jwt.snippet!!.contains(signing))
        a.find("FC-AUTH-03", "api/app.py", 3)
        // En pruebas: se rebaja un nivel y se dice por qué.
        val test = a.find("FC-SECRET-02", "test/fixtures.js", 1)
        assertEquals(Severity.MEDIUM, test.severity)
        assertEquals("archivo de pruebas", test.downgraded)
        // Clave de cliente de Firebase en google-services.json: informativa.
        assertEquals(Severity.INFO, a.find("FC-SECRET-02", "app/google-services.json").severity)
        assertEquals(Severity.HIGH, a.find("FC-FILE-02", "release.keystore").severity)
        assertEquals(Severity.INFO, a.find("FC-FILE-02", "debug.keystore").severity)
    }

    @Test
    fun configuracionDeAndroidDockerCiFirebaseYSupabase() {
        val a = audit(
            dir,
            mapOf(
                "app/src/main/AndroidManifest.xml" to """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.demo">
                        <uses-permission android:name="android.permission.CAMERA" />
                        <uses-permission android:name="android.permission.READ_CONTACTS" />
                        <application android:allowBackup="true" android:debuggable="true" android:usesCleartextTraffic="true">
                            <activity android:name=".MainActivity" android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                            <activity android:name=".ShareActivity" android:exported="true" />
                            <service android:name=".SyncService" android:exported="true" android:permission="com.demo.SYNC" />
                            <activity android:name=".Internal" android:exported="false" />
                        </application>
                    </manifest>
                """,
                "app/src/main/java/com/demo/MainActivity.kt" to """
                    package com.demo
                    import android.webkit.WebView
                    import androidx.camera.core.ImageCapture
                    class MainActivity {
                        fun setup(web: WebView) {
                            web.settings.javaScriptEnabled = true
                            web.addJavascriptInterface(Bridge(), "bridge")
                            web.settings.allowFileAccess = true
                            val capture = ImageCapture.Builder().build()
                        }
                    }
                """,
                "app/src/main/res/xml/network_security_config.xml" to """
                    <network-security-config>
                        <base-config cleartextTrafficPermitted="true" />
                    </network-security-config>
                """,
                "docker-compose.yml" to """
                    services:
                      app:
                        image: demo
                        privileged: true
                """,
                "Dockerfile" to """
                    FROM node:20 AS build
                    USER node
                    FROM node:20-slim
                    COPY . .
                """,
                "safe/Dockerfile" to """
                    FROM node:20
                    USER node
                """,
                ".github/workflows/ci.yml" to """
                    on: pull_request_target
                    jobs:
                      build:
                        runs-on: ubuntu-latest
                        steps:
                          - uses: actions/checkout@v4
                            with:
                              ref: ${'$'}{{ github.event.pull_request.head.sha }}
                          - run: |
                              echo "Título: ${'$'}{{ github.event.pull_request.title }}"
                          - name: Seguro
                            env:
                              TITLE: ${'$'}{{ github.event.pull_request.title }}
                            run: echo "${'$'}TITLE"
                """,
                "firestore.rules" to """
                    service cloud.firestore {
                      match /databases/{database}/documents {
                        match /public/{doc} { allow read: if request.auth != null; }
                        match /{document=**} {
                          allow read, write: if true;
                        }
                      }
                    }
                """,
                "supabase/migrations/001_init.sql" to """
                    create table public.profiles (id uuid primary key, name text);
                    create table public.notes (id uuid primary key, body text);
                    alter table public.notes enable row level security;
                    alter table public.other disable row level security;
                """,
                "settings.py" to "DEBUG = True\nALLOWED_HOSTS = ['*']",
                "src/main/resources/application.properties" to "management.endpoints.web.exposure.include=*",
                "src/main/resources/application-dev.properties" to "management.endpoints.web.exposure.include=*",
            ),
        )
        assertCompleteCards(a)
        val m = "app/src/main/AndroidManifest.xml"
        a.find("FC-ANDROID-01", m, 4)
        assertEquals(Severity.HIGH, a.find("FC-DEBUG-01", m, 4).severity)
        a.find("FC-TLS-02", m, 4)
        a.find("FC-ANDROID-02", m, 11)
        assertEquals(1, a.findings.count { it.ruleId == "FC-ANDROID-02" }, "El lanzador y el servicio con permiso no cuentan")
        a.find("FC-ANDROID-03", m, 2)
        // CAMERA se usa (ImageCapture); READ_CONTACTS no aparece en el código.
        a.find("FC-ANDROID-04", m, 3)
        assertTrue(a.at(m, 2).none { it.ruleId == "FC-ANDROID-04" })
        assertEquals(Certainty.CONFIRMED, a.find("FC-WEBVIEW-01", "app/src/main/java/com/demo/MainActivity.kt", 7).certainty)
        a.find("FC-WEBVIEW-02", "app/src/main/java/com/demo/MainActivity.kt", 8)
        a.find("FC-TLS-02", "app/src/main/res/xml/network_security_config.xml", 2)
        a.find("FC-DOCKER-01", "docker-compose.yml", 4)
        a.find("FC-DOCKER-02", "Dockerfile", 3)
        a.none("FC-DOCKER-02", "safe/Dockerfile")
        a.find("FC-CI-01", ".github/workflows/ci.yml", 8)
        a.find("FC-CI-02", ".github/workflows/ci.yml", 10)
        assertTrue(a.at(".github/workflows/ci.yml", 13).isEmpty(), "Pasar el dato por env es la forma segura")
        assertEquals(Severity.CRITICAL, a.find("FC-FIREBASE-01", "firestore.rules", 5).severity)
        assertTrue(a.at("firestore.rules", 3).isEmpty())
        a.find("FC-SUPABASE-01", "supabase/migrations/001_init.sql", 4)
        assertEquals(Certainty.POSSIBLE, a.find("FC-SUPABASE-01", "supabase/migrations/001_init.sql", 1).certainty)
        assertTrue(a.at("supabase/migrations/001_init.sql", 2).isEmpty(), "notes tiene RLS")
        a.find("FC-DEBUG-01", "settings.py", 1)
        a.find("FC-CONF-01", "src/main/resources/application.properties", 1)
        val dev = a.find("FC-CONF-01", "src/main/resources/application-dev.properties", 1)
        assertEquals(Severity.LOW, dev.severity)
        assertNotNull(dev.downgraded)
    }

    @Test
    fun webCorsCookiesSesionesCsrfYValidacion() {
        val a = audit(
            dir,
            mapOf(
                "package.json" to """{ "name": "web", "dependencies": { "express": "4.19.0", "express-session": "1.18.0" } }""",
                "src/app.js" to """
                    const express = require('express');
                    const session = require('express-session');
                    const cors = require('cors');
                    const app = express();
                    app.use(cors({ origin: '*', credentials: true }));
                    app.use(session({ secret: process.env.S, cookie: { httpOnly: false, secure: false } }));
                    https.get(url, { rejectUnauthorized: false });
                    app.post('/items', async (req, res) => {
                      const item = await Item.create(req.body);
                      console.log('login', password);
                      console.log('Contraseña incorrecta');
                      res.json(item);
                    });
                    function resetToken() { const resetCode = Math.random().toString(36); return resetCode; }
                    function shuffle(list) { return list.sort(() => Math.random() - 0.5); }
                """,
                "web/client.js" to """
                    function show(msg) {
                      document.getElementById('out').innerHTML = location.hash.slice(1);
                      localStorage.setItem('access_token', msg.token);
                      localStorage.setItem('theme', 'dark');
                      el.innerHTML = DOMPurify.sanitize(msg.html);
                    }
                """,
                "src/Security.java" to """
                    class SecurityConfig {
                        void configure(HttpSecurity http) throws Exception {
                            http.csrf().disable();
                        }
                        String hash(String password) throws Exception {
                            return DigestUtils.md5Hex(password);
                        }
                        String fileSum(byte[] data) { return DigestUtils.md5Hex(data); }
                    }
                """,
                "src/Patterns.kt" to """
                    // Texto que solo describe patrones: no es código que los use.
                    val patterns = listOf("rejectUnauthorized:false", "csrf().disable()", "origin:true", "eval(x)")
                """,
            ),
        )
        assertCompleteCards(a)
        a.find("FC-CORS-01", "src/app.js", 5)
        a.find("FC-COOKIE-01", "src/app.js", 6)
        a.find("FC-COOKIE-02", "src/app.js", 6)
        a.find("FC-TLS-01", "src/app.js", 7)
        a.find("FC-MASS-01", "src/app.js", 9)
        a.find("FC-LOG-01", "src/app.js", 10)
        assertTrue(a.at("src/app.js", 11).isEmpty(), "Un texto fijo no es un dato sensible")
        a.find("FC-AUTH-04", "src/app.js", 14)
        assertTrue(a.at("src/app.js", 15).isEmpty(), "Math.random para barajar no es un secreto")
        a.find("FC-CSRF-02", "src/app.js")
        a.find("FC-VALID-01", "src/app.js")
        assertEquals(Certainty.PROBABLE, a.find("FC-XSS-01", "web/client.js", 2).certainty)
        a.find("FC-SESSION-01", "web/client.js", 3)
        assertTrue(a.at("web/client.js", 4).isEmpty() && a.at("web/client.js", 5).isEmpty(), a.describe(a.at("web/client.js")))
        a.find("FC-CSRF-01", "src/Security.java", 3)
        a.find("FC-AUTH-01", "src/Security.java", 6)
        assertTrue(a.at("src/Security.java", 8).isEmpty(), "MD5 de un archivo no es un hash de contraseña")
        assertTrue(a.at("src/Patterns.kt").isEmpty(), "Patrones dentro de cadenas: " + a.describe(a.at("src/Patterns.kt")))
    }

    @Test
    fun controlDeAccesoConProteccionesVisibles() {
        val a = audit(
            dir,
            mapOf(
                "src/routes.js" to """
                    const express = require('express');
                    const router = express.Router();
                    function requireAuth(req, res, next) { next(); }
                    router.get('/orders', requireAuth, (req, res) => res.json([]));
                    router.get('/profile', requireAuth, (req, res) => res.json({}));
                    router.get('/invoices', requireAuth, (req, res) => res.json([]));
                    router.get('/payments', (req, res) => res.json([]));
                    router.get('/login', (req, res) => res.json({}));
                    router.post('/admin/delete', (req, res) => res.json({}));
                    router.post('/admin/ok', requireAuth, (req, res) => res.json({}));
                    module.exports = router;
                """,
                "src/server.js" to """
                    const express = require('express');
                    const app = express();
                    app.use('/api', require('./routes'));
                    app.listen(3000);
                """,
                "src/main/java/com/demo/AdminController.java" to """
                    package com.demo;
                    import org.springframework.web.bind.annotation.*;
                    @RestController
                    @PreAuthorize("hasRole('ADMIN')")
                    @RequestMapping("/admin")
                    public class AdminController {
                        @GetMapping("/users")
                        public String users() { return "ok"; }
                    }
                """,
                "src/main/java/com/demo/PanelController.java" to """
                    package com.demo;
                    import org.springframework.web.bind.annotation.*;
                    @RestController
                    public class PanelController {
                        @GetMapping("/admin/panel")
                        public String panel() { return "ok"; }
                    }
                """,
            ),
        )
        assertCompleteCards(a)
        val admin = a.find("FC-ACCESS-02", "src/routes.js", 9)
        assertEquals(Certainty.POSSIBLE, admin.certainty)
        assertTrue(admin.explanation.contains("/api/admin/delete"), admin.explanation)
        assertTrue(a.at("src/routes.js", 10).isEmpty(), "Protegida con requireAuth")
        // 4 de 5 rutas protegidas: /payments rompe el patrón; /login es pública por su nombre.
        a.find("FC-ACCESS-01", "src/routes.js", 7)
        assertTrue(a.at("src/routes.js", 8).isEmpty())
        a.none("FC-ACCESS-02", "src/main/java/com/demo/AdminController.java")
        a.find("FC-ACCESS-02", "src/main/java/com/demo/PanelController.java", 5)
    }

    @Test
    fun supresionesSeSenalanSinOcultarLoQueSilencian() {
        val a = audit(
            dir,
            mapOf(
                "src/calc.js" to """
                    // nosemgrep: javascript.lang.security.eval
                    function calc(expr) { return eval(expr); }
                    const label = "nosec"; // texto, no comentario de supresión
                """,
                ".semgrepignore" to "node_modules",
            ),
        )
        assertCompleteCards(a)
        a.find("FC-SUPPRESS-01", "src/calc.js", 1)
        a.find("FC-CODE-01", "src/calc.js", 2)
        assertTrue(a.at("src/calc.js", 3).isEmpty())
        a.find("FC-SUPPRESS-02", ".semgrepignore")
        assertEquals(2, a.summary.suppressions)
    }

    @Test
    fun resumenDiceLoQueNoSeEvaluaYLoQueRequierePruebaDinamica() {
        val a = audit(
            dir,
            mapOf(
                "src/a.js" to "const x = 1;",
                "lib/tool.rb" to "def run(cmd)\n  system(cmd)\nend",
            ),
        )
        val s = a.summary
        assertEquals(0, s.findings)
        assertTrue(s.rules == SecurityRules.ALL.size && s.filesExamined >= 2)
        assertTrue(s.notEvaluated.any { it.contains("no hay base de vulnerabilidades") } || s.notEvaluated.none { it.contains("Fase 7") })
        assertTrue(s.notEvaluated.any { it.contains("Ruby") }, s.notEvaluated.toString())
        assertTrue(s.dynamicChecks.isNotEmpty())
    }
}
