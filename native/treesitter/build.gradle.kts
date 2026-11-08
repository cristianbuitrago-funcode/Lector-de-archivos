// Biblioteca nativa libfuncode_treesitter (núcleo de tree-sitter + 15 gramáticas + JNI) para Android. Las
// declaraciones JNI viven en :engine:languages (com.funcodestudiocb.analyzer.engine.languages.treesitter).
//
// Las fuentes C no se guardan en el repositorio (los parser.c generados ocupan más de 100 MB): se descargan del
// registro de npm, donde los mantenedores publican cada gramática con su código generado, y se verifica la
// integridad SHA-512 de cada paquete antes de usarlo, igual que el resto de dependencias.
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

plugins {
    alias(libs.plugins.android.library)
}

/** nombre|versión|integridad SHA-512 (npm)|carpeta de destino */
val treeSitterPackages = listOf(
    "tree-sitter|0.25.1|sha512-mrcEdkYtHfrK1A6fs3O6FxkBo0Qig5XUXqHhxUOQu0bmPo00QF4XaSx4edpazdHwxnSCjlGKGgIqWdaN4dvTLA==|tree-sitter",
    "tree-sitter-javascript|0.25.0|sha512-1fCbmzAskZkxcZzN41sFZ2br2iqTYP3tKls1b/HKGNPQUVOpsUxpmGxdN/wMqAk3jYZnYBR1dd/y/0avMeU7dw==|javascript",
    "tree-sitter-typescript|0.23.2|sha512-e04JUUKxTT53/x3Uq1zIL45DoYKVfHH4CZqwgZhPg5qYROl5nQjV+85ruFzFGZxu+QeFVbRTPDRnqL9UbU4VeA==|typescript",
    "tree-sitter-python|0.25.0|sha512-eCmJx6zQa35GxaCtQD+wXHOhYqBxEL+bp71W/s3fcDMu06MrtzkVXR437dRrCrbrDbyLuUDJpAgycs7ncngLXw==|python",
    "tree-sitter-java|0.23.5|sha512-Yju7oQ0Xx7GcUT01mUglPP+bYfvqjNCGdxqigTnew9nLGoII42PNVP3bHrYeMxswiCRM0yubWmN5qk+zsg0zMA==|java",
    "@tree-sitter-grammars/tree-sitter-kotlin|1.1.0|sha512-vlVXaxEE8t2kpJgfZpa8XVvxcnKw9AYtRTgy7KWjsDmAsadk06RxAT80IXOgGQnmM9i/orQn1nD84gPNUHu6DQ==|kotlin",
    "tree-sitter-php|0.24.2|sha512-zwgAePc/HozNaWOOfwRAA+3p8yhuehRw8Fb7vn5qd2XjiIc93uJPryDTMYTSjBRjVIUg/KY6pM3rRzs8dSwKfw==|php",
    "tree-sitter-c-sharp|0.23.5|sha512-xJGOeXPMmld0nES5+080N/06yY6LQi+KWGWV4LfZaZe6srJPtUtfhIbRSN7EZN6IaauzW28v6W4QHFwmeUW6HQ==|c-sharp",
    "tree-sitter-dart|1.0.0|sha512-Ve5YMPJjjGW9LEsO+MngAOibQsw5obFp+bUT41pvwdcXWRwJImOWs3eaPi6AubEiBmc09qvhdvxeIXvxlhMnug==|dart",
    "@derekstride/tree-sitter-sql|0.3.11|sha512-YRtTXVWK4n7/3BUGchhGcW/IF1u2ldR2fd0t3Uw7ymbGXcRfiG3v1T6N43jQKXz2LwN+Nx+Ab96T0HtusBVH9g==|sql",
    "tree-sitter-gdscript|6.1.0|sha512-Uy5+GWLkec2JaS1mamiJYsC9j/JoW2FxFq4bnji95gyTtMTsqO6+RSVIaBTU/vRGSNEi3PVDZzyV0j3B4RdntA==|gdscript",
    "tree-sitter-html|0.23.2|sha512-TN+l+7cCeLx9db/1RhRSqMAZO/266Oh2BHb8J8hMSSFLuzYvFTYP/UnD3S0mny5awzw05KzFNgu2vnwzN9wVJg==|html",
    "tree-sitter-css|0.25.0|sha512-FRc9R8ePrwJiUhZsuZ/wcFQ3K8Z+9yCgDrrUjuYswGWlN89UvcB9vslTUGZElQWGwhS8sUw3/r2n4lpb2sxT4Q==|css",
    "tree-sitter-c|0.24.1|sha512-lkYwWN3SRecpvaeqmFKkuPNR3ZbtnvHU+4XAEEkJdrp3JfSp2pBrhXOtvfsENUneye76g889Y0ddF2DM0gEDpA==|c",
    "tree-sitter-cpp|0.23.4|sha512-qR5qUDyhZ5jJ6V8/umiBxokRbe89bCGmcq/dk94wI4kN86qfdV8k0GHIUEKaqWgcu42wKal5E97LKpLeVW8sKw==|cpp",
)

/**
 * Descarga los paquetes de npm, comprueba su integridad y extrae solo el código C (y las licencias).
 * No ejecuta nada de lo descargado.
 */
abstract class FetchNpmSources : DefaultTask() {
    @get:Input
    abstract val packages: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        for (spec in packages.get()) {
            val (name, version, integrity, dir) = spec.split('|')
            val file = name.substringAfterLast('/')
            val bytes = URI("https://registry.npmjs.org/$name/-/$file-$version.tgz").toURL().openStream().use { it.readBytes() }
            val expected = integrity.removePrefix("sha512-")
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
            check(actual == expected) { "Integridad incorrecta en $name@$version" }
            extract(bytes, File(out, dir))
        }
    }

    private fun wanted(path: String): Boolean =
        path.endsWith(".c") || path.endsWith(".h") || path.substringAfterLast('/').startsWith("LICENSE")

    /** Lector tar mínimo (ustar + cabeceras pax de ruta larga), sin enlaces ni rutas que salgan del destino. */
    private fun extract(tgz: ByteArray, dest: File) {
        val data = GZIPInputStream(ByteArrayInputStream(tgz)).use { it.readBytes() }
        var pos = 0
        var paxPath: String? = null
        val root = dest.canonicalFile
        while (pos + 512 <= data.size) {
            val header = data.copyOfRange(pos, pos + 512)
            if (header.all { it == 0.toByte() }) break
            fun field(from: Int, len: Int) = String(header, from, len, Charsets.UTF_8).trimEnd('\u0000').trim()
            val size = field(124, 12).ifEmpty { "0" }.toLong(8).toInt()
            val type = header[156].toInt().toChar()
            val prefix = field(345, 155)
            val name = paxPath ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + field(0, 100)
            val body = pos + 512
            paxPath = null
            when (type) {
                'x' -> {
                    val pax = String(data, body, size, Charsets.UTF_8)
                    paxPath = pax.lineSequence().firstOrNull { it.contains(" path=") }?.substringAfter(" path=")
                }
                '0', '\u0000' -> {
                    val relative = name.removePrefix("package/")
                    if (wanted(relative) && !relative.split('/').contains("..")) {
                        val target = File(root, relative).canonicalFile
                        check(target.path.startsWith(root.path + File.separator)) { "Ruta fuera del destino: $name" }
                        target.parentFile.mkdirs()
                        target.writeBytes(data.copyOfRange(body, body + size))
                    }
                }
            }
            pos = body + ((size + 511) / 512) * 512
        }
    }
}

val fetchTreeSitterSources = tasks.register<FetchNpmSources>("fetchTreeSitterSources") {
    group = "build"
    description = "Descarga y verifica las fuentes de tree-sitter y de las gramáticas (npm)."
    packages.set(treeSitterPackages)
    outputDir.set(layout.buildDirectory.dir("ts-src"))
}

val treeSitterSourceDir: String = layout.buildDirectory.dir("ts-src").get().asFile.absolutePath

android {
    namespace = "com.funcodestudiocb.analyzer.nativetreesitter"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DTS_SRC=$treeSitterSourceDir")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// La compilación nativa necesita las fuentes descargadas.
tasks.configureEach {
    if (name.contains("CMake") || name.contains("ExternalNative")) dependsOn(fetchTreeSitterSources)
}

/**
 * Las licencias (MIT) de tree-sitter y de cada gramática viajan dentro del APK, en `assets/licencias/tree-sitter/`,
 * como exige su licencia al distribuir el código compilado.
 */
abstract class CollectLicenses : DefaultTask() {
    @get:InputDirectory
    abstract val sources: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val target = outputDir.get().asFile.resolve("licencias/tree-sitter")
        target.deleteRecursively()
        target.mkdirs()
        sources.get().asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
            val license = dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.uppercase().startsWith("LICENSE") }
                ?: error("Falta la licencia de ${dir.name}")
            license.copyTo(target.resolve("${dir.name}.txt"), overwrite = true)
        }
    }
}

val collectTreeSitterLicenses = tasks.register<CollectLicenses>("collectTreeSitterLicenses") {
    sources.set(fetchTreeSitterSources.flatMap { it.outputDir })
    outputDir.set(layout.buildDirectory.dir("generated/licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(collectTreeSitterLicenses, CollectLicenses::outputDir)
    }
}
