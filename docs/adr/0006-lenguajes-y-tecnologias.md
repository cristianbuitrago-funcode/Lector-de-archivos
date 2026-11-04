# ADR 0006 — Lenguajes, dependencias declaradas y motor de tecnologías

- **Estado:** aceptada (2026-11-04, Fase 4)

## Decisiones

1. **tree-sitter con un puente JNI propio, no KTreeSitter.** La arquitectura preveía los *bindings* oficiales de
   Kotlin. Se descartaron porque cruzan JNI en cada nodo (lento en archivos de miles de líneas) y no hay artefactos
   para todas las gramáticas necesarias. El puente propio (`native/treesitter/src/main/cpp/funcode_treesitter_jni.c`)
   hace **una sola llamada por archivo**: analiza el texto, recorre el árbol una vez y devuelve un único `int[]` con
   los comentarios, los fragmentos de resaltado, los nodos pedidos (importaciones) y el número de errores. No guarda
   ningún árbol entre llamadas. Se ejecuta sobre archivos hostiles dentro del proceso aislado, así que:
   - el recorrido es **iterativo** (los hilos de Android tienen pilas pequeñas, ver el incidente 9 de la Fase 3);
   - el análisis se **cancela** al superar su tiempo (`ts_parser_parse_with_options` con aviso de progreso) y el
     recorrido comprueba el reloj cada 16 384 nodos;
   - cada lista tiene un máximo de elementos y un fallo de memoria se informa en lugar de cerrar el proceso.
2. **15 gramáticas** (JavaScript, TypeScript, TSX, Python, Java, Kotlin, PHP, C#, Dart, SQL, GDScript, HTML, CSS, C y
   C++), con el núcleo 0.25.1 (ABI 14 y 15). Los demás lenguajes (Go, Rust, Ruby, Swift…) se reconocen y se cuentan
   con un analizador léxico; la cobertura lo dice.
3. **Fuentes descargadas de npm, verificadas y no guardadas en el repositorio.** Los `parser.c` generados ocupan más
   de 100 MB. Los mantenedores publican cada gramática en npm con su código generado; la tarea
   `fetchTreeSitterSources` descarga cada paquete, comprueba su **integridad SHA-512** (la misma que publica npm),
   extrae solo los `.c`, `.h` y las licencias con un lector tar propio que rechaza rutas peligrosas, y **no ejecuta
   nada** de lo descargado. Las licencias (MIT) se empaquetan en el APK (`assets/licencias/tree-sitter/`).
4. **Resaltado genérico.** Las categorías (palabra clave, cadena, comentario, número, constante, tipo, función) se
   deducen del tipo de cada nodo, su campo y su padre, sin las consultas `highlights.scm` de cada gramática. Es menos
   fino pero igual para los 15 lenguajes y no necesita el motor de consultas en el dispositivo.
5. **El análisis de lenguaje trabaja sobre el texto ya redactado.** La máscara de los secretos conserva la longitud,
   así que las posiciones del resaltado sirven para la copia guardada, y lo que se obtiene del texto (importaciones,
   nombres y versiones de dependencias, evidencias) **nunca contiene un secreto completo**. Consecuencia: un secreto
   enmascarado fuera de una cadena puede sumar un error de sintaxis.
6. **Recuento de líneas.** Una línea es de comentario si todo lo que contiene, salvo espacios, está dentro de un
   comentario del árbol; en blanco si no tiene nada; de código en cualquier otro caso. Sin gramática, o si el análisis
   sintáctico no termina, un analizador léxico con la sintaxis de comentarios y cadenas de cada lenguaje. Presupuesto:
   5 s por archivo y 180 s para todo el proyecto; los archivos generados (minificados, bloqueos) solo se cuentan
   léxicamente. Cada ficha dice qué método se usó.
7. **Dependencias declaradas en `engine:dependencies`.** En esta fase solo los manifiestos (npm, Composer, Gradle y
   sus catálogos de versiones, Maven, requirements/pyproject/Pipfile, pubspec, .NET, go.mod, Cargo, Gemfile, Podfile,
   CMake, Unity y Unreal), como evidencia para las tecnologías. Las dependencias transitivas (`// indirect` en Go) no
   se registran. Los archivos de bloqueo y las vulnerabilidades se añadirán al mismo módulo en la Fase 7.
8. **YAML por eventos.** Se usa solo el analizador por eventos de **snakeyaml-engine** (una máquina de estados sin
   recursión) y el árbol se construye aquí, de forma iterativa, con límites de profundidad y de nodos. Los alias
   nunca se expanden. Se usa la rama **2.x**: la 3.x necesita API de Java 11 que Android 8 no tiene.
   **TOML**: lector parcial por líneas (tablas, pares, tablas en línea, listas de varias líneas), suficiente para las
   tablas de dependencias; tomlj se descartó por su dependencia de ANTLR.
9. **Motor de reglas declarativo** (`engine/technologies/src/main/resources/.../catalog.yaml`, versión 1,
   208 tecnologías). Cada tecnología tiene señales con peso: dependencia declarada, archivo característico,
   importación, texto literal o patrón RE2 en el código. La puntuación es la suma de los pesos de las señales distintas
   (máximo 1): **confirmada** con 0,8 o más, **probable** con 0,5 o más, **posible** por debajo. Además:
   - las **menciones** (documentación, comentarios, o un texto del catálogo dentro de un comentario) nunca suman: la
     tecnología aparece como "mención no concluyente";
   - un texto encontrado solo en **archivos de prueba** suma la mitad (suele ser un dato de ejemplo);
   - `version: false` evita mostrar como versión de una tecnología la de un complemento de compilación;
   - el catálogo se valida al cargarlo (identificadores, pesos, umbrales, ecosistemas, patrones sin repeticiones
     acotadas grandes, que desbordarían la pila en Android) y las pruebas comprueban que cada tecnología puede llegar
     al menos a "probable".
10. **Esquema de resultados v4 y BD v4.** Registros nuevos: lenguaje de cada archivo (en la ficha), totales por
    lenguaje, dependencia declarada y tecnología (con hasta 8 evidencias, cada una con archivo y línea, y el total).
    El fragmento de la copia lleva su resaltado. El proceso principal valida que el resaltado esté dentro del texto,
    en orden y sin solaparse, y que toda evidencia de una tecnología tenga archivo. La BD añade el resaltado a la
    copia del código (migración automática desde la 3; los análisis anteriores quedan sin resaltado).
11. **APK por arquitectura y bibliotecas nativas comprimidas.** Las tablas de las gramáticas ocupan unos 23 MB por
    arquitectura. Se guardan comprimidas en el APK y se genera un APK por arquitectura (y uno universal).

## Consecuencias

- El conteo de líneas, el resaltado y las importaciones son exactos para los 15 lenguajes con gramática; para el
  resto, aproximados (léxicos).
- Las tecnologías solo se reconocen si están en el catálogo. Un texto característico en el código (por ejemplo, el
  patrón de un detector de webhooks de Slack) cuenta como uso "probable": la evidencia permite comprobarlo.
- La compilación necesita acceso a `registry.npmjs.org` la primera vez (en el CI y en el PC).
