package com.funcodestudiocb.analyzer.engine.security

import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecretType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SecretRedactorTest {

    private val mask = SecretRedactor.MASK

    /** Comprueba lo que exige la política §8.4 sobre cualquier resultado. */
    private fun check(original: String, fileName: String, vararg secrets: String): RedactionResult {
        val result = SecretRedactor.redact(original, fileName)
        assertEquals(original.length, result.text.length, "La máscara conserva la longitud (líneas y columnas no cambian)")
        assertEquals(original.count { it == '\n' }, result.text.count { it == '\n' })
        for (secret in secrets) {
            assertFalse(result.text.contains(secret), "El secreto no debe quedar en el texto: $secret")
            // Ningún trozo de 7 caracteres seguidos del secreto queda visible.
            secret.windowed(7).forEach { piece -> assertFalse(result.text.contains(piece) && !original.replace(secret, "").contains(piece), "Queda visible un fragmento: $piece") }
        }
        result.secrets.forEach { s ->
            val visible = s.masked.filter { it != mask }
            assertTrue(visible.length <= RecordValidator.MAX_VISIBLE_SECRET_CHARS, "Muestra demasiado: ${s.masked}")
            assertTrue(s.masked.length <= 18)
            if (s.length < 12) assertTrue(visible.isEmpty(), "Un secreto corto no muestra nada")
        }
        return result
    }

    @Test
    fun `formatos conocidos de credenciales`() {
        val aws = "AKIAIOSFODNN7EXAMPLE"
        val github = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val google = "AIza" + "SyD-1234567890abcdefghijklmnopqrstu"
        val stripe = "sk_live_" + "51HxYzAbCdEfGhIjKlMnOp"
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        // Se construye por partes: los datos de prueba no deben parecer secretos reales en el repositorio.
        val slack = "https://hooks.slack.com/services/" + "T00000000/" + "B00000000/" + "X".repeat(24)
        val text = """
            val awsKey = $aws
            const token = '$github';
            <string name="maps">$google</string>
            STRIPE = $stripe
            Authorization: Bearer $jwt
            webhook($slack)
        """.trimIndent()
        val result = check(text, "Config.kt", aws, github, google, stripe, jwt, slack)
        val types = result.secrets.map { it.type }.toSet()
        assertTrue(
            types.containsAll(listOf(SecretType.AWS_ACCESS_KEY, SecretType.GITHUB_TOKEN, SecretType.GOOGLE_API_KEY, SecretType.STRIPE_KEY, SecretType.JWT, SecretType.SLACK_WEBHOOK)),
            "Tipos detectados: $types",
        )
        val awsHit = result.secrets.first { it.type == SecretType.AWS_ACCESS_KEY }
        assertEquals(1, awsHit.line)
        assertEquals(20, awsHit.length)
        assertEquals("AKIA" + "$mask".repeat(12) + "LE", awsHit.masked)
        assertTrue(result.text.contains("AKIA" + "$mask".repeat(14) + "LE"), "En el texto, la máscara ocupa lo mismo que el secreto")
    }

    @Test
    fun `clave privada multilinea`() {
        val body = "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun\nVTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK\n"
        val text = "x = 1\n-----BEGIN RSA PRIVATE KEY-----\n$body-----END RSA PRIVATE KEY-----\ny = 2\n"
        val result = check(text, "id_rsa", "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun")
        val key = result.secrets.single()
        assertEquals(SecretType.PRIVATE_KEY, key.type)
        assertEquals(2, key.line)
        assertTrue(key.masked.all { it == mask }, "De una clave privada no se muestra nada")
        assertTrue(result.text.contains("-----BEGIN RSA PRIVATE KEY-----") && result.text.contains("-----END RSA PRIVATE KEY-----"))
        // Sin marca de final también se oculta.
        val open = SecretRedactor.redact("-----BEGIN PRIVATE KEY-----\n$body", "key.pem")
        assertFalse(open.text.contains("MIIEowIBAAKCAQEA"))
    }

    @Test
    fun `valores asignados a nombres sensibles en varios lenguajes`() {
        val cases = listOf(
            Triple("Config.kt", "val apiKey = \"abcd1234efgh5678ijkl\"", "abcd1234efgh5678ijkl"),
            Triple("settings.json", "{ \"password\": \"hunter2hunter2\" }", "hunter2hunter2"),
            Triple("config.php", "'secret' => 'xyz12345abc'", "xyz12345abc"),
            Triple("app.py", "DB_PASSWORD = 'Sup3rS3cr3t!'", "Sup3rS3cr3t!"),
            Triple("client.ts", "const clientSecret: string = \"cs_9f8e7d6c5b4a\";", "cs_9f8e7d6c5b4a"),
            Triple("Main.java", "private static final String AUTH_TOKEN = \"tok-123456789abc\";", "tok-123456789abc"),
            Triple(".env", "DB_PASSWORD=supersecret123 # producción", "supersecret123"),
            Triple(".env.local", "export API_KEY=0123456789abcdef", "0123456789abcdef"),
            Triple("application.yml", "  password: plainYamlPass99", "plainYamlPass99"),
            Triple("strings.xml", "<string name=\"api_secret\">s3cr3tValue</string>", "s3cr3tValue"),
            Triple("web.config", "<add key=\"ApiKey\" value=\"wc-1234567890\" />", "wc-1234567890"),
            Triple("db.go", "dsn := \"postgres://admin:P4ssw0rd!@db.local:5432/app\"", "P4ssw0rd!"),
            Triple("settings.py", "SECRET_KEY = \"django-insecure-abc123xyz\"", "django-insecure-abc123xyz"),
        )
        for ((file, line, secret) in cases) {
            val result = check(line, file, secret)
            assertEquals(1, result.secrets.size, "$file: $line → ${result.text}")
        }
        assertEquals(SecretType.PASSWORD, SecretRedactor.redact("DB_PASSWORD=supersecret123", ".env").secrets.single().type)
        assertEquals(SecretType.CONNECTION_STRING_PASSWORD, SecretRedactor.redact("mongodb+srv://u:abcdef123@cluster", "a.js").secrets.single().type)
        // El comentario final y los espacios no forman parte del valor.
        assertEquals("DB_PASSWORD=supe${"$mask".repeat(8)}23 # producción", SecretRedactor.redact("DB_PASSWORD=supersecret123 # producción", ".env").text)
    }

    @Test
    fun `no se oculta lo que no es un secreto`() {
        val text = """
            passwordHint = "Introduce tu contraseña"
            password = ""
            password: ${'$'}{DB_PASSWORD}
            val token = process.env.TOKEN
            bypass = "algo normal"
            key = "pref_theme"
            enabled = "true"
            apiKeyHeaderName = "X-Api-Key"
            password_reset_url = "/reset"
            authorization = "${'$'}{AUTH}"
            val compass = "norte magnético"
        """.trimIndent()
        val result = SecretRedactor.redact(text, "App.kt")
        assertEquals(emptyList(), result.secrets, "Falsos positivos: ${result.secrets}")
        assertEquals(text, result.text)
        val env = SecretRedactor.redact("PASSWORD=\${DB_PASSWORD}\nTOKEN=\$GITHUB_TOKEN\nDEBUG=true\n", ".env")
        assertEquals(emptyList(), env.secrets)
        // Textos de interfaz (recursos de Android, traducciones): no son credenciales.
        val labels = "<string name=\"field_password\">Contraseña</string>\n<string name=\"field_new_password\">Contraseña nueva</string>\n" +
            "<string name=\"msg_wrong_password\">Contraseña incorrecta.</string>\n"
        assertEquals(emptyList(), SecretRedactor.redact(labels, "strings.xml").secrets)
        assertEquals(emptyList(), SecretRedactor.redact("  password: Introduce tu contraseña\n", "es.yml").secrets)
    }

    @Test
    fun `nombres sensibles por palabras`() {
        assertEquals(SecretType.PASSWORD, SecretRedactor.sensitiveKind("dbPassword"))
        assertEquals(SecretType.PASSWORD, SecretRedactor.sensitiveKind("DB_PASS"))
        assertEquals(SecretType.SECRET, SecretRedactor.sensitiveKind("API_KEY"))
        assertEquals(SecretType.SECRET, SecretRedactor.sensitiveKind("client_secret"))
        assertEquals(SecretType.SECRET, SecretRedactor.sensitiveKind("accessToken"))
        assertEquals(null, SecretRedactor.sensitiveKind("bypass"))
        assertEquals(null, SecretRedactor.sensitiveKind("key"))
        assertEquals(null, SecretRedactor.sensitiveKind("passwordHint"))
        assertEquals(null, SecretRedactor.sensitiveKind("tokenUrl"))
        assertEquals(listOf("user", "login", "controller"), SecretRedactor.splitWords("UserLoginController"))
    }

    @Test
    fun `secretos cortos y caracteres no ASCII no se muestran`() {
        val result = SecretRedactor.redact("password = \"ñandú‮abcdefghijk\"", "a.kt")
        val s = result.secrets.single()
        assertTrue(s.masked.filter { it != mask }.all { it in '!'..'~' }, "Solo ASCII visible: ${s.masked}")
        val short = SecretRedactor.redact("pwd = \"abc12\"", "a.kt").secrets.single()
        assertTrue(short.masked.all { it == mask })
    }

    @Test
    fun `varias apariciones solapadas se cuentan una vez`() {
        val result = check("aws_access_key_id = \"AKIAIOSFODNN7EXAMPLE\"", "creds.ini", "AKIAIOSFODNN7EXAMPLE")
        assertEquals(1, result.secrets.size)
    }

    @Test
    fun `texto hostil no bloquea el redactor`() {
        val hostile = buildString {
            repeat(20_000) { append("password=\"").append("a".repeat(40)).append("\"; ") }
            append('\n')
            append("x".repeat(500_000))
            append("\n-----BEGIN PRIVATE KEY-----")
            repeat(50_000) { append("eyJ") }
            append("\n")
            repeat(30_000) { append("sk-").append("-".repeat(10)) }
        }
        val time = measureTime { SecretRedactor.redact(hostile, ".env") }
        assertTrue(time.inWholeSeconds < 10, "Tardó $time")
    }
}
