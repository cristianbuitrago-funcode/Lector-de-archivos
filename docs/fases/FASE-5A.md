# Fase 5A — Relaciones entre archivos

- **Estado:** terminada, pendiente de aprobación (la 5B empieza cuando la apruebes)
- **Criterio de aceptación de la Fase 5:** cada conexión muestra sus 5 atributos y su evidencia. La 5A lo cumple para
  las relaciones de lenguaje; la 5B añadirá las de cada framework con la misma ficha.

## Objetivo

Encontrar qué archivos usan a qué otros archivos, sin ejecutar nada: importaciones, inclusiones, herencia,
implementación de interfaces, uso sin importación dentro del mismo paquete y recursos de páginas y estilos. Con ellas,
construir el grafo del proyecto y calcular la importancia de cada archivo, los ciclos de dependencias, los archivos
que nada usa y el impacto de cambiar o eliminar cada conexión. Ninguna conexión se inventa: cada una lleva su
evidencia (archivo y línea) y su confianza, y lo que no se puede resolver se dice.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `native:treesitter` | El puente JNI devuelve también el **nombre de cada declaración** (el campo `name` de la gramática), sin coste extra: sigue siendo una llamada por archivo. |
| `engine:languages` | **Extractor de símbolos** (`SymbolExtractor`). Importaciones con lo que se importa de cada una: JavaScript/TypeScript (nombrados, por defecto y `* as`), Python (`from … import`), Java y Kotlin (incluidos `static` y `.*`), C# (`using`, alias y `static`), PHP (`use` con llaves, `require`/`include`), Dart (`show`), C y C++ (`#include "…"` y `<…>` distinguidos, y si están dentro de un `#if`/`#ifdef`), CSS (`@import`) y GDScript (`extends`). **Declaraciones de nivel superior** (clases, interfaces, enumeraciones, objetos, funciones, tipos, constantes) con su línea y sus **supertipos** (`extends`, `implements`, `with`, `:` de Kotlin y C#, bases de Python y C++). Paquete o espacio de nombres. Si el árbol tiene errores de sintaxis, un **respaldo léxico** recupera las declaraciones que el árbol pierde. |
| `engine:relations` (nuevo) | **Recolector** en el mismo recorrido del comprimido y sobre el texto ya redactado: importaciones, declaraciones, referencias de HTML (`script`, `link`, `img`, `a`…), `url()` de CSS, `@use`/`@import` de SCSS, `preload`/`load` de GDScript y bloques `<script>` de Vue y Svelte; nombres usados en Java, Kotlin, C# y PHP (guardados como huellas, no como texto); y la configuración que cambia la resolución: `tsconfig`/`jsconfig` (`paths`, `baseUrl`), `package.json` (monorepos), PSR-4 de Composer, `pubspec.yaml` y `project.godot`. **Resolutores por ecosistema** (ver tabla abajo). **Grafo**: componentes fuertemente conexos (ciclos), PageRank (importancia), dependientes directos y en cadena, y alcance desde los puntos de entrada; todo iterativo. **Etapa** que emite cada conexión con sus cinco atributos, la ficha de grafo de cada archivo, los ciclos, el resumen y la cobertura. |
| `engine:api` | Esquema de resultados **v5**: conexión (`relation`), ficha de grafo (`file_graph`), ciclo (`dependency_cycle`) y resumen (`relations_summary`). El proceso principal valida cada uno: una conexión rota no tiene destino; las demás tienen exactamente un destino (archivo o paquete externo); la evidencia no puede faltar; la importancia está entre 0 y 1; listas acotadas. |
| `engine:inventory` | Comparte con las etapas siguientes los puntos de entrada y las funciones de cada archivo. |
| `engine:technologies` | Las inclusiones de C/C++ llegan ahora como `<stdio.h>`; el catálogo de tecnologías las compara sin los ángulos. |
| `engine:pipeline` | Añade la etapa de relaciones al análisis completo. |
| `app` | **Informe → Relaciones entre archivos**: total de conexiones internas y externas, **posibles errores** (referencias a archivos que no están; al tocar una se abre el archivo de origen), los **10 archivos más importantes**, los **ciclos** y los archivos **sin referencias encontradas**. **Explorador → ficha del archivo → Relaciones**: importancia (puesto *n* de *m*), cuántos archivos lo usan directamente y en cadena, si está en un ciclo, si se llega a él desde un punto de entrada, las listas **Usa (n)** y **Lo usan (n)** y los símbolos que declara. **Ficha de cada conexión** con sus cinco atributos (ver abajo); el origen y el destino se pueden tocar para abrir esos archivos. |

### Los cinco atributos de cada conexión

| Atributo | Qué muestra la ficha |
|----------|----------------------|
| **Origen y destino** | Archivo y línea de origen y lo escrito en el código (`./api`, `com.demo.data.Repo`…). Destino: un archivo del proyecto, un paquete externo o "No existe en el proyecto" si está rota. |
| **Tipo** | Importación, herencia, implementación, uso sin importación (mismo paquete) o recurso. Los tipos de framework (ruta → controlador, HTTP, base de datos, configuración, inyección, IPC) ya existen en el esquema y se rellenan en la 5B. |
| **Información intercambiada** | Lo que se importa o se usa (`api`, `Repo`, `AppCompatActivity`…). Si se importa el módulo entero o no se puede saber, lo dice ("No determinable estáticamente"). |
| **Impacto de modificarla o eliminarla** | Símbolos que dejarían de estar definidos en el origen; cuántos archivos usan el destino directamente y en cadena (radio de impacto); desde qué puntos de entrada se llega al origen. Para un paquete externo, cuántos archivos lo usan; para una conexión rota, a quién afecta ya. |
| **Confianza y evidencia** | Verificada, probable, no verificada o rota, con su significado, y la evidencia: "Importa «./api»: ruta relativa", archivo y línea. |

### Cómo se resuelve cada ecosistema

| Ecosistema | Verificada | Probable | No verificada | Rota |
|------------|-----------|----------|---------------|------|
| JavaScript / TypeScript | Ruta relativa con extensión o `index`, `paths`/`baseUrl` de tsconfig, paquete del monorepo, módulo de Node, paquete declarado en `package.json` | Alias `@/` o `~/` sin tsconfig | Paquete no declarado | Ruta relativa que no existe |
| Python | Módulo o paquete del proyecto (relativo o absoluto), biblioteca estándar, paquete declarado | — | Paquete no declarado | Módulo de un paquete del proyecto que no existe |
| Java / Kotlin | Clase por su paquete, clase anidada o miembro, interfaz de un `.aidl`, plataforma (`java.`, `android.`, `kotlin.`…) | `import paquete.*` (solo los archivos cuyos tipos se usan), uso sin importación en el mismo paquete, biblioteca cuyo grupo está declarado | Clase que se genera al compilar (`R`, `BuildConfig`, *bindings*…), biblioteca no declarada | El paquete es del proyecto pero no contiene la clase |
| PHP | `require`/`include`, clase por su espacio de nombres, PSR-4 de Composer, paquete declarado en Composer | Uso sin importación en el mismo espacio de nombres | Paquete no declarado | PSR-4 que apunta a un archivo que no existe |
| C# | Tipo por su espacio de nombres, alias, .NET, Unity | `using` de un espacio de nombres (solo los archivos cuyos tipos se usan), mismo espacio de nombres | Espacio de nombres externo | — |
| Dart / Flutter | Ruta relativa, `package:` del propio paquete, `dart:`, Flutter, paquete declarado | — | Paquete no declarado | Archivo que no existe |
| C / C++ | `#include "…"` junto al archivo | Ruta encontrada en otra carpeta del proyecto (carpetas de inclusión) si es única | Varias cabeceras con esa ruta, cabecera de una biblioteca cuya carpeta no está en el comprimido, inclusión dentro de `#if`/`#ifdef` | Cabecera que no existe |
| HTML / CSS / SCSS / Vue / Svelte | Ruta relativa | Ruta absoluta del sitio encontrada en `public/`, `static/`, `www/`, `dist/`… | Ruta absoluta del servidor que no está en el comprimido | Ruta relativa que no existe |
| GDScript (Godot) | `res://` y `class_name` del proyecto | Clase del propio motor Godot | — | `res://` que no existe |

En todos: una ruta que solo coincide **cambiando mayúsculas** es "probable" (funciona en Windows y falla en Linux y
Android). Las inclusiones del sistema (`#include <stdio.h>`) no se registran como conexión: la cobertura dice
cuántas hay.

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:relations` (nuevo) | 6 | **Criterio de aceptación** en cinco proyectos de prueba: toda conexión tiene origen, destino (o rota), tipo, impacto y evidencia con archivo y línea. **JavaScript/TypeScript**: rutas relativas, `index`, `.js` → `.ts`, `paths` de tsconfig, alias `@/`, monorepo, paquetes declarados y no declarados, módulos de Node, HTML y CSS, conexiones rotas, herencia, ciclos y archivos sin uso. **Python**: relativas, absolutas, submódulos, biblioteca estándar, paquetes y bases de clases. **Java y Kotlin**: paquete, mismo paquete, herencia, implementación, miembro de un `companion object`, clases generadas (`R`) y conexiones rotas; los scripts `.kts` no comparten paquete; una actividad no se señala como no usada. **PHP con PSR-4, C#, Dart, C (incluida la inclusión condicional) y GDScript**. **Honestidad**: si un archivo no se pudo analizar sintácticamente, no se afirma que otros archivos de su ecosistema no se usen y la cobertura lo explica. **Grafo**: componentes, PageRank, dependientes, alcanzabilidad y una cadena de 200 000 archivos sin desbordar la pila. |
| `engine:languages` | 25 | Las 19 de la Fase 4 (con las inclusiones de C/C++ entre ángulos) y 6 nuevas del extractor de símbolos: JavaScript, TypeScript, Python, Java y Kotlin, PHP + C# + Dart, C++ + GDScript (lo importado, declaraciones con línea, supertipos, paquete). |
| `engine:api` | 30 | Las anteriores con el esquema v5, y 2 nuevas: los registros de relaciones van y vuelven sin cambios y son válidos; las relaciones incoherentes se rechazan (sin evidencia, rota con destino, sin destino, con dos destinos, impacto negativo, más de 3 puntos de entrada, caracteres de control, importancia fuera de rango, ciclo de un archivo). |
| `engine:pipeline`, `engine:technologies`, `engine:dependencies`, `engine:inventory` | 6 + 5 + 10 + 14 | Las anteriores, ahora con la etapa de relaciones en el análisis completo. |
| `engine:ingest`, `engine:core`, `engine:security` | 36 + 10 + 8 | Sin cambios de comportamiento. |
| `data`, `analyzer-service` | 18 + 1 | Las anteriores. |
| `app` (Robolectric) | 28 | Las anteriores más **el recorrido de las relaciones**: informe con posibles errores y archivos sin referencias; tocar un posible error abre el archivo; ficha con **Usa (2)**; ficha de la conexión con **origen y destino, tipo, información intercambiada, impacto y confianza con su evidencia**. |
| **Total** | **197** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto, y dependencias verificadas en modo
estricto (la 5A no añade ninguna dependencia externa).

**Prueba con un proyecto real** (este repositorio: 394 archivos, 306 en el grafo; análisis completo en unos 2,7 s en
el PC, la etapa de relaciones en 0,1 s):
- **1 169 conexiones**: 773 entre archivos del proyecto y 396 con paquetes externos; **0 rotas**.
- 775 verificadas, 375 probables (sobre todo el uso sin importación dentro del mismo paquete de Kotlin) y 19 no
  verificadas, todas justificadas: 16 importaciones de `R` y `BuildConfig` (se generan al compilar),
  `androidx.annotation` (llega como dependencia transitiva, no declarada), la cabecera de tree-sitter (su carpeta se
  descarga al compilar) y una inclusión de UnRAR dentro de un `#ifdef`.
- **5 ciclos**, el mayor de 15 archivos de la app (contenedor, actividad, raíz de la interfaz…). **0 archivos sin
  referencias.**
- Los más importantes: `rar.hpp` (lo incluyen 55 archivos) y `Records.kt` (lo usan 42 directamente, 96 en cadena).

**Proyecto sintético con conexiones conocidas**: 3 000 archivos (1 000 de JavaScript, 1 000 de Python y 1 000 de
Kotlin en 50 paquetes, 37 MB), cada uno importa otros tres y los de Kotlin usan dos clases de su paquete sin
importarlas. Resultado: **14 935 conexiones, exactamente las generadas** (8 935 importaciones internas verificadas,
1 000 herencias, 2 000 usos en el mismo paquete como probables, 2 000 paquetes declarados verificados y 1 000
importaciones de `kotlinx.coroutines`, no declarado, como no verificadas), 0 rotas, y **146 archivos sin
referencias, los mismos 146 que ningún otro archivo importa** (comprobado uno a uno). La etapa de relaciones tarda
**0,9 s**; el análisis completo, 16,6 s en el PC, casi todo en tree-sitter.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Además de las de fases anteriores, `FullAnalysisInstrumentedTest`
comprueba en el proceso aislado que `server.js` → `util.js` sale verificada en la línea 3 con su impacto, que
`express` sale como paquete externo verificado y que `util.js` tiene un archivo que lo usa.

**Resultado: 13 de 13 correctas en las tres versiones** (ejecución n.º 19 del CI: 3 de aislamiento, 3 de cifrado,
3 de migración, 3 de análisis completo y 1 recorrido de extremo a extremo), y el trabajo de compilación, pruebas JVM
y Lint también correcto. A la primera, sin incidentes en los emuladores.

### Errores encontrados y corregidos durante la fase

Con el proyecto real (este repositorio), antes de dar la fase por buena:

1. **Interfaces AIDL marcadas como rotas.** `IAnalyzerService` no existe como archivo Kotlin: se genera al compilar
   desde su `.aidl`. Ahora la conexión apunta al `.aidl` y es verificada.
2. **`tree_sitter/api.h` marcada como rota.** Es la cabecera de una biblioteca cuya carpeta no está en el
   comprimido (se descarga al compilar). Ahora es un paquete externo no verificado, con ese motivo.
3. **`rarres.hpp` marcada como rota.** Está dentro de un `#ifdef` que no se compila en Android. Las inclusiones
   condicionales ahora son "no verificadas" y lo explican.
4. **57 archivos "sin referencias" que sí se usaban.** Los `.cpp` (los usa el sistema de compilación, no otro
   archivo) y archivos Kotlin con solo funciones de nivel superior (se usan sin importación desde el mismo paquete).
   Ahora en C/C++ solo cuentan las cabeceras, y el uso en el mismo paquete incluye funciones y constantes.
5. **La gramática de Kotlin falla con `var open by …`** (palabra reservada blanda) y las declaraciones posteriores
   al error se perdían. Ahora, si un archivo tiene errores de sintaxis, un respaldo léxico recupera las declaraciones.
6. **Importaciones de un miembro de `companion object`** (`Clase.Companion.funcion`) salían como no verificadas.
   Ahora se sube hasta la clase que lo contiene.
7. **Los scripts de Gradle `.kts` se enlazaban entre sí** por el uso en el mismo paquete. Cada script se compila
   aparte, así que ya no se enlazan.
8. **Honestidad sin tree-sitter.** Si la biblioteca nativa no carga (o un archivo agota su tiempo), no se pueden
   extraer sus importaciones, y 155 archivos salían como "sin referencias". Ahora, si faltan las importaciones de
   algún archivo de un ecosistema, no se señala ningún archivo de ese ecosistema como no usado, y la cobertura dice
   cuántos archivos y de qué lenguajes no se pudieron analizar (prueba nueva).
9. **Rendimiento.** Con el proyecto sintético de 3 000 archivos, la etapa tardaba 8 s, y 51 s al añadir el uso sin
   importación en el mismo paquete (se comparaba cada nombre con cada archivo). Ahora los nombres usados se guardan
   como huellas ordenadas, cada paquete tiene un índice por huella y los nombres declarados en más de 8 archivos se
   descartan por ambiguos.
10. Textos con número que debían ir en plural ("Lo usa 1 archivo" / "Lo usan 2 archivos").

## Cómo probarlo en tu teléfono

1. Instala **`app-arm64-v8a-debug.apk`** encima del anterior (tus análisis se conservan; los de fases anteriores no
   tienen relaciones hasta que los vuelvas a analizar).
2. Analiza un proyecto real en **Nuevo análisis**. Al terminar, el informe muestra **Relaciones entre archivos**.
3. Revisa **Posibles errores**: cada uno es una referencia a un archivo que no está en el comprimido. Tócalo para
   abrir el archivo que la contiene.
4. Mira **Archivos más importantes**: deberían ser los que medio proyecto usa (modelos, utilidades, configuración).
5. En el Explorador, abre un archivo y baja hasta **Relaciones**: verás su importancia, **Usa** y **Lo usan**.
6. Toca una conexión: la ficha muestra sus **cinco atributos**. Comprueba que la línea de origen es la de la
   importación y que, si eliminas el destino, el impacto dice qué símbolos dejarían de estar definidos y cuántos
   archivos se verían afectados.
7. Si un archivo sale en **Sin referencias encontradas**, revísalo: puede sobrar, o usarse a través del framework
   (eso llega en la 5B).

## Diferencias respecto a la arquitectura v0.2

- La extracción de símbolos se hace en el mismo recorrido de tree-sitter de la Fase 4 (no hay un segundo análisis).
- El uso sin importación en el mismo paquete se detecta con huellas de los nombres usados, no con el árbol completo
  de cada archivo (que no se conserva).
- El tipo "llamada" (función a función) no se calcula en la 5A: las conexiones son entre archivos, con los símbolos
  que se intercambian. Las de framework (ruta → controlador, HTTP, BD, configuración, inyección, IPC) son la 5B.

## Errores y limitaciones pendientes

1. **Relaciones de framework** (5B): rutas → controladores, llamadas HTTP entre frontend y backend, acceso a BD,
   lectura de configuración, inyección de dependencias, `AndroidManifest.xml` → clases, `R.layout.x` → layouts,
   GUID de Unity, escenas de Godot, IPC. Hasta entonces, un archivo que solo usa el framework puede salir "sin
   referencias" (el aviso lo dice) y las clases que un framework registra por su cuenta (actividades, controladores,
   `MonoBehaviour`…) no se señalan.
2. **Importaciones dinámicas, reflexión y rutas construidas en ejecución** no se resuelven (la cobertura lo dice).
3. **Lenguajes sin gramática** (Go, Rust, Ruby, Swift…): sin relaciones; la cobertura los cuenta.
4. **Uso en el mismo paquete**: es "probable" porque se basa en nombres; un nombre declarado en más de 8 archivos se
   descarta por ambiguo, y las funciones y constantes de menos de 4 letras no se buscan.
5. **C/C++**: no se interpretan las rutas de inclusión del sistema de compilación (CMake, Makefile); una cabecera
   con el mismo nombre en varias carpetas queda "no verificada".
6. **Límites**: 60 000 conexiones registradas (internas y rotas primero), 50 000 archivos de código, 200 ciclos, 300
   símbolos por archivo; al superarlos, la cobertura lo dice.
7. Pendientes de fases anteriores: biometría probada solo con simulación, APK de publicación sin firma propia,
   contraseñas en `String` dentro de Compose, detekt/ktlint sin configurar, sin pantalla de licencias.

## Siguiente sub-fase

**Fase 5B — Relaciones de framework**: analizadores por framework (Express, NestJS, Django, Flask, FastAPI,
Laravel, Spring, ASP.NET, Android, Flutter, Unity, Godot…) y enlace entre las llamadas HTTP del frontend y las rutas
del backend, con la misma ficha de cinco atributos.
