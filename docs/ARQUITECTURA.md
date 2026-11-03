# Funcode Code Analyzer — Arquitectura técnica

**Funcode Studio CB** · Documento de arquitectura **v0.2** · Fecha: 2026-11-03
**Estado: aprobada** (decisión N1 = A, 2026-11-03). Implementación en curso: Fase 1 terminada (ver `docs/fases/`).

---

## Registro de cambios

| Versión | Cambio |
|---------|--------|
| v0.1 | Propuesta inicial: aplicación web local con Docker. |
| **v0.2** | Decisiones del propietario: **la aplicación es un APK de Android**, herramienta **de uso interno** de Funcode Studio CB, interfaz **en español**, **acceso exclusivo del propietario**; resto de recomendaciones aprobadas. Se rediseña la plataforma: el análisis se ejecuta **en el propio dispositivo**, dentro de un proceso Android aislado. Se mantienen sin cambios el modelo de evidencia (§6), las estrategias por tipo de proyecto (§7), las categorías de seguridad (§8) y las secciones del informe (§10). |

### Qué cambia al pasar a Android

| Aspecto | v0.1 (web + Docker) | v0.2 (APK) |
|---------|---------------------|------------|
| Dónde se ejecuta | Contenedores en un PC | Todo dentro del teléfono o tablet |
| Aislamiento del análisis | Contenedor sin red ni privilegios | Proceso Android **aislado** (`isolatedProcess`): UID aleatorio, sin permisos, sin red y sin acceso a los archivos de la app |
| Extracción | A tmpfs `noexec` | **No se extrae nada a disco**: cada entrada se lee en memoria directamente del comprimido |
| Lenguaje del motor | Python | Kotlin, más C/C++ nativo para tree-sitter y UnRAR |
| SAST | Semgrep + reglas propias | **Motor de reglas propio** (Semgrep no puede ejecutarse en Android) |
| Vulnerabilidades de dependencias | OSV + OWASP Dependency-Check | **Base OSV offline** en el dispositivo (Dependency-Check no puede ejecutarse en Android) |
| Acceso | Contraseña opcional | **Obligatorio y exclusivo**: biometría + contraseña propia; base de datos cifrada con clave del Android Keystore |
| PDF | WeasyPrint | Generador nativo de Android (`PdfDocument`) |

---

## Índice

1. [Análisis de requisitos](#1-análisis-de-requisitos)
2. [Arquitectura](#2-arquitectura)
3. [Tecnologías seleccionadas](#3-tecnologías-seleccionadas)
4. [Módulos](#4-módulos)
5. [Comunicación entre módulos](#5-comunicación-entre-módulos)
6. [Modelo de evidencia y niveles de confianza](#6-modelo-de-evidencia-y-niveles-de-confianza)
7. [Estrategia de análisis por tipo de proyecto](#7-estrategia-de-análisis-por-tipo-de-proyecto)
8. [Motor de auditoría de seguridad](#8-motor-de-auditoría-de-seguridad)
9. [Seguridad de la propia aplicación](#9-seguridad-de-la-propia-aplicación)
10. [Informes y métricas](#10-informes-y-métricas)
11. [Limitaciones técnicas](#11-limitaciones-técnicas)
12. [Estrategia de pruebas](#12-estrategia-de-pruebas)
13. [Plan de implementación por fases](#13-plan-de-implementación-por-fases)
14. [Estructura del repositorio](#14-estructura-del-repositorio)
15. [Decisiones](#15-decisiones)

---

## 1. Análisis de requisitos

### 1.1 Requisitos funcionales

| ID | Área | Resumen |
|----|------|---------|
| RF-01 | Ingesta | Carga de un único `.zip` o `.rar` (selector del sistema, "Abrir con…/Compartir" desde otra app, arrastrar y soltar en pantallas grandes). Nombre, tamaño, formato, progreso. Detección de archivos corruptos y protegidos con contraseña. Duplicados. Exclusiones configurables que se registran aunque no se analicen. |
| RF-02 | Estructura | Árbol interactivo. Clasificación de cada archivo por rol (configuración, entrada, dependencias, BD, autenticación, servidor, UI, multimedia, posiblemente innecesario). Ficha por archivo: nombre, extensión, tamaño, ubicación, función, dependencias y archivos relacionados. |
| RF-03 | Tecnologías | Lenguajes, frameworks, motores y bases de datos con evidencia (dónde) y función (para qué). Una referencia indirecta **no** basta para afirmar que una tecnología está presente. |
| RF-04 | Relaciones | Importaciones, clases, funciones, módulos, componentes, rutas, controladores, modelos, servicios, accesos a BD, llamadas a APIs y configuración. Mapa de arquitectura. Para cada conexión: origen, destino, tipo, información intercambiada e impacto de modificarla o eliminarla. Lo no demostrable se marca como posible o no verificado. |
| RF-05 | Seguridad | Análisis estático de 18 categorías. Ficha completa por hallazgo. Distinción entre confirmado, probable, posible y "requiere prueba dinámica". Nunca declarar que un proyecto es seguro. |
| RF-06 | Dependencias | Directas, indirectas (si hay lockfile), versiones, vulnerables, fuera de soporte, aparentemente innecesarias y críticas. |
| RF-07 | Diagramas | Arquitectura por capas, dependencias y flujo de información. |
| RF-08 | Informes | 15 secciones; exportación PDF, HTML y JSON; historial; comparación entre análisis del mismo proyecto. |
| RF-09 | Interfaz | Dashboard, Nuevo análisis, Explorador, Arquitectura, Seguridad e Informes, con la identidad visual de Funcode Studio CB, en español. |
| RF-10 | Acceso | **Solo el propietario puede entrar en la aplicación y ver sus datos.** |

### 1.2 Requisitos no funcionales

| ID | Requisito |
|----|-----------|
| RNF-01 | **El proyecto analizado se trata como hostil.** Cualquier archivo puede estar diseñado para atacar al analizador. |
| RNF-02 | **Nunca se ejecuta ni se carga código del proyecto**: ni compilaciones, ni scripts, ni `setup.py`, ni Gradle, ni carga dinámica de clases. Todo se lee como texto o se analiza sintácticamente. |
| RNF-03 | **Todo ocurre en el dispositivo.** El código nunca sale del teléfono. La única conexión es la descarga de bases públicas (vulnerabilidades y fechas de fin de soporte), que no envía nada del proyecto. |
| RNF-04 | **Honestidad verificable**: cada afirmación lleva evidencia (archivo, línea, fragmento) y nivel de confianza. Lo que no se pudo analizar aparece en una sección obligatoria de *cobertura*. |
| RNF-05 | **Extensibilidad**: nuevos lenguajes, frameworks y reglas se añaden como módulos o reglas declarativas, sin modificar el núcleo. |
| RNF-06 | **Robustez**: un fallo en un analizador o en un archivo no aborta el análisis; incluso si el proceso aislado se cae, se conservan los resultados parciales y se informa del motivo. |
| RNF-07 | **Rendimiento** (objetivo a validar en un dispositivo real en la Fase 10): proyecto de ~5 000 archivos fuente en menos de 5 minutos en un teléfono de gama media reciente. |
| RNF-08 | **Reproducibilidad**: cada informe registra la versión de la app, de las reglas y la fecha de las bases de vulnerabilidades. |
| RNF-09 | **Dispositivos**: Android 8.0 (API 26) o superior; teléfonos y tablets (diseño adaptable). Recomendado: 6 GB de RAM o más. |

### 1.3 Principios de diseño

1. **Evidencia antes que afirmación.**
2. **Defensa en profundidad.**
3. **Todo local.** La red solo trae datos públicos; nunca envía nada del proyecto.
4. **El proceso principal nunca analiza contenido del proyecto**: solo muestra texto y datos que ya validó. Todo el trabajo con contenido hostil ocurre en el proceso aislado.
5. **Núcleo pequeño + módulos.** El motor no depende de Android y se prueba en un PC.
6. **Degradación honesta.**

---

## 2. Arquitectura

### 2.1 Vista general

```
 ┌────────────────────────────── Teléfono / tablet Android ─────────────────────────────┐
 │                                                                                      │
 │  PROCESO PRINCIPAL (UID de la app)                                                   │
 │  ┌────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ Bloqueo de acceso: biometría + contraseña propia                               │  │
 │  │ Interfaz Jetpack Compose  ─  ViewModels  ─  Repositorios                       │  │
 │  │ BD cifrada (Room + SQLCipher, clave protegida por Android Keystore)            │  │
 │  │ Coordinador de análisis (servicio en primer plano con notificación)            │  │
 │  │ Generador de informes (HTML · PDF · JSON)                                      │  │
 │  │ Actualizador de bases públicas (OSV, fin de soporte): único componente con red │  │
 │  └──────────────┬───────────────────────────────────────────────▲─────────────────┘  │
 │                 │ Binder (AIDL): descriptor del comprimido      │ flujo de resultados│
 │                 │ (solo lectura) + configuración + tubería      │ (validado como     │
 │                 ▼                                               │ NO confiable)      │
 │  ┌──────────────────────────────────────────────────────────────┴─────────────────┐  │
 │  │ PROCESO DE ANÁLISIS AISLADO  (android:isolatedProcess="true")                  │  │
 │  │ UID aleatorio · sin permisos · sin red · sin acceso a los archivos de la app   │  │
 │  │ Motor Funcode: Kotlin + tree-sitter (nativo) + UnRAR (nativo)                  │  │
 │  │ Lee el ZIP/RAR entrada a entrada EN MEMORIA: nada se extrae a disco            │  │
 │  └────────────────────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Componentes

| Componente | Proceso | Responsabilidad | Red | Acceso a datos |
|------------|---------|-----------------|-----|----------------|
| **Bloqueo de acceso** | Principal | Biometría fuerte y contraseña propia; desbloquea la clave de la BD | No | Clave de la BD |
| **Interfaz** | Principal | Las 6 pantallas. Muestra el contenido del proyecto solo como texto. | No | Lo que entregan los repositorios |
| **BD cifrada** | Principal | Historial, resultados, copia redactada del código | No | Todo, cifrado |
| **Coordinador** | Principal (servicio en primer plano) | Importa el archivo, lanza el análisis aislado, vigila tiempos, recibe y valida resultados | No | Copia temporal del comprimido |
| **Motor de análisis** | **Aislado** | Pipeline completo de análisis | **Imposible** (el sistema se la niega) | Solo los descriptores que recibe |
| **Informes** | Principal | HTML, PDF y JSON a partir de resultados ya validados | No | Resultados |
| **Actualizador** | Principal | Descarga datos públicos de OSV y de fechas de fin de soporte | Sí (solo descarga) | Ninguno del proyecto |

### 2.3 Flujo de un análisis

1. **Selección.** El usuario elige el archivo con el selector del sistema, lo abre desde otra app ("Abrir con…/Compartir") o lo arrastra (tablets, multiventana, Samsung DeX).
2. **Importación.** El coordinador copia el archivo a la caché privada de la app **cifrándolo con una clave efímera que solo existe en memoria**, contando bytes (corta al superar el límite), comprueba la firma real del archivo (ZIP `PK\x03\x04`/`PK\x05\x06`; RAR4 `Rar!\x1A\x07\x00`; RAR5 `Rar!\x1A\x07\x01\x00`) sin fiarse de la extensión y calcula su SHA-256.
3. **Lanzamiento.** Se inicia un servicio en primer plano (con notificación de progreso, para que Android no lo detenga) que enlaza el servicio aislado y le entrega: descriptor de solo lectura de la copia, clave efímera, configuración y el extremo de escritura de una tubería para los resultados.
4. **Pipeline** dentro del proceso aislado (cada etapa informa progreso y registra cobertura):

   | # | Etapa | Resultado |
   |---|-------|-----------|
   | E1 | Inspección del comprimido | listado, cifrado, corrupción, bombas, nombres peligrosos |
   | E2 | Lectura selectiva en memoria | lo excluido se registra pero no se lee |
   | E3 | Inventario | hashes, duplicados, binario/texto, codificación, tamaños |
   | E4 | Lenguajes | lenguaje por archivo, líneas de código/comentario/blancas |
   | E5 | Tecnologías | frameworks, motores, BD y servicios con evidencia |
   | E6 | Análisis sintáctico | símbolos, imports, rutas, llamadas HTTP, accesos a BD, claves de configuración |
   | E7 | Relaciones | resolución de imports, semántica de frameworks, enlaces entre capas, grafo, impacto |
   | E8 | Seguridad | reglas, secretos, auditores de configuración, clasificación |
   | E9 | Dependencias | manifiestos, lockfiles, vulnerabilidades (OSV), fin de soporte, uso real |
   | E10 | Métricas | complejidad, duplicación, índice de mantenimiento |
   | E11 | Diagramas | vistas agregadas y su disposición (layout) |
   | E12 | Contenido para el visor | texto **ya redactado** + resaltado de sintaxis precalculado |

5. **Resultados.** El motor envía registros por la tubería a medida que los produce; el progreso llega por un *callback* Binder. El proceso principal **valida cada registro** (esquema, tamaños, rutas) y lo guarda cifrado.
6. **Limpieza.** Se borra la copia temporal y se destruye la clave efímera (borrado criptográfico, §9.2). El sistema destruye el proceso aislado al desenlazarlo.
7. **Informes.** Se generan bajo demanda en el proceso principal a partir del resultado almacenado.

---

## 3. Tecnologías seleccionadas

### 3.1 Aplicación Android

| Necesidad | Tecnología | Motivo |
|-----------|-----------|--------|
| Lenguaje | **Kotlin** | Estándar de Android, seguro frente a nulos, corrutinas |
| Interfaz | **Jetpack Compose + Material 3** con tema oscuro Funcode | UI declarativa moderna, animaciones discretas, accesibilidad |
| Diseño adaptable | Barra inferior en teléfono; *navigation rail* y paneles dobles en tablet | Explorador y Arquitectura aprovechan pantallas grandes |
| Arquitectura | MVVM + capas `ui` / `domain` / `data`, inyección de dependencias manual (`AppContainer`, ver ADR 0003), Corrutinas + Flow | Separación clara, testeable |
| Base de datos | **Room + SQLCipher** | Cifrado completo de la BD |
| Claves | **Android Keystore** (StrongBox si el dispositivo lo tiene), clave ligada a la autenticación del usuario | La clave no puede extraerse del dispositivo |
| Autenticación | **AndroidX Biometric** (clase fuerte) + contraseña propia con **Argon2id** (Bouncy Castle) | Solo el propietario |
| Cifrado de la copia temporal | **Google Tink** (`StreamingAead`, con acceso aleatorio) | Permite leer el ZIP cifrado sin descifrarlo entero, y el borrado criptográfico |
| Gráficas | **Vico** (gráficas para Compose) | Nativo, ligero |
| Diagramas | **Compose Canvas** + algoritmo de disposición propio del motor | Zoom, desplazamiento y foco; el mismo dibujo se reutiliza en el PDF |
| Árbol de archivos | `LazyColumn` virtualizada | Miles de nodos sin bloquear la UI |
| Visor de código | `LazyColumn` + `AnnotatedString` con resaltado **precalculado en el proceso aislado** | El proceso principal no analiza código hostil |
| Comunicación con el motor | **AIDL** + `ParcelFileDescriptor` (tuberías) | Mecanismo oficial entre procesos |
| Trabajo en segundo plano | Servicio en primer plano de tipo `dataSync` (incluye "procesamiento de archivos locales") | Android no detiene el análisis si se sale de la app |

### 3.2 Motor de análisis (módulos Kotlin/JVM sin dependencias de Android)

| Necesidad | Tecnología | Motivo / alternativa descartada |
|-----------|-----------|---------------------------------|
| Análisis sintáctico | **tree-sitter** con los *bindings* oficiales de Kotlin (**KTreeSitter**); gramáticas compiladas desde su código C con el **NDK** | Un motor para todos los lenguajes, tolerante a errores. Descartado ANTLR: lento y poco tolerante a errores |
| ZIP | **Apache Commons Compress** (acceso aleatorio) + validación propia | Control total sobre cada entrada |
| RAR | **Biblioteca UnRAR oficial de RARLAB** (C++) compilada con el NDK | Soporta RAR4 y RAR5 y detecta cifrado. Descartada `junrar` (Java), que **no soporta RAR5**, el formato por defecto de las versiones actuales de WinRAR |
| Regex sobre contenido no confiable | **RE2/J** | Tiempo lineal: inmune a ReDoS |
| XML | `XmlPullParser` con DTD desactivado | Sin XXE ni *billion laughs* |
| YAML / TOML / JSON | **snakeyaml-engine** con límites de alias; **tomlj**; lector JSON por flujo con límite de profundidad | Parsers seguros frente a bombas |
| Grafos | Algoritmos propios: Tarjan (ciclos), PageRank (importancia), alcanzabilidad (impacto), disposición por capas tipo Sugiyama | Pequeños, auditables y comunes a pantalla, PDF y SVG |
| Complejidad | Cálculo propio sobre el árbol de tree-sitter | lizard (Python) no existe en Android |
| SAST | **Motor de reglas propio de Funcode**: consultas tree-sitter + RE2/J + chequeos de configuración, con análisis de flujo intraprocedural | Semgrep no puede ejecutarse en Android. Al ser una herramienta interna (D2), las reglas comunitarias de Semgrep pueden servir de referencia técnica, porque su licencia permite el uso interno |
| Vulnerabilidades | **Base de datos OSV** (volcados públicos oficiales por ecosistema) indexada en SQLite en el dispositivo | Cubre npm, PyPI, Maven, Packagist, NuGet, Pub, Go, crates.io y RubyGems. OWASP Dependency-Check no puede ejecutarse en Android |
| Fin de soporte | Datos públicos de **endoflife.date** (Android SDK, Node.js, PHP, Python, Django, Laravel, Spring Boot, Angular, .NET…) | Permite detectar versiones obsoletas sin consultar nada del proyecto en línea |
| Serialización | **kotlinx.serialization** | Esquema de resultados versionado |
| HTML del informe | **kotlinx.html** | Escapa el texto por defecto: el contenido del proyecto nunca se convierte en HTML |
| PDF | **`android.graphics.pdf.PdfDocument`** + `StaticLayout` | Nativo, sin bibliotecas externas ni WebView; reutiliza el dibujo de los diagramas |

### 3.3 Construcción y calidad

**Gradle (Kotlin DSL)** con catálogo de versiones y **verificación de dependencias por checksum**; **Android Gradle Plugin**; **NDK + CMake** para tree-sitter y UnRAR; **detekt**, **ktlint**, **Android Lint**; **JUnit 5 + Kotest** (motor), **Robolectric + pruebas de Compose** (UI en la JVM), pruebas instrumentadas en emulador; **GitHub Actions** compila el APK en cada fase.

### 3.4 Identidad visual (tokens iniciales, ajustables)

| Token | Valor | Uso |
|-------|-------|-----|
| `bg` | `#05070D` | Fondo principal (negro) |
| `navy` | `#0A1A33` | Paneles (azul marino) |
| `navy2` | `#10284D` | Paneles elevados, bordes |
| `electric` | `#1E90FF` | Acciones, enlaces, foco, nodos del grafo |
| `gold` | `#D4AF37` | Acentos de marca, títulos destacados, puntuaciones |
| Severidades | crítico `#FF3B5C`, alto `#FF8A3D`, medio `#F5C542`, bajo `#4FC3F7`, info `#8A9BB5` | Con contraste AA sobre fondo oscuro |

Si Funcode Studio CB tiene logotipo, tipografía o colores oficiales, se sustituyen en la Fase 1.

---

## 4. Módulos

Cada carpeta es un **módulo Gradle independiente**. El motor (`engine/*`) no depende de Android: se compila y prueba
en un PC, y en el teléfono se ejecuta dentro del proceso aislado.

| Módulo | Responsabilidad | Fase |
|--------|-----------------|------|
| `app` | Interfaz Compose, navegación, bloqueo de acceso, coordinador, informes PDF, actualizador | 1 → 10 |
| `analyzer-service` | Servicio aislado que aloja el motor; interfaz AIDL | 1 |
| `data` | Room + SQLCipher, repositorios, Keystore, validación de resultados | 1 |
| `engine:api` | Modelos de dominio, interfaces de extensión, esquema de resultados | 1 |
| `engine:core` | Orquestador del pipeline, contexto, cobertura, presupuestos de memoria/tiempo | 1 |
| `engine:ingest` | ZIP/RAR, validación, lectura en memoria, exclusiones, duplicados | 2 |
| `engine:inventory` | Árbol, roles de archivo, puntos de entrada, archivos posiblemente innecesarios | 3 |
| `engine:languages` | Detección de lenguaje, conteo de líneas, carga de gramáticas | 4 |
| `engine:technologies` | Motor de reglas declarativas (YAML) con evidencia | 4 |
| `engine:relations` | Extractores por lenguaje, resolutores por ecosistema, analizadores de framework, grafo, impacto | 5 |
| `engine:security` | Reglas, secretos, redacción, auditores de configuración | 6 |
| `engine:dependencies` | Parsers de manifiestos/lockfiles, índice OSV, fin de soporte, uso real, criticidad | 7 |
| `engine:layout` | Disposición de grafos (pantalla, PDF, SVG) | 8 |
| `engine:metrics` | Complejidad, duplicación, índice de mantenimiento | 9 |
| `engine:report` | JSON, HTML, comparación entre análisis | 9 |
| `native:treesitter` | Gramáticas tree-sitter compiladas + JNI | 5 |
| `native:unrar` | UnRAR + JNI | 2 |
| `engine:cli` | Ejecutable de consola **solo para pruebas y CI**: ejecuta el motor en un PC sobre un ZIP | 2 |

### 4.1 Puntos de extensión

Boceto de diseño (no es código definitivo):

```kotlin
interface ArchiveReader {                 // zip, rar; futuro: 7z, tar.gz
    fun probe(head: ByteArray): Boolean
    fun inspect(source: SeekableSource): ArchiveListing          // sin leer contenidos
    fun read(source: SeekableSource, entry: Entry, budget: Budget): ByteArray  // en memoria, con límite
}
interface LanguageExtractor {             // uno por lenguaje, basado en consultas tree-sitter
    fun extract(file: SourceFile, tree: Tree): Extraction        // símbolos, imports, rutas, HTTP, BD, config
}
interface ImportResolver {                // uno por ecosistema (JS/TS, Python, JVM, PHP, Dart, .NET, C/C++)
    fun resolve(ref: ImportRef, index: ProjectIndex): Resolution // destino + confianza + motivo
}
interface FrameworkAnalyzer {             // Express, Django, Laravel, Spring, Android, Unity, Godot...
    fun applies(techs: TechnologyReport): Boolean
    fun analyze(ctx: AnalysisContext): FrameworkFacts
}
interface SecurityRule { /* consulta tree-sitter, RE2 o chequeo de configuración */ }
interface DependencyParser { /* package.json, pom.xml, pubspec.yaml... */ }
interface ReportRenderer { /* json, html, pdf; futuro: sarif, cyclonedx */ }
```

Las tecnologías y muchas reglas se definen en YAML, empaquetadas en el APK:

```yaml
id: laravel
name: Laravel
category: framework
language: php
role: "Framework web MVC del backend (rutas, controladores, ORM Eloquent)"
signals:
  - { kind: manifest_dependency, ecosystem: composer, package: laravel/framework, weight: 0.6 }
  - { kind: file_exists, path: artisan, weight: 0.25 }
  - { kind: code_reference, pattern: 'Illuminate\\', weight: 0.25 }
thresholds: { confirmed: 0.8, probable: 0.5 }
# Las menciones en documentación, comentarios o dependencias transitivas NUNCA suman:
# se listan aparte como "menciones no concluyentes".
```

### 4.2 Pantallas

| Pantalla | Contenido |
|----------|-----------|
| **Acceso** | Biometría o contraseña; configuración inicial del propietario la primera vez |
| **Dashboard** | Proyectos analizados, últimos análisis, vulnerabilidades por severidad, tecnologías más utilizadas, tendencia |
| **Nuevo análisis** | Selección del archivo, ficha (nombre, tamaño, formato), configuración (exclusiones, módulos, límites), progreso por etapas en tiempo real |
| **Explorador** | Árbol con filtros por rol, visor de código con hallazgos marcados, ficha del archivo y sus relaciones |
| **Arquitectura** | Diagrama por capas, grafo de archivos/módulos con foco y filtros, flujos de información, dependencias |
| **Seguridad** | Hallazgos por severidad y certeza, filtros, ficha detallada, recomendaciones, pruebas dinámicas recomendadas |
| **Informes** | Historial, exportación PDF/HTML/JSON, comparación entre análisis del mismo proyecto |
| **Ajustes** | Exclusiones por defecto, límites, actualización de bases, almacenamiento de código, bloqueo, borrado de datos |

---

## 5. Comunicación entre módulos

### 5.1 Interfaz ↔ datos

Compose → ViewModel → Repositorio → Room/SQLCipher, con `StateFlow` y corrutinas. El progreso de un análisis en
curso llega a la UI como un `Flow` del coordinador.

### 5.2 Proceso principal ↔ proceso aislado (AIDL)

Boceto:

```
interface IAnalyzer {
    void start(in ParcelFileDescriptor archive,     // copia cifrada, solo lectura
               in byte[] ephemeralKey,              // clave efímera de la copia
               in byte[] config,                    // configuración serializada
               in ParcelFileDescriptor resultsPipe, // extremo de escritura de la tubería
               IAnalysisCallback callback);         // progreso y finalización
    void cancel();
}
```

Reglas del contrato:

- Los resultados viajan por la tubería como **registros con prefijo de longitud** (el canal Binder tiene un límite de ~1 MB por transacción).
- El proceso principal **valida cada registro** (esquema, tamaños, rutas, número de registros) antes de guardarlo: si un parser fuese explotado, el proceso aislado estaría comprometido.
- Los registros se guardan a medida que llegan: si el proceso aislado se cae (memoria, tiempo, fallo), se conservan los resultados parciales y el análisis queda marcado como **incompleto**, con el motivo.
- El proceso aislado nunca recibe la clave de la BD ni puede abrir archivos por su cuenta.

### 5.3 Dentro del motor

Las etapas comparten un `AnalysisContext` (inventario inmutable + resultados acumulados) y declaran sus
dependencias. El trabajo por archivo se paraleliza con corrutinas en un número de hilos acotado y con tiempo
máximo por archivo. Cada etapa registra en la cobertura qué analizó, qué omitió y por qué.

### 5.4 Actualizador

Descarga los volcados públicos de OSV de los ecosistemas que elijas y los datos de fin de soporte, los valida, los
indexa en una BD local de solo lectura y la pasa al proceso aislado como descriptor de solo lectura en cada análisis.
Se ejecuta a petición o periódicamente (WorkManager, solo con Wi-Fi si así se configura).

---

## 6. Modelo de evidencia y niveles de confianza

*(Sin cambios respecto a v0.1.)* Este modelo es el mecanismo que impide que la herramienta "invente". **Toda
afirmación** del análisis es un registro con evidencia:

```
Afirmación
 ├─ tipo (tecnología, relación, hallazgo, rol de archivo…)
 ├─ confianza (según la escala de su tipo)
 └─ evidencia[]: archivo · rango de líneas · fragmento (redactado) · analizador que la produjo · motivo
```

### 6.1 Escalas

| Dominio | Niveles | Criterio |
|---------|---------|----------|
| **Tecnologías** | Confirmada · Probable · *Mención no concluyente* | Confirmada: marcador definitivo (p. ej. `project.godot`) o manifiesto + uso en código. Probable: una sola señal fuerte. Las menciones en documentación, comentarios o dependencias transitivas se listan aparte y **no** se presentan como tecnología del proyecto. |
| **Relaciones** | Verificada · Probable · No verificada · Rota | Verificada: resolución sintáctica a un archivo existente (import resuelto, GUID de Unity, `R.layout.x` existente). Probable: heurística con evidencia fuerte (ruta HTTP literal idéntica en frontend y backend; inyección por tipo). No verificada: coincidencia parcial, URL construida dinámicamente, nombre similar. Rota: el destino no existe en el proyecto (se informa como posible error). |
| **Seguridad – certeza** | Confirmado · Probable · Posible · Requiere prueba dinámica | Confirmado: la condición es observable en código o configuración. Probable: patrón peligroso + flujo de datos demostrado desde una entrada del usuario. Posible: patrón peligroso sin flujo demostrable. Requiere prueba dinámica: no decidible sin ejecutar la aplicación. |
| **Seguridad – severidad** | Crítico · Alto · Medio · Bajo · Informativo | Independiente de la certeza: un hallazgo puede ser *crítico* y solo *posible*. |

"Confirmado" significa *la condición existe de forma observable*, no "explotabilidad demostrada".

### 6.2 Atributos de cada conexión

| Atributo | Cómo se obtiene |
|----------|-----------------|
| Origen y destino | Archivo + símbolo, con línea |
| Tipo | `import`, `herencia`, `implementación`, `llamada`, `ruta→handler`, `http`, `acceso_bd`, `lectura_config`, `recurso` (layout, asset, escena), `ipc`, `inyección` |
| Información intercambiada | Datos extraídos: símbolos importados; método + ruta + campos del cuerpo; tablas/colecciones y operaciones; claves de configuración leídas; canales IPC. Si no es determinable: "No determinable estáticamente". |
| Impacto de modificar/eliminar | Calculado sobre el grafo: símbolos que quedarían sin definir, dependientes directos y transitivos (radio de impacto), si está en un camino desde un punto de entrada, y efecto específico del tipo (p. ej. "cambiar esta ruta rompe las llamadas desde `api.ts:42` y `UserService.kt:17`"). |
| Confianza y evidencia | Según §6.1 |

### 6.3 Cobertura

Sección obligatoria en la app y en el informe: archivos analizados por cada analizador, archivos omitidos
(binario, demasiado grande, minificado, excluido, error de sintaxis, lenguaje sin soporte) con el motivo, y
módulos que fallaron o no estaban disponibles (p. ej. base OSV no descargada).

---

## 7. Estrategia de análisis por tipo de proyecto

*(Sin cambios respecto a v0.1.)* Un mismo comprimido puede contener varios subproyectos (monorepo, app + backend,
Flutter con `android/`): cada manifiesto define una raíz de subproyecto, cada uno se analiza con su estrategia y
después se enlazan entre sí.

| Tipo | Detección | Qué se extrae | Relaciones específicas | Seguridad específica | Límites |
|------|-----------|---------------|------------------------|----------------------|---------|
| **Android** (Java/Kotlin) | `AndroidManifest.xml`, plugin `com.android.application`, `libs.versions.toml` | Activities, Services, Receivers, Providers, intent-filters (launcher = punto de entrada), permisos, layouts, `@Composable`, navegación, ViewModels, Room, Retrofit, Firebase | Layout XML ↔ Activity/Fragment (`setContentView(R.layout.x)`, ViewBinding); Manifest ↔ clases; interfaz Retrofit → endpoints; DAO → Entity → tabla | `debuggable`, `allowBackup`, `usesCleartextTraffic`, network security config, componentes exportados sin permiso, permisos peligrosos sin uso aparente, WebView con JS y `addJavascriptInterface`, TrustManager permisivo, secretos en `strings.xml`/Gradle | APK/AAB/DEX/.so no se decompilan; `R` y `BuildConfig` son generados (se infieren) |
| **Web frontend** (React, Angular, Vue) | `package.json`, `angular.json`, configuración de Vite/Webpack | Componentes, rutas del router, stores, servicios HTTP (fetch, axios, HttpClient), variables de entorno | Componente → hijo; ruta → componente; servicio → endpoint del backend | Sinks XSS (`dangerouslySetInnerHTML`, `v-html`, `[innerHTML]`, `bypassSecurityTrust*`), secretos en variables públicas (`VITE_*`, `NEXT_PUBLIC_*`, `REACT_APP_*`), tokens en `localStorage` | Código minificado: análisis superficial |
| **Páginas web estáticas** | `*.html` sin framework | Enlaces, scripts, estilos, formularios, recursos | HTML → CSS/JS/imágenes; formulario → `action` | Scripts externos sin SRI, contenido mixto `http://`, formularios sin protección, manejadores en línea | — |
| **Backend / APIs** (Express, NestJS, Django, Flask, FastAPI, Laravel, Spring Boot, ASP.NET Core) | Manifiestos + marcadores (`manage.py`, `artisan`, `@SpringBootApplication`…) | Rutas, middleware, controladores, servicios, modelos ORM, migraciones, esquemas SQL | Ruta → handler → servicio → repositorio/modelo → tabla | SQLi, inyección de comandos, path traversal, CORS, CSRF, rutas sin autenticación, JWT inseguro, cookies sin flags, modo debug, asignación masiva | Inyección de dependencias y reflexión → relaciones *probables* |
| **Unity** | `ProjectSettings/ProjectVersion.txt`, `Assets/`, `*.meta` | Scripts C#, escenas, prefabs, escenas de build | Escena/prefab → script por GUID (`m_Script: {guid}` ↔ `.cs.meta`) | Claves de servicios, `PlayerPrefs` con datos sensibles, HTTP sin TLS | Assets serializados en binario no son legibles; `Library/` se excluye |
| **Unreal Engine** | `*.uproject`, `Source/*.Build.cs`, `Config/*.ini` | Módulos, `UCLASS`/`UFUNCTION`, plugins | Módulo → módulo, includes C++ | Configuración y secretos en `.ini` | **Blueprints (`.uasset`) son binarios: no se analizan** |
| **Godot** | `project.godot` | Escenas `.tscn`/`.tres`, scripts `.gd`/`.cs`, autoloads, escena principal | Escena → script (`ext_resource res://`) | Secretos, HTTP sin TLS | Gramática GDScript a verificar en Fase 4 |
| **Flutter / Dart** | `pubspec.yaml` con `sdk: flutter` | Widgets, rutas, servicios (http, dio), Firebase, providers/bloc | `package:<nombre>/` → `lib/`; subproyectos `android/` e `ios/` | Secretos, HTTP sin TLS, almacenamiento inseguro | — |
| **Escritorio** (Electron, WPF/WinForms, JavaFX/Swing, Qt, Python) | `electron` en dependencias, `.csproj`, FXML, `.ui`, `.pro` | Procesos main/preload/renderer, ventanas, vistas | Canales IPC; XAML ↔ code-behind; FXML ↔ `fx:controller` | `nodeIntegration`, `contextIsolation: false`, `webSecurity: false`, `shell.openExternal` sin validar | Binarios no se analizan |

**Niveles de soporte por lenguaje** (visibles en la app para no prometer de más):

| Nivel | Lenguajes | Profundidad |
|-------|-----------|-------------|
| 1 | JavaScript, TypeScript, Python, Java, Kotlin, PHP | Símbolos, imports resueltos, rutas, flujo de datos intraprocedural |
| 2 | C#, Dart, HTML, CSS, SQL, GDScript | Símbolos, imports/referencias, patrones de seguridad |
| 3 | C, C++ | Includes y símbolos principales; las macros limitan la precisión |
| — | Otros | Inventario, conteo de líneas y búsqueda de secretos |

---

## 8. Motor de auditoría de seguridad

### 8.1 Fuentes de hallazgos

1. **Reglas propias de Funcode**: consultas tree-sitter con análisis de flujo intraprocedural, patrones RE2/J y chequeos de configuración estructurada (Manifest, `settings.py`, `.env`, `application.properties`, reglas de Firebase…).
2. **Secretos**: patrones por proveedor + entropía + contexto.
3. **OSV** (offline) para dependencias, y datos de fin de soporte.

Todos los hallazgos se normalizan a un único formato y se deduplican (archivo + línea + CWE).

### 8.2 Categorías y certeza máxima alcanzable sin ejecutar

| Categoría | Técnica | Certeza máxima |
|-----------|---------|----------------|
| SQL Injection | Sinks de consulta + flujo desde entradas del usuario | Probable |
| XSS | Sinks DOM/plantillas sin escape | Probable |
| CSRF | Desactivación/exenciones explícitas; sesiones por cookie sin protección | Confirmado (si está desactivado) / Requiere prueba dinámica |
| Path Traversal | APIs de archivos con entrada del usuario | Probable |
| Ejecución de comandos | `exec`, `system`, `shell=True`, `eval`, `Runtime.exec` | Probable |
| Contraseñas en código | Patrones + entropía + contexto | Confirmado |
| API keys / tokens expuestos | Patrones por proveedor (AWS, Google, GitHub, Stripe, Slack, JWT, claves privadas…) | Confirmado el formato; **la validez no se comprueba** (no se contacta ningún servicio) |
| Configuración insegura | Debug activo, TLS desactivado, cabeceras, flags de cookies | Confirmado |
| Permisos excesivos | Manifest Android, Electron, reglas Firebase/Supabase | Confirmado (declarado) / Posible (innecesario) |
| Autenticación débil | MD5/SHA1 para contraseñas, JWT sin verificación o `alg: none`, secretos de sesión débiles | Probable |
| Validación insuficiente | Handlers que usan la entrada sin esquema de validación | Posible |
| Dependencias vulnerables | Versión exacta contra OSV | Confirmado (versión exacta en lockfile) / Posible (rango) |
| Librerías desactualizadas | Fechas de fin de soporte (runtimes y frameworks) | Confirmado con datos / **No evaluado** para el resto de librerías |
| CORS | `*` con credenciales, reflejo del origen | Confirmado |
| Información sensible expuesta | `.env` incluidos, claves privadas, volcados SQL, logs, source maps | Confirmado |
| Sesiones | Cookies sin `HttpOnly`/`Secure`/`SameSite`, tokens en `localStorage` | Probable / Requiere prueba dinámica |
| Falta de control de acceso | Rutas sin middleware de autenticación cuando las demás lo tienen; endpoints administrativos | Posible / Requiere prueba dinámica |

### 8.3 Ficha de cada hallazgo

`id` · nombre · categoría (CWE y OWASP Top 10) · archivo · líneas · severidad · certeza · explicación ·
consecuencias · fragmento problemático (redactado) · recomendación · ejemplo corregido (plantilla por lenguaje,
cuando sea viable) · referencias · regla que lo produjo y su versión.

### 8.4 Política de enmascarado de secretos

- El secreto se redacta **en el momento de detectarse, dentro del proceso aislado**: el valor completo nunca llega a la BD, a los informes, a los logs ni a la pantalla.
- Se muestra como máximo el prefijo de 4 caracteres y el sufijo de 2 (`AKIA••••••••••••Q7`), más el tipo y la longitud. Secretos de menos de 12 caracteres: solo tipo y longitud.
- Para saber al comparar análisis si un secreto sigue presente, se guarda una huella HMAC-SHA256 con clave local, no el secreto.
- La copia del código para el visor también pasa por el redactor.

### 8.5 Protección contra evasión

- El motor **no respeta** comentarios de supresión (`nosemgrep`, `# nosec`, `// NOSONAR`, `eslint-disable` en reglas de seguridad) ni archivos que silencian herramientas (`.semgrepignore`, `osv-scanner.toml`, `.gitleaksignore`…).
- Su presencia se informa como hallazgo informativo: revela supresiones deliberadas.

### 8.6 Honestidad del resultado

- El informe incluye siempre: *"La ausencia de hallazgos no implica ausencia de vulnerabilidades"*, la cobertura del análisis y la lista de comprobaciones que requieren pruebas dinámicas (DAST, pruebas de penetración, revisión de la configuración de producción).
- Los hallazgos en carpetas de pruebas o ejemplos y los valores de marcador (`changeme`, `your_api_key`) se rebajan de severidad, pero **no se ocultan**.
- Cada regla tiene casos vulnerables y seguros en las pruebas para medir falsos positivos.

---

## 9. Seguridad de la propia aplicación

### 9.1 Modelo de amenazas

| Atacante | Objetivo | Controles principales |
|----------|----------|-----------------------|
| **Autor del comprimido** (controla nombres, tamaños, estructura y contenido) | Ejecutar código, leer/escribir fuera de lo previsto, agotar recursos, provocar XSS en informes, ocultar vulnerabilidades, envenenar resultados | Proceso aislado, lectura en memoria, límites, parsers seguros, texto siempre escapado, validación de resultados |
| **Otra persona con el teléfono en la mano** | Ver proyectos, informes o secretos | Bloqueo biométrico + contraseña, BD cifrada con clave ligada a la autenticación, bloqueo automático, capturas de pantalla bloqueadas |
| **Otra app del dispositivo / copia de seguridad** | Leer los datos de la app | Almacenamiento privado, cifrado, copias de seguridad desactivadas |

### 9.2 Controles por requisito

| Requisito | Implementación en Android |
|-----------|---------------------------|
| Límites de tamaño | Corte durante la importación; límites por archivo y totales; los tamaños declarados en el comprimido **no se creen**: se cuentan los bytes reales al descomprimir. |
| Protección contra ZIP Bombs | Ratio de compresión por entrada y global; máximo de entradas; detección de entradas solapadas (bombas no recursivas); comprimidos anidados registrados pero **no expandidos**. |
| Path Traversal | **No se escribe ningún archivo extraído**, así que no hay dónde escribir fuera. Aun así, cada nombre se normaliza y valida (rutas absolutas, `..`, NUL, caracteres de control, nombres reservados), y los nombres sospechosos se marcan como hallazgo. Los enlaces simbólicos se registran, nunca se siguen. |
| Aislamiento del análisis | `android:isolatedProcess="true"`: el sistema le asigna un UID aleatorio y un dominio SELinux restringido, **sin permisos, sin red y sin acceso al almacenamiento de la app**. Solo usa los descriptores que recibe. |
| Límites de memoria y tiempo | Presupuestos internos de memoria (tamaño máximo por entrada leída, cachés acotadas, un árbol sintáctico a la vez por hilo); tiempo máximo por archivo y total; vigilante en el proceso principal que desenlaza el servicio si se excede (el sistema destruye entonces el proceso aislado). Un fallo por memoria en el proceso aislado no afecta a la app. |
| Prohibición de ejecutar archivos extraídos | Nada se escribe a disco. Además, Android (API 29+) prohíbe ejecutar binarios desde los directorios de datos de la app. El motor nunca usa `DexClassLoader`, `System.load` ni WebView con contenido del proyecto. |
| Protección de archivos temporales | El único temporal es la copia del comprimido, en el directorio privado `noBackupFilesDir`, **cifrada con una clave efímera** que solo existe en memoria. |
| Eliminación segura | **Borrado criptográfico**: al terminar se borra la copia y se destruye la clave efímera; aunque la memoria flash conserve restos físicos, son ilegibles. Limpieza de huérfanos al iniciar la app. |
| Cifrado de información almacenada | BD completa con SQLCipher; su clave está envuelta por una clave del Android Keystore (en hardware, StrongBox si existe) que **solo puede usarse tras autenticarte**. |
| Solo el propietario accede | Configuración inicial con contraseña propia (Argon2id) y biometría fuerte; bloqueo al pasar a segundo plano y por inactividad; espera creciente tras intentos fallidos y borrado opcional de datos tras N fallos; `FLAG_SECURE` (sin capturas ni miniaturas en recientes). |
| No enviar código a servicios externos | El proceso aislado no puede usar la red. La app solo descarga datos públicos (OSV, fin de soporte). Sin analítica ni SDKs de informes de errores de terceros. Copias de seguridad desactivadas (`allowBackup=false` y reglas de extracción de datos). Exportar o compartir un informe solo ocurre por acción explícita, con aviso. |
| Secretos nunca completos | §8.4 |

Controles adicionales:

- **Parsers**: `XmlPullParser` sin DTD, YAML con límites de alias, JSON con límite de profundidad, RE2/J, tiempo máximo en tree-sitter.
- **Interfaz**: Compose muestra el contenido del proyecto solo como texto; los caracteres de control y de dirección bidireccional (p. ej. U+202E) de los nombres se muestran escapados; no se usa WebView para contenido del proyecto.
- **Informes**: HTML generado con escape automático, sin JavaScript y con su propia CSP. Los informes exportados quedan fuera de la protección de la app (contienen secretos enmascarados, nunca completos); la app lo advierte al exportar.
- **Cadena de suministro propia**: verificación de checksums de dependencias de Gradle, catálogo de versiones, Dependabot y, desde la Fase 6, el motor se analiza a sí mismo en CI mediante `engine:cli`.
- **Distribución**: APK firmado con una clave de Funcode que nunca se sube al repositorio (secreto de GitHub o en tu poder).

### 9.3 Límites por defecto (configurables en Ajustes)

| Límite | Valor |
|--------|-------|
| Tamaño del comprimido | 300 MB |
| Tamaño total descomprimido leído (bytes reales) | 1 GB |
| Número de entradas | 100 000 |
| Tamaño máximo por entrada leída en memoria | 8 MB (las mayores, hasta 100 MB, solo reciben inventario y búsqueda de secretos por flujo) |
| Tamaño máximo para análisis sintáctico | 2 MB |
| Ratio de compresión por entrada | 250:1 (en entradas de más de 1 MB) |
| Ratio de compresión global | 100:1 |
| Profundidad / longitud de ruta | 64 niveles / 1 024 caracteres |
| Comprimidos anidados | Se registran, no se expanden |
| Tiempo total del análisis | 30 min |
| Tiempo por archivo (análisis sintáctico) | 5 s |

---

## 10. Informes y métricas

### 10.1 Secciones del informe

1. Resumen ejecutivo · 2. Descripción general · 3. Tecnologías · 4. Arquitectura · 5. Estructura de carpetas ·
6. Archivos más importantes · 7. Relaciones entre archivos · 8. Bases de datos y conexiones · 9. APIs y servicios
externos · 10. Dependencias · 11. Auditoría de seguridad · 12. Problemas encontrados · 13. Recomendaciones ·
14. Nivel general de mantenimiento · 15. Conclusiones ·
**Anexo A – Cobertura y limitaciones del análisis** · **Anexo B – Metadatos** (hash del comprimido, fecha, versión
de la app y de las reglas, fecha de las bases OSV y de fin de soporte).

- **Resumen ejecutivo y conclusiones**: plantillas deterministas a partir de los datos; cada frase es trazable a un dato del análisis. No se usa IA generativa.
- **Archivos más importantes**: puntos de entrada + centralidad en el grafo (PageRank) + rol (configuración, autenticación, BD) + radio de impacto, con la explicación de por qué cada uno es importante.

### 10.2 Índice de mantenimiento (0–100)

Indicador **orientativo, no un estándar**, compuesto por sub-índices visibles con su valor y explicación:
complejidad ciclomática · tamaño de archivos y funciones · duplicación · pruebas · documentación · salud de
dependencias · herramientas de calidad (linters, formateadores, CI) · deuda señalada (TODO/FIXME/HACK) · estructura
(ciclos de dependencias, acoplamiento extremo). La fórmula y los pesos se muestran en el informe.

### 10.3 Formatos

| Formato | Características |
|---------|-----------------|
| **PDF** | Generado de forma nativa: portada con la marca Funcode, índice, numeración, diagramas vectoriales |
| **HTML** | Un único archivo autocontenido, sin JavaScript, con CSP, secciones plegables, diagramas SVG |
| **JSON** | Datos completos, esquema versionado (`schema_version`), secretos enmascarados, identificadores estables |

Se guardan donde elijas mediante el selector de documentos de Android (sin permisos de almacenamiento) o se
comparten por acción explícita.

**Comparación**: hallazgos nuevos/resueltos, dependencias añadidas/eliminadas/actualizadas, tecnologías, archivos
añadidos/eliminados/modificados (por hash) y evolución de métricas.

---

## 11. Limitaciones técnicas

1. **Análisis solo estático.** Importaciones dinámicas, reflexión, inyección de dependencias y rutas construidas en ejecución generan relaciones *probables* o *no verificadas*, nunca verificadas.
2. **Sin Semgrep ni OWASP Dependency-Check.** No pueden ejecutarse en Android. Se sustituyen por reglas propias y por la base OSV. Consecuencia: el conjunto inicial de reglas será más pequeño que el catálogo comunitario de Semgrep y crecerá por fases. OSV no cubre bibliotecas C/C++ sin gestor de paquetes.
3. **Flujo de datos limitado** a una función (intraprocedural). Vulnerabilidades que atraviesan varias funciones o archivos pueden pasar desapercibidas.
4. **Binarios no analizados**: APK/AAB/DEX, `.so`, `.dll`, `.exe`, `.jar`, Blueprints de Unreal y assets binarios de Unity se inventarían, pero no se decompilan.
5. **Código minificado u ofuscado**: análisis superficial.
6. **Recursos del teléfono.** Proyectos muy grandes tardan más que en un PC y pueden alcanzar los límites de memoria; el análisis se marca entonces como incompleto, con los resultados obtenidos.
7. **Frescura de las vulnerabilidades**: depende de la última actualización de la base OSV del dispositivo (la fecha aparece en cada informe). Los volcados de algunos ecosistemas (sobre todo npm) ocupan bastante espacio: se elige qué ecosistemas descargar.
8. **Obsolescencia**: sin consultar en línea solo se evalúa la fecha de fin de soporte de runtimes y frameworks; para el resto de librerías, "no evaluado".
9. **Resolución de versiones incompleta**: POM padres, BOMs y catálogos externos que no están en el ZIP no se pueden resolver; sin lockfile no hay dependencias transitivas.
10. **RAR**: no se admiten RAR multivolumen. Los RAR con cabeceras cifradas no permiten ver ni los nombres sin contraseña. UnRAR es gratuito y su uso para extraer está permitido, pero no es software libre.
11. **Comprimidos con contraseña**: se detectan; se puede introducir la contraseña, que solo vive en memoria durante el análisis. No se intenta descifrar sin ella.
12. **Falsos positivos y negativos** inherentes a toda detección por patrones; de ahí los niveles de certeza.
13. **Validez de secretos**: se detecta el formato, no si la credencial está activa.
14. **Grafos grandes**: se muestran agregados por carpeta o módulo; el detalle se obtiene con filtros y foco.
15. **Si olvidas la contraseña y la biometría deja de estar disponible, los datos no se pueden recuperar.** Es la consecuencia directa del cifrado exclusivo para el propietario.
16. **Arrastrar y soltar** funciona en tablets, multiventana y modos escritorio (p. ej. Samsung DeX); en un teléfono el camino principal es el selector de archivos o "Abrir con…".
17. **El índice de mantenimiento es una heurística**, no una medida objetiva de calidad.

---

## 12. Estrategia de pruebas

| Tipo | Dónde se ejecuta | Contenido |
|------|------------------|-----------|
| **Motor (unitarias y de integración)** | JVM (PC y CI) | Validador de nombres (pruebas basadas en propiedades), límites, redactor, resolutores, parsers, cada regla |
| **Corpus malicioso** | JVM | ZIP slip, rutas absolutas, enlaces simbólicos, ZIP bomb clásica y por solapamiento, ZIP cifrado (ZipCrypto y AES), CRC corrupto, archivo truncado, RAR4/RAR5 válidos, RAR con datos o cabeceras cifradas, multivolumen, extensión falsa, nombres con caracteres de control/bidi, 100 000 entradas vacías |
| **Proyectos de referencia** | JVM | Mini proyectos por tipo con resultados esperados, **incluidas aserciones negativas** (la tecnología X no debe detectarse; la relación Y debe salir como "no verificada") |
| **Interfaz** | JVM (Robolectric + pruebas de Compose) | Pantallas, navegación, estados, escape de texto hostil |
| **Instrumentadas** | Emulador en GitHub Actions y tu dispositivo | Proceso aislado (sin red, sin acceso a archivos), Keystore, bloqueo, extremo a extremo |
| **Seguridad de la app** | JVM + instrumentadas | XSS en el informe HTML; XXE; bomba YAML; cadenas ReDoS; registros de resultados manipulados rechazados; terminación por tiempo |
| **Aceptación** | Tu dispositivo | Al final de cada fase instalas el APK generado por CI y compruebas la funcionalidad |
| **CI** | GitHub Actions | detekt, ktlint, Lint, pruebas JVM, pruebas en emulador, compilación del APK, verificación de dependencias |

---

## 13. Plan de implementación por fases

Cada fase entrega su parte del motor **y** de la interfaz, y termina con un APK instalable. Al cerrar cada fase:
pruebas ejecutadas, verificación funcional, documentación en `docs/fases/FASE-N.md`, lista de errores pendientes y
**espera de tu aprobación**.

| Fase | Entregables | Criterio de aceptación |
|------|-------------|------------------------|
| **1. Arquitectura y configuración inicial** | Proyecto Gradle multimódulo; tema Funcode y navegación entre las pantallas (vacías y marcadas como tales); **bloqueo de acceso** (contraseña + biometría) y **BD cifrada**; servicio aislado con AIDL, vigilante y tubería de resultados (con un análisis de prueba); CI que compila el APK | Instalas el APK, configuras tu acceso, navegas; las pruebas demuestran que el proceso aislado no tiene red ni acceso a archivos |
| **2. Carga y extracción ZIP/RAR** | Importación cifrada con límites, firma real, ZIP y RAR (UnRAR nativo), todas las protecciones de §9.2, exclusiones configurables, duplicados, progreso; pantalla **Nuevo análisis** | Un ZIP/RAR legítimo se procesa; cada muestra maliciosa se rechaza con un mensaje claro |
| **3. Explorador y estructura** | Inventario, roles, puntos de entrada, archivos posiblemente innecesarios, copia redactada del código; pantalla **Explorador** | Se navega el árbol y se consulta la ficha de cualquier archivo |
| **4. Lenguajes y tecnologías** | Gramáticas tree-sitter, detección de lenguajes y líneas, motor de reglas YAML, BD y servicios externos | Cada tecnología muestra dónde se encontró y su confianza |
| **5. Relaciones** *(en 5A y 5B)* | 5A: extractores, resolutores, grafo, impacto. 5B: analizadores de framework y enlace frontend↔backend | Cada conexión muestra sus 5 atributos y su evidencia |
| **6. Auditoría de seguridad** | Reglas, secretos y enmascarado, auditores de configuración, protección contra evasión; pantalla **Seguridad** | Fichas completas con certeza y severidad; ningún secreto completo en la salida |
| **7. Dependencias** | Parsers de manifiestos/lockfiles, actualizador e índice OSV, fin de soporte, uso real, criticidad | Dependencias directas/indirectas con vulnerabilidades y uso |
| **8. Diagramas** | Disposición por capas/módulos/archivos/flujos; pantalla **Arquitectura** | Flujo Frontend → API → Controlador → Servicio → Modelo → BD legible cuando existe |
| **9. Informes** | JSON, HTML, PDF nativo, índice de mantenimiento, resumen ejecutivo, historial, comparación; pantalla **Informes** | Los tres formatos se exportan con las 15 secciones + anexos |
| **10. Interfaz final, pruebas y optimización** | Dashboard completo, pulido visual y animaciones, accesibilidad, rendimiento en dispositivo real, pruebas extremo a extremo, revisión de seguridad, manual de uso | Proyecto desconocido → diagnóstico completo en el teléfono |

**Extensión opcional posterior (no incluida en las 10 fases)**: un complemento para PC que ejecute Semgrep y
OWASP Dependency-Check sobre el mismo proyecto y genere un JSON que la app pueda importar y fusionar con su informe.

---

## 14. Estructura del repositorio

```
Lector-de-archivos/
├── app/                    interfaz Compose, bloqueo, coordinador, informes PDF, actualizador
├── analyzer-service/       servicio aislado + AIDL
├── data/                   Room + SQLCipher, repositorios, Keystore, validación de resultados
├── engine/
│   ├── api/  core/  ingest/  inventory/  languages/  technologies/
│   ├── relations/  security/  dependencies/  layout/  metrics/  report/
│   └── cli/                solo pruebas y CI
├── native/
│   ├── treesitter/         gramáticas + JNI
│   └── unrar/              UnRAR + JNI
├── testing/                corpus malicioso y proyectos de referencia
├── gradle/                 catálogo de versiones, verificación de dependencias
├── docs/                   ARQUITECTURA.md, adr/, fases/
└── .github/workflows/      CI: análisis, pruebas, emulador, APK
```

---

## 15. Decisiones

### 15.1 Aprobadas por el propietario

| # | Decisión | Resultado |
|---|----------|-----------|
| D1 | Plataforma | **App Android (APK)** |
| D2 | Uso | **Herramienta interna de Funcode Studio CB** (no se comercializa) |
| D3 | Red | Sin envío de código; solo descarga de bases públicas |
| D4 | Copia del código | Sí, cifrada y con secretos redactados; desactivable |
| D5 | Idioma | **Español** |
| D6 | Comprimidos con contraseña | Detectar y permitir introducir la contraseña (solo en memoria) |
| D7 | Acceso | **Exclusivo del propietario** (biometría + contraseña propia) |

### 15.2 Confirmadas después

| # | Decisión | Resultado |
|---|----------|-----------|
| N1 | Dónde se ejecuta el análisis | **Todo en el teléfono** (proceso aislado), confirmado por el propietario |

### 15.3 Entorno de desarrollo

El acceso a `dl.google.com` (Android SDK y NDK) quedó habilitado. Las decisiones técnicas tomadas durante la
implementación se registran en `docs/adr/`.
