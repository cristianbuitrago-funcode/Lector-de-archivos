# Fase 4 — Lenguajes y tecnologías

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación:** cada tecnología muestra dónde se encontró y su confianza.

## Objetivo

Reconocer los lenguajes del proyecto y contar sus líneas de código, comentarios y en blanco con análisis sintáctico;
y detectar frameworks, bases de datos y servicios externos con un motor de reglas declarativas, mostrando de cada
tecnología la evidencia (archivo y línea) y la confianza.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `native:treesitter` (nuevo) | **tree-sitter 0.25.1 y 15 gramáticas** (JavaScript, TypeScript, TSX, Python, Java, Kotlin, PHP, C#, Dart, SQL, GDScript, HTML, CSS, C, C++) compiladas con el NDK para arm64-v8a, armeabi-v7a y x86_64, con páginas de 16 KB. Las fuentes se descargan de npm y se comprueba su integridad SHA-512 antes de usarlas; no se ejecuta nada de lo descargado. **Puente JNI propio**: una llamada por archivo, recorrido iterativo, tiempo máximo con cancelación, listas acotadas. Las licencias de las gramáticas viajan en el APK. |
| `engine:languages` (nuevo) | **Detección del lenguaje** de 49 lenguajes y formatos por nombre, extensión, línea `#!` y contenido en los casos ambiguos (`.h` de C o C++, `.m` de Objective-C o MATLAB, `.ts` de TypeScript o Qt). **Recuento de líneas** de código, comentario y en blanco: con el árbol sintáctico para los 15 lenguajes con gramática, o con un analizador léxico de comentarios y cadenas para el resto. **Importaciones** de cada archivo (incluidos `require()` e `import()` dinámicos fuera de comentarios). **Resaltado de sintaxis** (7 categorías) para el visor. Errores de sintaxis por archivo. |
| `engine:dependencies` (nuevo) | **Dependencias directas declaradas** con su versión, alcance (ejecución, desarrollo, pruebas, compilación, *peer*, opcional, plataforma) y línea: npm, Composer, Gradle (incluidos los catálogos de versiones `libs.versions.toml`, *bundles* y complementos), Maven (con propiedades), requirements.txt, pyproject.toml (PEP 621, Poetry, grupos), Pipfile, pubspec.yaml, .NET (csproj, packages.config, SDK web), go.mod (sin las indirectas), Cargo.toml, Gemfile (con grupos), Podfile, CMake y complementos de Unreal. Lector YAML por eventos y lector TOML parcial. |
| `engine:technologies` (nuevo) | **Motor de reglas declarativas** con un catálogo YAML de **208 tecnologías** en 15 categorías: plataformas móviles, frameworks web y de servidor, interfaz, **16 bases de datos y 20 tecnologías de acceso a datos (ORM)**, **32 servicios externos y de nube** (Firebase, Supabase, AWS, Google Cloud, Azure, Stripe, PayPal, Mercado Pago, Twilio, SendGrid, OpenAI, Anthropic, Gemini, Google Maps, Sentry…), autenticación, red, pruebas, compilación, despliegue y motores de juego. Señales con peso: dependencia declarada, archivo característico, importación, texto o patrón en el código. **Confianza**: confirmada, probable o posible; las **menciones** en documentación o comentarios nunca suman y se listan como no concluyentes. Cada tecnología lleva hasta 8 evidencias con archivo y línea, el total de evidencias, sus versiones declaradas y los subproyectos donde aparece. |
| `engine:pipeline` (nuevo) | Composición del análisis completo (ingesta → inventario → lenguajes → dependencias → tecnologías), en un único recorrido del comprimido. |
| `engine:inventory` | El examen del contenido incluye ahora el análisis de lenguaje (sobre el texto ya redactado), el resaltado de la copia y los totales por lenguaje; entrega el texto redactado a las etapas nuevas. |
| `engine:api` | Esquema de resultados v4: lenguaje y recuento en la ficha, totales por lenguaje, dependencia declarada, tecnología y resaltado de la copia. El proceso principal valida que el resaltado esté dentro del texto y ordenado, y que toda evidencia de una tecnología tenga archivo. |
| `data` | BD versión 4 (resaltado junto a la copia del código; migración automática desde la 3). |
| `app` | **Informe**: apartado **Tecnologías, bases de datos y servicios** agrupado por categoría, con la confianza de cada una y, al tocarla, sus evidencias (archivo:línea) y versiones; tocar una evidencia **abre ese archivo en el Explorador**. Apartado aparte de **menciones no concluyentes**. Apartado **Lenguajes** con líneas de código, comentarios y en blanco, y el método usado. El total separa el código de la documentación y los datos. **Ficha del archivo**: lenguaje, nivel de soporte, recuento, método y errores de sintaxis; tecnologías encontradas en ese archivo. **Visor con resaltado de sintaxis** (manteniendo visibles los caracteres invisibles y bidireccionales). APK por arquitectura con las bibliotecas nativas comprimidas. |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:languages` | 19 | Las 15 gramáticas se cargan. Por lenguaje: líneas de código, comentario y en blanco, e importaciones (JavaScript con `require` e `import()` y sin los de los comentarios, TypeScript, TSX, Python con importaciones múltiples, Java con `static`, Kotlin, C#, Dart, PHP, C, C++ y CSS); HTML, SQL y GDScript. Código roto (errores contados, sin fallar). Tiempo agotado → recuento léxico con el motivo. 40 000 líneas en menos de 5 s. Resaltado en caracteres con texto no ASCII y emojis. Rangos de comentarios sintácticos y léxicos. Recuento léxico con bloques, cadenas, CRLF y sin salto final. Detección del lenguaje y casos ambiguos. |
| `engine:dependencies` | 10 | Un manifiesto de cada ecosistema con versión, alcance y línea; catálogo de versiones de Gradle; dependencias locales y transitivas excluidas; opciones de pip con credenciales ignoradas; nombres no válidos y límite por manifiesto; YAML: alias sin expandir (*bomba YAML* en menos de 2 s), profundidad máxima, documento inválido. |
| `engine:technologies` | 5 | Búsqueda de muchos textos a la vez (Aho–Corasick): apariciones solapadas, sin distinguir mayúsculas, parada anticipada. El catálogo empaquetado se carga y valida (≥ 150 tecnologías, ≥ 15 bases de datos, ≥ 20 servicios externos, todas alcanzables); catálogos erróneos rechazados (repetición acotada grande, patrón sin aviso, ecosistema o categoría desconocidos, peso fuera de rango); importaciones por módulo o submódulo sin falsos prefijos; comodines de archivo. |
| `engine:pipeline` | 6 | **Criterio de aceptación** con un proyecto de tres partes (Laravel, Flutter + Firebase, Express + PostgreSQL): cada tecnología con su confianza y su evidencia en el archivo y la línea exactos (dependencia, archivo característico, importación, código); MySQL "probable" solo por la configuración; Redis y Stripe como menciones; tecnologías ausentes no inventadas. Ningún secreto completo en ningún registro. Lenguajes, líneas, dependencias con subproyecto y resaltado. Android con catálogo de versiones (complemento, BOM de Compose, Room con su importación). Proyecto sin código: ninguna tecnología y la cobertura lo explica. Sin catálogo, el análisis sigue y la cobertura lo dice. |
| `engine:inventory` | 14 | Las de la Fase 3, ahora con el análisis de lenguaje integrado. |
| `engine:api` | 28 | Las anteriores con el esquema v4. |
| `engine:ingest`, `engine:core`, `engine:security` | 36 + 10 + 8 | Sin cambios de comportamiento. |
| `data` | 18 | Las anteriores. |
| `analyzer-service` | 1 | Las anteriores. |
| `app` (Robolectric) | 27 | Las anteriores más: el resaltado se guarda con la copia y vuelve con posiciones válidas; reparto del resaltado por líneas y recorte seguro; **recorrido del informe**: tecnología confirmada con versión, menciones aparte, lenguajes, evidencias con archivo:línea y apertura del archivo en el Explorador, ficha con lenguaje y tecnologías, visor con resaltado. |
| **Total** | **182** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto y **prueba con un proyecto real**
(este repositorio, 349 archivos, en unos 4 s en el PC; un proyecto sintético de 3 000 archivos y 65 MB, en 25 s): Android, Jetpack Compose (con las versiones del BOM), Room,
JUnit, Robolectric, Kotlin Coroutines, Gradle, CMake y GitHub Actions confirmados con su archivo y línea; 116
archivos Kotlin y 151 de C++ contados sintácticamente; las menciones de la documentación (Firebase, Stripe, AWS…)
aparecen como no concluyentes.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Además de las de fases anteriores:
- `FullAnalysisInstrumentedTest`: en el proceso aislado, **tree-sitter nativo** cuenta sintácticamente los archivos
  Kotlin y JavaScript, Express sale confirmado con su importación en el archivo y la línea correctos y la copia
  guardada tiene resaltado.
- `MigrationInstrumentedTest`: la BD de la Fase 3 migra a la versión 4 conservando la copia del código.

**Resultado: 13 de 13 correctas en las tres versiones** (ejecución n.º 17 del CI: 3 de aislamiento, 3 de cifrado,
3 de migración, 3 de análisis completo y 1 recorrido de extremo a extremo). Las ejecuciones 15 y 16 fallaron por los
incidentes 10 y 11.

### Errores encontrados y corregidos durante la fase

1. Las importaciones `import static org.junit.Assert.*` de Java guardaban un punto final.
2. En los bloques `plugins { }` de Gradle, `alias(libs.plugins.x)` se cortaba por la palabra "plugins" y el
   complemento del catálogo de versiones se perdía.
3. La regla de Jetpack Compose (`androidx.compose.*`) no reconocía el BOM (`androidx.compose:compose-bom`).
4. Con el proyecto real, una URL `postgres://` dentro de una **prueba** del redactor hacía "probable" a PostgreSQL.
   Ahora un texto encontrado solo en archivos de prueba suma la mitad y lo indica ("Una prueba contiene…").
5. Android mostraba como "versión" la del complemento de Gradle (AGP). Las reglas pueden marcar `version: false`.
6. El APK de depuración ocupaba 117 MB porque las bibliotecas nativas iban sin comprimir. Ahora se comprimen y hay
   un APK por arquitectura: **25,6 MB** el de arm64 en depuración y **8,3 MB** en publicación.
7. Lint: un texto con número que debía ser plural, un texto sin usar, y el aviso de versión nueva de
   snakeyaml-engine, que se mantiene en la rama 2.x a propósito (ADR 0006).
8. **Rendimiento con proyectos grandes.** Con un proyecto sintético de 3 000 archivos (65 MB de código), leerlo
   tardaba 46 s en el PC. El perfil mostró que más de la mitad del tiempo eran patrones de la Fase 3 sin texto de
   aviso: RE2/J (que no tiene autómata determinista) los evaluaba sobre cada archivo. Ahora cada patrón lleva los
   textos literales de los que al menos uno debe aparecer; el resultado sobre el proyecto real es idéntico
   (373 registros comparados) y la lectura baja a **23 s**, dos tercios de ellos en tree-sitter. Los textos del
   catálogo de tecnologías se buscan además todos a la vez, en una sola pasada (Aho–Corasick).
9. Al revisar las capturas: el total de "líneas de código" incluía Markdown, texto y JSON. Ahora cada lenguaje lleva
   su tipo (programación, marcado, datos o documentación) y solo los dos primeros cuentan como código; el resto se
   indica aparte. Y entre evidencias de igual fuerza se muestran antes las del código del producto que las de las
   pruebas.

Y el que detectó el CI en los tres emuladores (en el PC todo pasaba):

10. **El proceso aislado se cerraba al empezar cualquier análisis.** El cargador del catálogo validaba los patrones
    con una expresión de `java.util.regex` que tenía una `}` sin escapar. La JVM del PC la acepta; Android usa ICU,
    que la rechaza: la clase del catálogo no se podía inicializar y el proceso aislado se cerraba antes de enviar
    nada (también con los RAR). Se vio en el registro del emulador. Ahora esa comprobación usa RE2/J (el mismo código
    en el PC y en Android) con la llave escapada, y si el catálogo no se pudiera cargar el análisis sigue sin
    tecnologías y la cobertura lo explica (prueba nueva).
11. Con esa corrección, en Android 15 falló una vez la prueba de extremo a extremo de la Fase 1: la pulsación de
    "Crear acceso" llegó mientras el teclado aún se animaba y el botón se movió. La prueba ahora cierra el teclado y
    espera a que la interfaz quede quieta antes de pulsar.

## Cómo probarlo en tu teléfono

1. Instala **`app-arm64-v8a-debug.apk`** encima del anterior (tus análisis se conservan; los de fases anteriores no
   tienen lenguajes ni tecnologías hasta que los vuelvas a analizar).
2. Analiza un proyecto real en **Nuevo análisis**. Al terminar, el informe muestra **Tecnologías, bases de datos y
   servicios** y **Lenguajes**.
3. Toca una tecnología: verás por qué se detectó (dependencia, archivo, importación, código) con el archivo y la
   línea. Toca una evidencia para abrir ese archivo en el Explorador.
4. Comprueba la confianza: lo declarado en un manifiesto sale "Confirmado"; lo que solo aparece en el código,
   "Probable"; lo que solo mencionan el README o un comentario, en **Menciones no concluyentes**.
5. En el Explorador, abre un archivo de código: la ficha muestra su lenguaje, las líneas de código, comentarios y en
   blanco, y si se contaron con el árbol sintáctico. Pulsa **Ver código** para ver el resaltado.
6. Si el proyecto tiene una clave en un archivo de configuración, comprueba que la tecnología se detecta igual y que
   la clave sigue mostrando solo 4 + 2 caracteres.

## Diferencias respecto a la arquitectura v0.2

- **Puente JNI propio** en lugar de KTreeSitter, y tree-sitter se adelanta de la Fase 5 a esta (ADR 0006).
- Fuentes de las gramáticas **descargadas de npm** con verificación de integridad, en lugar de guardarlas en el
  repositorio.
- **TOML**: lector parcial propio en lugar de tomlj. **YAML**: snakeyaml-engine solo por eventos.
- Los lectores de manifiestos se adelantan a esta fase en `engine:dependencies`; los archivos de bloqueo y las
  vulnerabilidades siguen en la Fase 7.
- Módulo nuevo `engine:pipeline` para componer el análisis.

## Errores y limitaciones pendientes

1. **Lenguajes sin gramática** (Go, Rust, Ruby, Swift, Scala, Lua, Shell, Vue/Svelte como archivo completo…): líneas
   contadas con el analizador léxico, sin resaltado ni importaciones. En HTML no se analizan los `<script>` y
   `<style>` incrustados; los *docstrings* de Python cuentan como código.
2. **Resaltado genérico**: es correcto en lo esencial pero menos fino que el de un editor (por ejemplo, no distingue
   variables de propiedades).
3. **Catálogo**: una tecnología que no está en él no se detecta (la cobertura lo dice). Un texto característico en el
   código puede ser un falso "probable" (en este repositorio, el patrón de webhooks de Slack del redactor marca
   "Slack API" como probable); la evidencia permite comprobarlo.
4. Las referencias a catálogos de versiones de Gradle con otro nombre que `libs`, o declarados en
   `settings.gradle`, pueden no resolverse (se informa en la cobertura). Las versiones de los BOM y de los catálogos
   centralizados de .NET no se aplican a cada dependencia.
5. Las tecnologías de **Go, Rust y Swift** se detectan por sus manifiestos y textos, no por sus importaciones.
6. El APK universal ocupa 39,5 MB (depuración): para el móvil conviene el de arm64.
7. No hay todavía una pantalla de licencias en la app (los textos van dentro del APK).
8. Pendientes de fases anteriores: biometría probada solo con simulación, APK de publicación sin firma propia,
   contraseñas en `String` dentro de Compose, detekt/ktlint sin configurar.

## Siguiente fase

**Fase 5 — Relaciones** (en dos partes): 5A, extractores por lenguaje, resolución de importaciones a archivos del
proyecto, grafo e impacto de cambios; 5B, analizadores por framework y enlace entre frontend y backend. Criterio:
cada conexión muestra sus 5 atributos y su evidencia.
