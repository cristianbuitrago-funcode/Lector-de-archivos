# Licencias de componentes de terceros

| Componente | Uso | Licencia |
|------------|-----|----------|
| UnRAR 7.3.2 (RARLAB, Alexander Roshal) | Lectura de RAR4 y RAR5 en el proceso aislado. **Modificado** (apertura por descriptor, ver ADR 0004). | Licencia de UnRAR (freeware; ver `native/unrar/LICENSE-UnRAR.txt`) |
| Apache Commons Compress, Commons IO, Commons Lang | Lectura de ZIP | Apache 2.0 |
| RE2/J (Google) | Expresiones regulares de tiempo lineal sobre el contenido del proyecto | BSD de 3 cláusulas (licencia de Go) |
| tree-sitter 0.25.1 (Max Brunsfeld y colaboradores) | Análisis sintáctico en el proceso aislado | MIT |
| Gramáticas de tree-sitter: JavaScript, TypeScript/TSX, Python, Java, PHP, C#, HTML, CSS, C, C++ (organización tree-sitter); Kotlin (tree-sitter-grammars); Dart (UserNobody14); SQL (Derek Stride); GDScript (PrestonKnopp y colaboradores) | Lenguajes con análisis sintáctico | MIT (el texto de cada licencia va dentro del APK, en `assets/licencias/tree-sitter/`) |
| snakeyaml-engine 2.10 | Lectura por eventos de YAML (pubspec.yaml y catálogo de tecnologías) | Apache 2.0 |
| SQLCipher for Android | Base de datos cifrada | BSD |
| Bouncy Castle | Argon2id | MIT |
| AndroidX, Jetpack Compose, Room, Kotlin, kotlinx | App y motor | Apache 2.0 |
| libarchive (solo archivos de prueba) | Corpus RAR de las pruebas automáticas; no se incluye en la app | BSD de 2 cláusulas |
| zip4j (solo pruebas) | Generar ZIP cifrados de referencia; no se incluye en la app | Apache 2.0 |

## Párrafo exigido por la licencia de UnRAR

> UnRAR source code may be used in any software to handle RAR archives without limitations free of charge, but
> cannot be used to develop RAR (WinRAR) compatible archiver and to re-create RAR compression algorithm, which is
> proprietary. Distribution of modified UnRAR source code in separate form or as a part of other software is
> permitted, provided that full text of this paragraph, starting from "UnRAR source code" words, is included in
> license, or in documentation if license is not available, and in source code comments of resulting package.
