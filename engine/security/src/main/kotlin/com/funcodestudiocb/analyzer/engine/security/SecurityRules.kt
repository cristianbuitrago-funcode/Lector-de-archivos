package com.funcodestudiocb.analyzer.engine.security

import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.ACCESS_CONTROL
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.COMMAND_EXECUTION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.CORS
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.CSRF
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.EXCESSIVE_PERMISSIONS
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.EXPOSED_SECRET
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.HARDCODED_PASSWORD
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.INSECURE_CONFIGURATION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.INSUFFICIENT_VALIDATION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.PATH_TRAVERSAL
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SENSITIVE_DATA_EXPOSURE
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SESSION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SQL_INJECTION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.OUTDATED_LIBRARY
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SUPPRESSION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.VULNERABLE_DEPENDENCY
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.WEAK_AUTHENTICATION
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory.XSS
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.Severity.CRITICAL
import com.funcodestudiocb.analyzer.engine.api.Severity.HIGH
import com.funcodestudiocb.analyzer.engine.api.Severity.INFO
import com.funcodestudiocb.analyzer.engine.api.Severity.LOW
import com.funcodestudiocb.analyzer.engine.api.Severity.MEDIUM

/** Regla de la auditoría con los textos de su ficha (§8.3). La detección está en `engine:audit`. */
class SecurityRule(
    val id: String,
    val name: String,
    val category: SecurityCategory,
    val cwe: Int?,
    val owasp: String?,
    val severity: Severity,
    val explanation: String,
    val consequences: String,
    val recommendation: String,
    /** Ejemplo corregido por lenguaje (`javascript`, `python`, `java`, `kotlin`, `php`, `csharp`, `config`…). */
    val examples: Map<String, String> = emptyMap(),
    private val extraReferences: List<String> = emptyList(),
    val version: Int = 1,
) {
    val references: List<String>
        get() = listOfNotNull(cwe?.let { "https://cwe.mitre.org/data/definitions/$it.html" }, owasp?.let { OWASP_LINKS[it.take(3)] }) + extraReferences

    fun example(language: String?): String? = examples[language] ?: when (language) {
        "typescript", "tsx", "vue", "svelte" -> examples["javascript"]
        "kotlin" -> examples["java"]
        else -> null
    } ?: examples["config"]

    companion object {
        private val OWASP_LINKS = mapOf(
            "A01" to "https://owasp.org/Top10/A01_2021-Broken_Access_Control/",
            "A02" to "https://owasp.org/Top10/A02_2021-Cryptographic_Failures/",
            "A03" to "https://owasp.org/Top10/A03_2021-Injection/",
            "A04" to "https://owasp.org/Top10/A04_2021-Insecure_Design/",
            "A05" to "https://owasp.org/Top10/A05_2021-Security_Misconfiguration/",
            "A06" to "https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/",
            "A07" to "https://owasp.org/Top10/A07_2021-Identification_and_Authentication_Failures/",
            "A08" to "https://owasp.org/Top10/A08_2021-Software_and_Data_Integrity_Failures/",
            "A09" to "https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/",
            "A10" to "https://owasp.org/Top10/A10_2021-Server-Side_Request_Forgery_%28SSRF%29/",
        )
    }
}

/** Catálogo de reglas de Funcode. Cada regla tiene casos vulnerables y seguros en las pruebas (§8.6). */
object SecurityRules {

    private const val A01 = "A01:2021 Pérdida de control de acceso"
    private const val A02 = "A02:2021 Fallos criptográficos"
    private const val A03 = "A03:2021 Inyección"
    private const val A04 = "A04:2021 Diseño inseguro"
    private const val A05 = "A05:2021 Configuración de seguridad incorrecta"
    private const val A06 = "A06:2021 Componentes vulnerables y desactualizados"
    private const val A07 = "A07:2021 Fallos de identificación y autenticación"
    private const val A08 = "A08:2021 Fallos de integridad de software y datos"
    private const val A09 = "A09:2021 Fallos de registro y monitorización"
    private const val A10 = "A10:2021 Falsificación de peticiones del lado del servidor (SSRF)"
    private const val CHEAT = "https://cheatsheetseries.owasp.org/cheatsheets/"

    val ALL: List<SecurityRule> = listOf(
        // ------------------------------------------------------------------ secretos
        SecurityRule(
            "FC-SECRET-01", "Clave privada en el proyecto", EXPOSED_SECRET, 321, A02, CRITICAL,
            "El comprimido contiene una clave privada (bloque «BEGIN … PRIVATE KEY»). Cualquiera con acceso al código o a su historial la tiene.",
            "Quien la tenga puede suplantar al servidor o al usuario dueño de la clave, descifrar tráfico o firmar en su nombre.",
            "Retira la clave del código y del historial de git, revócala y genera otra. Guárdala en un gestor de secretos o en el almacén de claves del sistema.",
            mapOf("config" to "# Fuera del repositorio:\n#   variable de entorno o gestor de secretos\nPRIVATE_KEY_PATH=/run/secrets/servidor.key"),
            listOf(CHEAT + "Secrets_Management_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-SECRET-02", "Clave o token de un servicio en el código", EXPOSED_SECRET, 798, A07, HIGH,
            "Hay una credencial con el formato de un proveedor conocido escrita en el código. El formato está confirmado; su validez no se comprueba (no se contacta con ningún servicio).",
            "Si es válida, cualquiera con el código puede usar la cuenta del servicio: costes, acceso a datos o envío de mensajes en tu nombre.",
            "Revoca la credencial en el panel del proveedor, sácala del código (y del historial) y léela de una variable de entorno o de un gestor de secretos.",
            mapOf(
                "javascript" to "const stripe = new Stripe(process.env.STRIPE_SECRET_KEY);",
                "python" to "stripe.api_key = os.environ[\"STRIPE_SECRET_KEY\"]",
                "java" to "String apiKey = System.getenv(\"SERVICE_API_KEY\");",
                "php" to "\$apiKey = env('SERVICE_API_KEY');",
                "csharp" to "var apiKey = builder.Configuration[\"Service:ApiKey\"]; // user-secrets o Key Vault",
            ),
            listOf(CHEAT + "Secrets_Management_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-SECRET-03", "Contraseña escrita en el código", HARDCODED_PASSWORD, 259, A07, HIGH,
            "Un valor asignado a un nombre de contraseña está escrito tal cual en el código o en la configuración.",
            "Quien acceda al código conoce la contraseña; además suele repetirse en otros entornos y no se puede cambiar sin publicar una versión nueva.",
            "Lee la contraseña de una variable de entorno o de un gestor de secretos y cámbiala, porque ya está expuesta.",
            mapOf(
                "javascript" to "const password = process.env.DB_PASSWORD;",
                "python" to "password = os.environ[\"DB_PASSWORD\"]",
                "java" to "String password = System.getenv(\"DB_PASSWORD\");",
                "php" to "\$password = env('DB_PASSWORD');",
                "config" to "DB_PASSWORD=   # se rellena al desplegar, nunca en el repositorio",
            ),
        ),
        SecurityRule(
            "FC-SECRET-04", "Contraseña en una cadena de conexión", HARDCODED_PASSWORD, 259, A07, HIGH,
            "Una URL o cadena de conexión (base de datos, cola, caché) lleva el usuario y la contraseña dentro.",
            "Da acceso directo a la base de datos o al servicio con los permisos de esa cuenta.",
            "Construye la cadena de conexión desde variables de entorno y cambia la contraseña expuesta.",
            mapOf("config" to "DATABASE_URL=   # definida en el entorno de ejecución, no en el código"),
        ),
        SecurityRule(
            "FC-SECRET-05", "Secreto o token escrito en el código", EXPOSED_SECRET, 798, A07, HIGH,
            "Un valor asignado a un nombre de secreto, token o clave está escrito tal cual.",
            "Si es una credencial real, cualquiera con el código puede usarla.",
            "Muévelo a una variable de entorno o a un gestor de secretos y renuévalo.",
        ),
        SecurityRule(
            "FC-FILE-06", "Credenciales de una cuenta de servicio", EXPOSED_SECRET, 798, A07, CRITICAL,
            "Hay un archivo de credenciales de cuenta de servicio (por ejemplo, de Google Cloud o Firebase) con su clave privada.",
            "Permite actuar como esa cuenta de servicio con todos sus permisos en la nube.",
            "Elimina el archivo del repositorio y de su historial, revoca la clave en la consola del proveedor y usa credenciales del entorno (identidad de carga de trabajo, variables o gestor de secretos).",
        ),

        // ------------------------------------------------------------------ inyección
        SecurityRule(
            "FC-SQLI-01", "Consulta SQL construida con datos variables", SQL_INJECTION, 89, A03, HIGH,
            "La consulta se construye concatenando o interpolando valores en el texto SQL, en lugar de pasarlos como parámetros.",
            "Si alguno de esos valores viene del usuario, puede cambiar la consulta: leer o borrar datos de otros usuarios o saltarse el inicio de sesión.",
            "Usa consultas parametrizadas (marcadores ? o :nombre) o el constructor de consultas del ORM, y nunca concatenes valores en el SQL.",
            mapOf(
                "javascript" to "db.query('SELECT * FROM users WHERE id = ?', [req.params.id]);",
                "python" to "cursor.execute(\"SELECT * FROM users WHERE id = %s\", (user_id,))",
                "java" to "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\nps.setLong(1, id);",
                "php" to "\$stmt = \$pdo->prepare('SELECT * FROM users WHERE id = ?');\n\$stmt->execute([\$id]);",
                "csharp" to "var cmd = new SqlCommand(\"SELECT * FROM Users WHERE Id = @id\", conn);\ncmd.Parameters.AddWithValue(\"@id\", id);",
                "go" to "rows, err := db.Query(\"SELECT * FROM users WHERE id = $1\", id)",
            ),
            listOf(CHEAT + "SQL_Injection_Prevention_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-CMD-01", "Comando del sistema construido con datos variables", COMMAND_EXECUTION, 78, A03, CRITICAL,
            "Se ejecuta un comando del sistema (o un intérprete de comandos) con un texto construido a partir de valores variables.",
            "Si el valor viene del usuario, puede añadir comandos propios (`; rm -rf`, `&& curl …`) y ejecutarlos con los permisos de la aplicación.",
            "Evita el intérprete de comandos: llama al programa con una lista de argumentos fija y valida los valores contra una lista de permitidos.",
            mapOf(
                "javascript" to "execFile('convert', [entrada, salida]); // sin shell, argumentos separados",
                "python" to "subprocess.run([\"convert\", entrada, salida], check=True)  # sin shell=True",
                "java" to "new ProcessBuilder(\"convert\", entrada, salida).start();",
                "php" to "exec('convert ' . escapeshellarg(\$entrada) . ' ' . escapeshellarg(\$salida));",
            ),
            listOf(CHEAT + "OS_Command_Injection_Defense_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-CODE-01", "Evaluación de código dinámico", COMMAND_EXECUTION, 95, A03, HIGH,
            "Se ejecuta como código un texto que se construye o se recibe en tiempo de ejecución (eval, exec, new Function…).",
            "Si el texto depende del usuario, puede ejecutar código arbitrario dentro de la aplicación.",
            "No evalúes texto como código. Para datos usa un analizador (JSON.parse, ast.literal_eval) y para lógica, funciones conocidas elegidas de una lista.",
            mapOf(
                "javascript" to "const datos = JSON.parse(texto);",
                "python" to "valor = ast.literal_eval(texto)",
            ),
        ),
        SecurityRule(
            "FC-DESER-01", "Deserialización de datos no confiables", COMMAND_EXECUTION, 502, A08, HIGH,
            "Se deserializan objetos con un mecanismo que puede crear tipos arbitrarios (pickle, unserialize, ObjectInputStream, BinaryFormatter, yaml.load sin cargador seguro).",
            "Con datos manipulados, la deserialización puede ejecutar código o alterar la lógica de la aplicación.",
            "Usa formatos de datos simples (JSON) o cargadores seguros (yaml.safe_load) y no deserialices nunca datos que vengan del usuario.",
            mapOf("python" to "datos = yaml.safe_load(texto)", "php" to "\$datos = json_decode(\$texto, true);"),
            listOf(CHEAT + "Deserialization_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-SSTI-01", "Plantilla construida con datos variables", COMMAND_EXECUTION, 1336, A03, HIGH,
            "El texto de la plantilla se construye con valores variables y después se interpreta (render_template_string, Template(...)).",
            "Si el valor viene del usuario, puede inyectar expresiones de la plantilla y llegar a ejecutar código en el servidor.",
            "Usa plantillas fijas y pasa los datos como variables de contexto.",
            mapOf("python" to "return render_template(\"saludo.html\", nombre=nombre)"),
        ),
        SecurityRule(
            "FC-PATH-01", "Ruta de archivo con datos del usuario", PATH_TRAVERSAL, 22, A01, HIGH,
            "Un dato de la petición llega a una operación con archivos (leer, escribir, enviar, borrar) sin limitarse a una carpeta.",
            "Con `../` el usuario puede leer o sobrescribir archivos fuera de la carpeta prevista (configuración, claves, código).",
            "Quédate solo con el nombre del archivo (basename), comprueba que la ruta final sigue dentro de la carpeta permitida o usa identificadores en lugar de nombres.",
            mapOf(
                "javascript" to "const nombre = path.basename(req.params.nombre);\nres.sendFile(nombre, { root: CARPETA_PUBLICA });",
                "python" to "return send_from_directory(CARPETA, os.path.basename(nombre))",
                "java" to "Path ruta = base.resolve(nombre).normalize();\nif (!ruta.startsWith(base)) throw new SecurityException();",
                "php" to "\$ruta = realpath(CARPETA . '/' . basename(\$nombre));",
            ),
            listOf(CHEAT + "File_Upload_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-PATH-02", "Inclusión de archivos con datos del usuario", PATH_TRAVERSAL, 98, A03, CRITICAL,
            "Un dato de la petición decide qué archivo se incluye o se ejecuta (include, require).",
            "El usuario puede incluir archivos locales sensibles o, según la configuración, código remoto: ejecución de código en el servidor.",
            "No incluyas archivos según datos del usuario: usa una lista fija de vistas o páginas permitidas.",
            mapOf("php" to "\$paginas = ['inicio' => 'inicio.php', 'ayuda' => 'ayuda.php'];\ninclude \$paginas[\$_GET['p']] ?? 'inicio.php';"),
        ),
        SecurityRule(
            "FC-SSRF-01", "Petición del servidor a una URL del usuario", INSUFFICIENT_VALIDATION, 918, A10, HIGH,
            "El servidor hace una petición HTTP a una URL que viene de la petición del usuario.",
            "Se puede usar el servidor para llegar a servicios internos (metadatos de la nube, bases de datos, paneles de administración).",
            "Valida la URL contra una lista de dominios permitidos y bloquea direcciones internas y de metadatos.",
        ),
        SecurityRule(
            "FC-REDIRECT-01", "Redirección a una dirección del usuario", INSUFFICIENT_VALIDATION, 601, A01, MEDIUM,
            "La aplicación redirige a una URL que viene de la petición sin comprobarla.",
            "Facilita el phishing: un enlace del propio dominio lleva a una página falsa.",
            "Redirige solo a rutas internas o a destinos de una lista permitida.",
            mapOf("javascript" to "const destino = DESTINOS_PERMITIDOS.includes(req.query.next) ? req.query.next : '/';\nres.redirect(destino);"),
        ),

        // ------------------------------------------------------------------ XSS
        SecurityRule(
            "FC-XSS-01", "HTML insertado sin escapar en el navegador", XSS, 79, A03, MEDIUM,
            "Se inserta HTML en la página a partir de un valor variable (innerHTML, document.write, dangerouslySetInnerHTML, v-html…).",
            "Si el valor contiene datos del usuario, puede ejecutar JavaScript en el navegador de otros usuarios: robo de sesiones o acciones en su nombre.",
            "Inserta texto (textContent, interpolación normal del framework) o sanea el HTML con una biblioteca como DOMPurify.",
            mapOf("javascript" to "elemento.textContent = mensaje;\n// o, si hace falta HTML:\nelemento.innerHTML = DOMPurify.sanitize(html);"),
            listOf(CHEAT + "Cross_Site_Scripting_Prevention_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-XSS-02", "Respuesta con datos del usuario sin escapar", XSS, 79, A03, HIGH,
            "El servidor devuelve en la respuesta HTML un dato de la petición tal cual.",
            "Un enlace manipulado ejecuta JavaScript en el navegador de quien lo abra (XSS reflejado).",
            "Escapa los datos al generar HTML (htmlspecialchars, plantillas con escape automático) o responde JSON.",
            mapOf("php" to "echo htmlspecialchars(\$_GET['q'], ENT_QUOTES, 'UTF-8');", "javascript" to "res.json({ q: req.query.q });"),
        ),
        SecurityRule(
            "FC-XSS-03", "Escape automático de la plantilla desactivado", XSS, 79, A03, MEDIUM,
            "La plantilla muestra un valor sin escapar ({!! !!}, |safe, mark_safe, th:utext, Html.Raw, <%- %>, {{{ }}}).",
            "Si el valor contiene datos del usuario, se ejecuta su HTML y JavaScript en la página.",
            "Usa la salida con escape normal de la plantilla y reserva la salida sin escapar para HTML que generas tú y ya está saneado.",
        ),

        // ------------------------------------------------------------------ autenticación y criptografía
        SecurityRule(
            "FC-AUTH-01", "Contraseñas con un hash débil", WEAK_AUTHENTICATION, 916, A02, HIGH,
            "Se calcula un hash rápido (MD5 o SHA-1) cerca de una contraseña.",
            "Estos hashes se rompen por fuerza bruta en minutos: una filtración de la base de datos expone las contraseñas.",
            "Usa un algoritmo para contraseñas con sal y coste: bcrypt, scrypt o Argon2id.",
            mapOf(
                "javascript" to "const hash = await bcrypt.hash(password, 12);",
                "python" to "hash = argon2.PasswordHasher().hash(password)",
                "java" to "String hash = new BCryptPasswordEncoder().encode(password);",
                "php" to "\$hash = password_hash(\$password, PASSWORD_ARGON2ID);",
            ),
            listOf(CHEAT + "Password_Storage_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-AUTH-02", "JWT sin verificar la firma", WEAK_AUTHENTICATION, 347, A07, HIGH,
            "Se aceptan tokens JWT sin comprobar la firma (verificación desactivada o algoritmo «none»).",
            "Cualquiera puede fabricar un token con los datos que quiera y hacerse pasar por otro usuario o por un administrador.",
            "Verifica siempre la firma y fija la lista de algoritmos aceptados.",
            mapOf(
                "javascript" to "const datos = jwt.verify(token, process.env.JWT_SECRET, { algorithms: ['HS256'] });",
                "python" to "datos = jwt.decode(token, clave, algorithms=[\"HS256\"])",
            ),
            listOf(CHEAT + "JSON_Web_Token_for_Java_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-AUTH-03", "Secreto de firma o de sesión escrito en el código", WEAK_AUTHENTICATION, 798, A07, HIGH,
            "La clave que firma los tokens o las cookies de sesión está escrita en el código.",
            "Con ella cualquiera puede firmar sesiones o tokens válidos y entrar como cualquier usuario.",
            "Lee el secreto de una variable de entorno, que sea largo y aleatorio, y cámbialo porque ya está expuesto.",
            mapOf(
                "javascript" to "app.use(session({ secret: process.env.SESSION_SECRET, cookie: { httpOnly: true, secure: true } }));",
                "python" to "app.config[\"SECRET_KEY\"] = os.environ[\"SECRET_KEY\"]",
            ),
        ),
        SecurityRule(
            "FC-AUTH-04", "Números aleatorios no criptográficos para secretos", WEAK_AUTHENTICATION, 338, A02, MEDIUM,
            "Se generan tokens, contraseñas o códigos con un generador aleatorio predecible (Math.random, random, java.util.Random).",
            "Un atacante puede predecir los valores generados y adivinar tokens de sesión o de recuperación.",
            "Usa el generador criptográfico del lenguaje: crypto.randomBytes, secrets, SecureRandom.",
            mapOf("javascript" to "const token = crypto.randomBytes(32).toString('hex');", "python" to "token = secrets.token_urlsafe(32)", "java" to "new SecureRandom().nextBytes(bytes);"),
        ),

        // ------------------------------------------------------------------ configuración insegura
        SecurityRule(
            "FC-TLS-01", "Verificación de certificados TLS desactivada", INSECURE_CONFIGURATION, 295, A02, HIGH,
            "La conexión HTTPS acepta cualquier certificado (verificación desactivada o un validador que siempre devuelve verdadero).",
            "Un atacante en la red puede interceptar y modificar el tráfico sin que la aplicación lo note (ataque de intermediario).",
            "Deja activada la verificación. Para certificados propios, añade la autoridad de confianza en lugar de desactivar la comprobación.",
            mapOf(
                "python" to "requests.get(url, verify=\"/ruta/ca.pem\")  # nunca verify=False",
                "javascript" to "https.request({ ...opciones, ca: fs.readFileSync('ca.pem') }); // sin rejectUnauthorized: false",
            ),
            listOf(CHEAT + "Transport_Layer_Security_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-TLS-02", "Tráfico sin cifrar permitido", INSECURE_CONFIGURATION, 319, A02, MEDIUM,
            "La configuración permite conexiones HTTP sin cifrar (usesCleartextTraffic, cleartextTrafficPermitted, NSAllowsArbitraryLoads).",
            "Los datos y las sesiones viajan en claro y se pueden leer o modificar en la red.",
            "Usa solo HTTPS y, si un dominio concreto necesita HTTP, permítelo solo para él.",
            mapOf("config" to "<network-security-config>\n  <base-config cleartextTrafficPermitted=\"false\" />\n</network-security-config>"),
        ),
        SecurityRule(
            "FC-DEBUG-01", "Modo de depuración activo", INSECURE_CONFIGURATION, 489, A05, MEDIUM,
            "La aplicación está configurada en modo de depuración (DEBUG, debug=True, APP_DEBUG, android:debuggable).",
            "Muestra trazas, configuración y a veces una consola interactiva; en Android permite inspeccionar la app instalada.",
            "Desactiva la depuración en producción y lee el modo de una variable de entorno con valor seguro por defecto.",
            mapOf("python" to "DEBUG = os.environ.get(\"DJANGO_DEBUG\") == \"1\"", "config" to "APP_DEBUG=false"),
        ),
        SecurityRule(
            "FC-CONF-01", "Endpoints de administración expuestos", INSECURE_CONFIGURATION, 200, A05, MEDIUM,
            "Spring Boot Actuator expone todos sus endpoints (management.endpoints.web.exposure.include=*).",
            "Puede revelar variables de entorno, configuración, volcados de memoria o permitir cambiar el nivel de registro.",
            "Expón solo health e info y protege el resto con autenticación.",
            mapOf("config" to "management.endpoints.web.exposure.include=health,info"),
        ),
        SecurityRule(
            "FC-ANDROID-01", "Copia de seguridad de la app permitida", INSECURE_CONFIGURATION, 530, A05, LOW,
            "El manifiesto permite la copia de seguridad de los datos de la app (android:allowBackup=\"true\").",
            "Los datos privados de la app se pueden extraer con una copia de seguridad (ADB o en la nube).",
            "Desactiva la copia o limita qué se copia con dataExtractionRules / fullBackupContent.",
            mapOf("config" to "<application android:allowBackup=\"false\" android:dataExtractionRules=\"@xml/reglas_copia\" …>"),
        ),
        SecurityRule(
            "FC-WEBVIEW-01", "WebView con JavaScript y una interfaz nativa expuesta", INSECURE_CONFIGURATION, 749, A05, HIGH,
            "Un WebView tiene JavaScript activado y expone objetos nativos con addJavascriptInterface.",
            "Si carga contenido no confiable, ese contenido puede llamar a métodos nativos de la app.",
            "Carga solo contenido propio, expón el mínimo de métodos (anotados con @JavascriptInterface) y valida el origen.",
        ),
        SecurityRule(
            "FC-WEBVIEW-02", "WebView con acceso a archivos locales", INSECURE_CONFIGURATION, 200, A05, MEDIUM,
            "El WebView permite el acceso a archivos (setAllowFileAccess, setAllowUniversalAccessFromFileURLs…).",
            "Una página cargada puede leer archivos de la app o enviarlos fuera.",
            "Desactiva el acceso a archivos y usa WebViewAssetLoader para el contenido local.",
            mapOf("java" to "webView.getSettings().setAllowFileAccess(false);"),
        ),
        SecurityRule(
            "FC-ELECTRON-01", "Electron con Node.js en el contenido o sin aislamiento", INSECURE_CONFIGURATION, 94, A05, HIGH,
            "La ventana de Electron se crea con nodeIntegration activado, contextIsolation desactivado o webSecurity desactivado.",
            "Un XSS en la interfaz se convierte en ejecución de código en el equipo del usuario.",
            "Usa nodeIntegration: false, contextIsolation: true, sandbox: true y expón solo lo necesario con contextBridge.",
            mapOf("javascript" to "new BrowserWindow({ webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, preload } });"),
            listOf("https://www.electronjs.org/docs/latest/tutorial/security"),
        ),
        SecurityRule(
            "FC-ELECTRON-02", "Apertura de URLs externas sin comprobar", INSECURE_CONFIGURATION, 601, A05, MEDIUM,
            "shell.openExternal recibe una URL variable sin comprobar su esquema o destino.",
            "Con un esquema peligroso (file:, protocolos propios) puede abrir programas o archivos locales.",
            "Permite solo https: y dominios de una lista antes de llamar a openExternal.",
        ),
        SecurityRule(
            "FC-CI-01", "Flujo de CI que ejecuta código de una pull request externa", INSECURE_CONFIGURATION, 829, A08, HIGH,
            "Un flujo con pull_request_target hace checkout del código de la pull request.",
            "El código de cualquier colaborador externo se ejecuta con los secretos y permisos de escritura del repositorio.",
            "Usa pull_request para ejecutar código de la PR, o separa en dos flujos sin compartir secretos.",
            extraReferences = listOf("https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/"),
        ),
        SecurityRule(
            "FC-CI-02", "Datos de la PR o del issue dentro de un comando del flujo", COMMAND_EXECUTION, 78, A03, HIGH,
            "Un paso run: interpola directamente campos controlados por el usuario (título, cuerpo, rama) con ${'$'}{{ … }}.",
            "Un título o nombre de rama manipulado ejecuta comandos en el runner con los secretos del flujo.",
            "Pasa el valor por una variable de entorno (env:) y úsala entre comillas en el script.",
            mapOf("config" to "env:\n  TITULO: \${{ github.event.issue.title }}\nrun: echo \"\$TITULO\""),
        ),
        SecurityRule(
            "FC-PUBENV-01", "Secreto en una variable pública del frontend", SENSITIVE_DATA_EXPOSURE, 200, A05, HIGH,
            "Una variable que el framework incluye en el código del navegador (NEXT_PUBLIC_, VITE_, REACT_APP_, EXPO_PUBLIC_) tiene nombre de secreto.",
            "Su valor queda visible para cualquier visitante en el JavaScript descargado.",
            "Deja en variables públicas solo valores públicos (claves publicables) y usa los secretos solo en el servidor.",
        ),

        // ------------------------------------------------------------------ permisos
        SecurityRule(
            "FC-ANDROID-02", "Componente de Android exportado sin permiso", EXCESSIVE_PERMISSIONS, 926, A01, MEDIUM,
            "Una actividad, servicio, receptor o proveedor está exportado (otras apps pueden usarlo) y no exige ningún permiso.",
            "Cualquier app instalada puede abrirlo, enviarle datos o leer los suyos (proveedores de contenido).",
            "Pon android:exported=\"false\" si no hace falta o exige un permiso con android:permission (con protectionLevel signature).",
            mapOf("config" to "<service android:name=\".SyncService\" android:exported=\"false\" />"),
        ),
        SecurityRule(
            "FC-ANDROID-03", "Permiso peligroso declarado", EXCESSIVE_PERMISSIONS, 250, A05, INFO,
            "El manifiesto declara un permiso que da acceso a datos o funciones sensibles del usuario.",
            "Si no es imprescindible, aumenta lo que se puede hacer con la app si se ve comprometida y reduce la confianza del usuario.",
            "Declara solo los permisos que la app usa y pídelos en el momento en que hacen falta.",
        ),
        SecurityRule(
            "FC-ANDROID-04", "Permiso posiblemente innecesario", EXCESSIVE_PERMISSIONS, 250, A05, LOW,
            "El manifiesto declara un permiso peligroso, pero no se encontró en el código ninguna de las APIs que lo usan.",
            "Un permiso sin uso amplía sin motivo lo que la app puede hacer.",
            "Comprueba si se usa (quizá lo usa una biblioteca) y elimínalo del manifiesto si no.",
        ),
        SecurityRule(
            "FC-DOCKER-01", "Contenedor con privilegios", EXCESSIVE_PERMISSIONS, 250, A05, HIGH,
            "Un servicio de docker-compose se ejecuta con privileged: true.",
            "Un fallo en el contenedor da control sobre el equipo anfitrión.",
            "Quita privileged y añade solo las capacidades concretas que hagan falta (cap_add).",
        ),
        SecurityRule(
            "FC-DOCKER-02", "Imagen que se ejecuta como root", EXCESSIVE_PERMISSIONS, 250, A05, LOW,
            "El Dockerfile no cambia a un usuario sin privilegios (USER) o cambia explícitamente a root.",
            "Si la aplicación se ve comprometida, el atacante tiene permisos de root dentro del contenedor.",
            "Crea un usuario sin privilegios y añade USER antes de arrancar la aplicación.",
            mapOf("config" to "RUN adduser --system app\nUSER app"),
        ),

        // ------------------------------------------------------------------ control de acceso
        SecurityRule(
            "FC-ACCESS-01", "Ruta sin autenticación entre rutas protegidas", ACCESS_CONTROL, 862, A01, MEDIUM,
            "En este archivo o grupo la mayoría de rutas exigen autenticación, pero esta no tiene ningún middleware, guarda o anotación de acceso.",
            "Si no es una ruta pública intencionada, cualquiera puede usarla sin iniciar sesión.",
            "Añade la misma protección que al resto o documenta por qué es pública. Compruébalo con una prueba dinámica.",
        ),
        SecurityRule(
            "FC-ACCESS-02", "Ruta de administración sin autenticación visible", ACCESS_CONTROL, 862, A01, HIGH,
            "Una ruta de administración no tiene ninguna protección visible en su declaración.",
            "Si no la protege otra capa (proxy, middleware global), cualquiera puede usar funciones de administración.",
            "Protégela con autenticación y control de roles y compruébalo con una prueba dinámica.",
        ),
        SecurityRule(
            "FC-FIREBASE-01", "Reglas de Firebase abiertas a todos", ACCESS_CONTROL, 284, A01, CRITICAL,
            "Las reglas de seguridad de Firestore, Realtime Database o Storage permiten leer o escribir sin condiciones.",
            "Cualquiera con el identificador del proyecto (que va dentro de la app) puede leer, modificar o borrar todos los datos.",
            "Exige autenticación y limita cada colección a su dueño (request.auth.uid).",
            mapOf("config" to "match /usuarios/{uid} {\n  allow read, write: if request.auth != null && request.auth.uid == uid;\n}"),
            listOf("https://firebase.google.com/docs/rules/insecure-rules"),
        ),
        SecurityRule(
            "FC-SUPABASE-01", "Seguridad por filas desactivada", ACCESS_CONTROL, 284, A01, HIGH,
            "Una tabla tiene desactivada la seguridad por filas (DISABLE ROW LEVEL SECURITY).",
            "En Supabase, cualquiera con la clave pública puede leer y modificar toda la tabla.",
            "Activa RLS y crea políticas por usuario.",
            mapOf("config" to "alter table perfiles enable row level security;\ncreate policy \"propio\" on perfiles for select using (auth.uid() = id);"),
        ),

        // ------------------------------------------------------------------ CORS, sesiones y CSRF
        SecurityRule(
            "FC-CORS-01", "CORS con cualquier origen y credenciales", CORS, 942, A05, HIGH,
            "Se permite cualquier origen y a la vez el envío de cookies o credenciales.",
            "Una web ajena puede hacer peticiones autenticadas en nombre del usuario y leer las respuestas.",
            "Permite solo los orígenes de una lista concreta cuando uses credenciales.",
            mapOf("javascript" to "app.use(cors({ origin: ['https://app.ejemplo.com'], credentials: true }));"),
            listOf(CHEAT + "HTML5_Security_Cheat_Sheet.html#cross-origin-resource-sharing"),
        ),
        SecurityRule(
            "FC-CORS-02", "CORS abierto a cualquier origen", CORS, 942, A05, LOW,
            "Las respuestas permiten cualquier origen (*).",
            "Cualquier web puede leer las respuestas de la API; es aceptable solo para datos públicos.",
            "Limita los orígenes permitidos si la API no es pública.",
        ),
        SecurityRule(
            "FC-CORS-03", "CORS que refleja el origen de la petición", CORS, 942, A05, HIGH,
            "El origen permitido se copia de la cabecera Origin de la propia petición.",
            "Equivale a permitir cualquier origen, también con credenciales.",
            "Compara el origen con una lista de orígenes permitidos antes de devolverlo.",
        ),
        SecurityRule(
            "FC-COOKIE-01", "Cookie accesible desde JavaScript", SESSION, 1004, A05, MEDIUM,
            "La cookie se crea con httpOnly desactivado.",
            "Un XSS puede leerla y robar la sesión.",
            "Marca las cookies de sesión con HttpOnly.",
            mapOf("javascript" to "res.cookie('sid', valor, { httpOnly: true, secure: true, sameSite: 'lax' });"),
        ),
        SecurityRule(
            "FC-COOKIE-02", "Cookie que también viaja sin cifrar", SESSION, 614, A05, MEDIUM,
            "La cookie se crea con secure desactivado (o SESSION_COOKIE_SECURE = False).",
            "Se envía también por HTTP y se puede capturar en la red.",
            "Marca las cookies de sesión con Secure y sirve la aplicación solo por HTTPS.",
        ),
        SecurityRule(
            "FC-SESSION-01", "Token de sesión guardado en localStorage", SESSION, 922, A07, MEDIUM,
            "Un token de acceso o de sesión se guarda en localStorage o sessionStorage.",
            "Cualquier XSS puede leerlo y enviarlo fuera; no tiene la protección de una cookie HttpOnly.",
            "Guarda la sesión en una cookie HttpOnly, Secure y SameSite gestionada por el servidor.",
        ),
        SecurityRule(
            "FC-CSRF-01", "Protección CSRF desactivada", CSRF, 352, A01, MEDIUM,
            "La protección contra CSRF está desactivada (csrf().disable(), @csrf_exempt, IgnoreAntiforgeryToken, excepciones de VerifyCsrfToken).",
            "Si la sesión va en cookies, una web ajena puede hacer acciones en nombre del usuario (cambiar datos, hacer pagos).",
            "Mantén la protección CSRF activada; desactívala solo para APIs sin cookies (con tokens en la cabecera Authorization).",
            extraReferences = listOf(CHEAT + "Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-CSRF-02", "Sesión por cookies sin protección CSRF visible", CSRF, 352, A01, MEDIUM,
            "La aplicación usa sesiones con cookies y no se encontró ninguna protección CSRF (token o cookies SameSite estrictas).",
            "Si no hay otra protección, una web ajena puede hacer acciones en nombre del usuario.",
            "Añade tokens CSRF o configura las cookies con SameSite=Lax/Strict, y compruébalo con una prueba dinámica.",
        ),

        // ------------------------------------------------------------------ validación
        SecurityRule(
            "FC-MASS-01", "Asignación masiva con los datos de la petición", INSUFFICIENT_VALIDATION, 915, A04, MEDIUM,
            "Se crea o actualiza un registro con todo el cuerpo de la petición (create(req.body), update(\$request->all()), fields = '__all__').",
            "El usuario puede enviar campos que no debería poder cambiar (rol, saldo, propietario).",
            "Copia solo los campos permitidos o declara la lista de campos asignables.",
            mapOf(
                "javascript" to "const { nombre, email } = req.body;\nawait User.create({ nombre, email });",
                "php" to "Post::create(\$request->only(['titulo', 'cuerpo']));",
            ),
            listOf(CHEAT + "Mass_Assignment_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-VALID-01", "Datos de la petición sin validación", INSUFFICIENT_VALIDATION, 20, A04, LOW,
            "El código del servidor usa datos de la petición y el proyecto no usa ninguna biblioteca de validación de esquemas.",
            "Datos inesperados (tipos, tamaños, campos extra) pueden provocar errores o abrir otras vulnerabilidades.",
            "Valida cada petición con un esquema (zod, joi, express-validator, pydantic, Bean Validation, FormRequest).",
        ),

        // ------------------------------------------------------------------ información expuesta
        SecurityRule(
            "FC-FILE-01", "Archivo .env con valores en el proyecto", SENSITIVE_DATA_EXPOSURE, 538, A05, HIGH,
            "El comprimido contiene un archivo .env (no una plantilla): suele llevar las contraseñas y claves reales del entorno.",
            "Todos sus valores quedan expuestos a quien tenga el código.",
            "Saca el .env del repositorio (.gitignore), deja una plantilla .env.example sin valores y renueva los secretos.",
        ),
        SecurityRule(
            "FC-FILE-02", "Almacén de claves o certificado privado", SENSITIVE_DATA_EXPOSURE, 321, A02, HIGH,
            "El comprimido contiene un almacén de claves o un certificado con clave privada (.jks, .keystore, .p12, .pfx, .key, .pem).",
            "Con él (y su contraseña, que a menudo está cerca) se puede firmar o suplantar la aplicación o el servidor.",
            "Guarda los almacenes de claves fuera del repositorio, en el sistema de CI o en un gestor de secretos.",
        ),
        SecurityRule(
            "FC-FILE-03", "Base de datos o volcado incluido", SENSITIVE_DATA_EXPOSURE, 538, A05, MEDIUM,
            "El comprimido contiene una base de datos o un volcado SQL con datos.",
            "Puede contener datos personales o credenciales de usuarios.",
            "No incluyas datos reales en el repositorio; usa datos de prueba generados.",
        ),
        SecurityRule(
            "FC-FILE-04", "Mapas de fuente publicados", SENSITIVE_DATA_EXPOSURE, 540, A05, LOW,
            "Hay archivos .map junto al JavaScript publicado.",
            "Permiten reconstruir el código fuente original del frontend, con comentarios y rutas internas.",
            "No publiques los mapas de fuente en producción o súbelos solo a tu herramienta de errores.",
        ),
        SecurityRule(
            "FC-FILE-05", "Registros o copias de seguridad incluidos", SENSITIVE_DATA_EXPOSURE, 530, A05, LOW,
            "El comprimido contiene archivos de registro (.log) o copias de seguridad (.bak, .old, .backup).",
            "Pueden contener datos de usuarios, tokens o versiones antiguas del código.",
            "Exclúyelos del repositorio y de los paquetes publicados.",
        ),
        SecurityRule(
            "FC-LOG-01", "Datos sensibles en los registros", SENSITIVE_DATA_EXPOSURE, 532, A09, LOW,
            "Se escribe en el registro una variable con nombre de contraseña, token o secreto.",
            "Los registros se guardan y se comparten con menos cuidado que la base de datos: el secreto queda expuesto.",
            "No registres secretos; registra como mucho que existen o una versión enmascarada.",
        ),

        // ------------------------------------------------------------------ dependencias y fin de soporte (Fase 7)
        SecurityRule(
            "FC-DEP-01", "Dependencia con vulnerabilidades conocidas", VULNERABLE_DEPENDENCY, 1395, A06, HIGH,
            "La versión de esta dependencia está afectada por vulnerabilidades publicadas en la base OSV (la del teléfono, con su fecha).",
            "El código de la dependencia se ejecuta dentro de la aplicación: una vulnerabilidad suya es una vulnerabilidad del proyecto si se usa la parte afectada.",
            "Actualiza a una versión corregida (la ficha indica cuáles) y fija la versión con un archivo de bloqueo. Si no hay corrección, valora sustituir la dependencia o aislar su uso.",
            mapOf("config" to "# Actualiza a la versión corregida y vuelve a generar el archivo de bloqueo:\nnpm install paquete@versión-corregida   (o pip, composer, gradle…)"),
            listOf("https://osv.dev/", CHEAT + "Vulnerable_Dependency_Management_Cheat_Sheet.html"),
        ),
        SecurityRule(
            "FC-DEP-02", "Paquete malicioso", VULNERABLE_DEPENDENCY, 506, A08, CRITICAL,
            "El paquete figura en la base OSV como malicioso (aviso MAL-): roba datos, instala puertas traseras o ejecuta código al instalarse.",
            "Basta con instalarlo para que se ejecute su código en el equipo de desarrollo, en la integración continua o en producción.",
            "Elimínalo del proyecto y del archivo de bloqueo, revisa los equipos donde se instaló y cambia las credenciales que pudieran estar a su alcance.",
            extraReferences = listOf("https://github.com/ossf/malicious-packages"),
        ),
        SecurityRule(
            "FC-EOL-01", "Versión sin soporte", OUTDATED_LIBRARY, 1104, A06, MEDIUM,
            "La versión de este entorno, framework o servicio ya no recibe correcciones de seguridad según endoflife.date.",
            "Las vulnerabilidades que se descubran a partir de ahora no se corregirán en esta versión.",
            "Planifica la actualización a un ciclo con soporte (la ficha indica la fecha de fin y la última versión del ciclo).",
            extraReferences = listOf("https://endoflife.date/"),
        ),
        SecurityRule(
            "FC-EOL-02", "Soporte a punto de terminar", OUTDATED_LIBRARY, 1104, A06, LOW,
            "El soporte de seguridad de esta versión termina en los próximos 6 meses según endoflife.date.",
            "Después de esa fecha dejará de recibir correcciones de seguridad.",
            "Programa la actualización antes de la fecha de fin de soporte.",
            extraReferences = listOf("https://endoflife.date/"),
        ),

        // ------------------------------------------------------------------ supresiones (§8.5)
        SecurityRule(
            "FC-SUPPRESS-01", "Comentario que silencia una herramienta de seguridad", SUPPRESSION, null, null, INFO,
            "Un comentario pide a una herramienta de seguridad que no avise en esta línea o archivo (nosemgrep, nosec, NOSONAR, eslint-disable…).",
            "Funcode no respeta estas supresiones: los hallazgos se muestran igual. El comentario revela que alguien decidió ocultar un aviso.",
            "Revisa por qué se suprimió el aviso y, si sigue haciendo falta, documenta el motivo junto al comentario.",
        ),
        SecurityRule(
            "FC-SUPPRESS-02", "Archivo que silencia una herramienta de seguridad", SUPPRESSION, null, null, INFO,
            "El proyecto incluye un archivo de exclusiones de una herramienta de seguridad (.semgrepignore, .gitleaksignore, .trivyignore…).",
            "Funcode no lo respeta, pero indica qué rutas o avisos se excluyeron de otras auditorías.",
            "Revisa que las exclusiones sigan justificadas.",
        ),
    )

    private val byId = ALL.associateBy { it.id }

    fun get(id: String): SecurityRule = byId[id] ?: error("Regla desconocida: $id")
}
