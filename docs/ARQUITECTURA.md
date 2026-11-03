# Funcode Code Analyzer — Propuesta de arquitectura técnica

**Funcode Studio CB** · Documento de arquitectura v0.1 · Fecha: 2026-11-03
**Estado: PENDIENTE DE APROBACIÓN (Fase 0).** No se ha escrito código de la aplicación. Este documento describe
qué se va a construir, con qué tecnologías, cómo se comunicarán los módulos, qué limitaciones existen y en qué
orden se implementará.

---

## Índice

1. [Análisis de requisitos](#1-análisis-de-requisitos)
2. [Arquitectura propuesta](#2-arquitectura-propuesta)
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
15. [Decisiones que requieren su aprobación](#15-decisiones-que-requieren-su-aprobación)

---

## 1. Análisis de requisitos

### 1.1 Requisitos funcionales

| ID | Área | Resumen |
|----|------|---------|
| RF-01 | Ingesta | Carga de un único `.zip` o `.rar` (arrastrar o seleccionar). Nombre, tamaño, formato, progreso. Detección de archivos corruptos y protegidos con contraseña. Duplicados. Exclusiones configurables que se registran aunque no se analicen. |
| RF-02 | Estructura | Árbol interactivo. Clasificación de cada archivo por rol (configuración, entrada, dependencias, BD, autenticación, servidor, UI, multimedia, posiblemente innecesario). Ficha por archivo: nombre, extensión, tamaño, ubicación, función, dependencias y archivos relacionados. |
| RF-03 | Tecnologías | Lenguajes, frameworks, motores y bases de datos, con evidencia (dónde) y función (para qué). Una referencia indirecta **no** basta para afirmar que una tecnología está presente. |
| RF-04 | Relaciones | Importaciones, clases, funciones, módulos, componentes, rutas, controladores, modelos, servicios, accesos a BD, llamadas a APIs, configuración. Mapa de arquitectura. Para cada conexión: origen, destino, tipo, información intercambiada e impacto de modificarla o eliminarla. Nada inventado: lo no demostrable se marca como posible o no verificado. |
| RF-05 | Seguridad | Análisis estático de 18 categorías de problemas. Ficha completa por hallazgo. Distinción entre confirmado, probable, posible y "requiere prueba dinámica". Nunca declarar que un proyecto es seguro. |
| RF-06 | Dependencias | Directas, indirectas (si hay lockfile), versiones, vulnerables, obsoletas, aparentemente innecesarias y críticas. |
| RF-07 | Diagramas | Arquitectura por capas, dependencias y flujo de información. |
| RF-08 | Informes | 15 secciones; exportación PDF, HTML y JSON; historial; comparación entre análisis del mismo proyecto. |
| RF-09 | Interfaz | Dashboard, Nuevo análisis, Explorador, Arquitectura, Seguridad, Informes, con la identidad visual de Funcode Studio CB. |

### 1.2 Requisitos no funcionales

| ID | Requisito |
|----|-----------|
| RNF-01 | **El proyecto analizado se trata como hostil.** Cualquier archivo puede estar diseñado para atacar al analizador. |
| RNF-02 | **Nunca se ejecuta código del proyecto**: ni compilaciones, ni scripts de instalación, ni `setup.py`, ni Gradle/Maven, ni `npm install`. Todo se lee como texto o se analiza sintácticamente. |
| RNF-03 | **Privacidad y funcionamiento sin conexión por defecto.** El código nunca sale del equipo. Cualquier consulta externa requiere activación explícita y solo envía nombre y versión de paquetes. |
| RNF-04 | **Honestidad verificable**: cada afirmación lleva evidencia (archivo, línea, fragmento) y nivel de confianza. Lo que no se pudo analizar aparece en una sección obligatoria de *cobertura*. |
| RNF-05 | **Extensibilidad**: nuevos lenguajes, frameworks, reglas y herramientas se añaden como plugins sin modificar el núcleo. |
| RNF-06 | **Robustez**: un fallo en un analizador o en un archivo no aborta el análisis; se registra y el resto continúa. |
| RNF-07 | **Rendimiento** (objetivo a validar en Fase 10): ~10 000 archivos fuente en menos de 5 minutos en un equipo de 4 núcleos y 8 GB. |
| RNF-08 | **Reproducibilidad**: cada informe registra versiones de la herramienta, de las reglas y fecha de la base de vulnerabilidades usada. |

### 1.3 Principios de diseño

1. **Evidencia antes que afirmación.** Ningún resultado se muestra sin la prueba que lo sustenta.
2. **Defensa en profundidad.** Cada barrera de seguridad asume que la anterior puede fallar.
3. **Offline-first.** Todo funciona sin Internet; la red es una mejora opcional, nunca un requisito.
4. **Núcleo pequeño + plugins.** El núcleo orquesta; los analizadores concretos son intercambiables.
5. **Degradación honesta.** Si falta una herramienta, una gramática o un dato, el informe lo dice.

### 1.4 Ambigüedades detectadas y cómo propongo resolverlas

| Ambigüedad | Propuesta |
|------------|-----------|
| ¿Qué significa "vulnerabilidad confirmada" en un análisis que no ejecuta código? | "Confirmado" significará *la condición existe de forma observable en el código o la configuración* (p. ej. un secreto escrito en claro, `debuggable=true`, una versión con un aviso de seguridad publicado). No significa "explotabilidad demostrada". Ver §6. |
| "Dependencias obsoletas" exige conocer la última versión publicada, dato que no está en el ZIP. | Sin conexión: se usa una instantánea local descargada por el actualizador. Con conexión (opcional y explícita): consulta a los registros públicos. Sin ninguno de los dos, el informe dice "no evaluado". |
| "Comparación entre análisis de un mismo proyecto": ¿cómo se sabe que es el mismo? | El usuario asigna el análisis a un proyecto al subirlo (con sugerencia automática basada en el nombre y la similitud de la estructura de archivos). |
| "Cifrado de información sensible almacenada": ¿qué datos? | Se cifran los resultados completos y las copias del código. Solo los metadatos necesarios para el dashboard (nombre, fecha, contadores) quedan sin cifrar. |
| Plataforma: ¿web, escritorio o servidor? | Aplicación web **local** (se usa en el navegador, pero corre en el propio equipo) desplegada con Docker. Ver decisión D1 en §15. |

---

## 2. Arquitectura propuesta

### 2.1 Vista general

```
 ┌──────────────────────────────── Equipo del usuario ─────────────────────────────────┐
 │                                                                                     │
 │   Navegador (SPA React)                                                             │
 │        │  HTTP + SSE solo en 127.0.0.1                                              │
 │        ▼                                                                            │
 │   ┌──────────────────────────┐        ┌───────────────────────────────┐             │
 │   │  API (FastAPI)           │───────►│ Datos: SQLite + blobs cifrados│             │
 │   │  · REST + progreso SSE   │        │ (AES-256-GCM, clave aparte)   │             │
 │   │  · recepción de cargas   │        └───────────────────────────────┘             │
 │   │  · historial, informes   │                                                      │
 │   └─────┬──────────────▲─────┘                                                      │
 │         │ deposita      │ recoge resultado                                          │
 │         │ trabajo       │ (lo valida como NO confiable)                             │
 │         ▼               │                                                           │
 │   ┌──────────────────────────┐                                                      │
 │   │  SPOOL (tmpfs compartido)│  incoming/ · claimed/ · progress/ · results/         │
 │   └─────┬──────────────▲─────┘                                                      │
 │         ▼               │                                                           │
 │   ┌──────────────────────────────────────────────┐                                  │
 │   │  WORKER (contenedor aislado)                 │                                  │
 │   │  sin red · raíz de solo lectura · sin        │                                  │
 │   │  privilegios · límites de CPU/RAM/procesos   │                                  │
 │   │   └─ 1 proceso hijo por análisis             │                                  │
 │   │      (rlimits + tiempo máximo + kill)        │                                  │
 │   │      extracción en tmpfs noexec,nosuid,nodev │                                  │
 │   └──────────────────────▲───────────────────────┘                                  │
 │                          │ solo lectura                                             │
 │   ┌──────────────────────┴───────────────────────┐                                  │
 │   │  Bases de vulnerabilidades (OSV / NVD)       │                                  │
 │   └──────────────────────▲───────────────────────┘                                  │
 │                          │ escribe                                                  │
 │   ┌──────────────────────┴───────────────────────┐                                  │
 │   │  UPDATER (opcional, único con Internet)      │                                  │
 │   │  descarga datos públicos; nunca ve código    │                                  │
 │   └──────────────────────────────────────────────┘                                  │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Componentes

| Componente | Responsabilidad | Acceso a red | Acceso a datos del usuario |
|------------|-----------------|--------------|----------------------------|
| **web** (SPA) | Interfaz gráfica. Solo muestra datos; nunca interpreta contenido del proyecto como HTML. | Solo a la API local | Lo que la API le entrega |
| **api** | Recibe cargas, gestiona análisis, historial, ajustes, informes y comparaciones. Única dueña de la BD y de la clave de cifrado. | Solo `127.0.0.1` | BD cifrada y spool |
| **worker** | Ejecuta el pipeline de análisis dentro de un sandbox. No ve la BD ni la clave. | **Ninguna** | Solo el trabajo actual |
| **updater** | Descarga bases de vulnerabilidades y, opcionalmente, metadatos de versiones de paquetes. | Internet (solo datos públicos) | **Ninguno** |

### 2.3 Flujo de un análisis

1. **Carga.** El navegador envía el archivo por streaming. La API corta la transmisión si supera el límite
   (no espera a recibirlo entero), comprueba la firma real del archivo (ZIP `PK\x03\x04`/`PK\x05\x06`;
   RAR4 `Rar!\x1A\x07\x00`; RAR5 `Rar!\x1A\x07\x01\x00`) sin fiarse de la extensión, y calcula su SHA-256.
2. **Encolado.** La API crea el registro del análisis (`en_cola`) y deposita archivo + `job.json` en
   `spool/incoming/` mediante escritura temporal y *rename* atómico.
3. **Reclamo.** El worker reclama el trabajo con otro *rename* atómico y lanza un proceso hijo con límites.
4. **Pipeline** (cada etapa informa progreso y registra cobertura):

   | # | Etapa | Resultado |
   |---|-------|-----------|
   | E1 | Inspección del comprimido **sin extraer** | listado, cifrado, corrupción, bombas, rutas peligrosas |
   | E2 | Extracción segura y selectiva | lo excluido se registra pero **no se escribe en disco** |
   | E3 | Inventario | hashes, duplicados, binario/texto, codificación, tamaños |
   | E4 | Lenguajes | lenguaje por archivo, líneas de código/comentario/blancas |
   | E5 | Tecnologías | frameworks, motores, BD y servicios con evidencia |
   | E6 | Análisis sintáctico | símbolos, imports, rutas, llamadas HTTP, accesos a BD, claves de configuración |
   | E7 | Relaciones | resolución de imports, semántica de frameworks, enlaces entre capas, grafo, impacto |
   | E8 | Seguridad | reglas propias + herramientas externas, deduplicación, clasificación |
   | E9 | Dependencias | manifiestos, lockfiles, vulnerabilidades, uso real, criticidad |
   | E10 | Métricas | complejidad, duplicación, índice de mantenimiento |
   | E11 | Diagramas | vistas agregadas del grafo |
   | E12 | Empaquetado | `result.json` + contenido de archivos de texto **ya redactado** |
   | E13 | Limpieza | borrado del directorio de extracción y del archivo subido |

5. **Progreso.** El worker escribe eventos JSON por línea; la API los reenvía al navegador por SSE.
6. **Ingesta del resultado.** La API valida `result.json` contra su esquema y sus límites (lo trata como no
   confiable, porque si un parser fuese explotado el worker estaría comprometido), lo cifra, lo guarda y
   borra el spool.
7. **Informes.** Se generan bajo demanda a partir del resultado almacenado.

### 2.4 Modos de ejecución

| Modo | Uso | Aislamiento |
|------|-----|-------------|
| **Docker Compose** (recomendado) | Linux, Windows y macOS (Docker Desktop) | Completo: contenedor del worker sin red, solo lectura, sin capacidades, con límites; en Windows/macOS además dentro de la VM de Docker. |
| **Local sin Docker** | Desarrollo o equipos sin Docker | Reducido: subproceso con límites POSIX. La interfaz muestra un aviso permanente. No recomendado para muestras sospechosas. |

---

## 3. Tecnologías seleccionadas

### 3.1 Backend y motor de análisis

| Necesidad | Tecnología | Motivo | Alternativas descartadas |
|-----------|-----------|--------|--------------------------|
| Lenguaje | **Python 3.12+** | Mejor ecosistema de análisis estático: tree-sitter, Semgrep (nativo de Python), parsers seguros de XML/YAML/TOML, generación de PDF. | Node.js (menos maduro en PDF y análisis); Go/Rust (más rápidos, pero ralentizan la escritura de reglas) |
| API | **FastAPI + Pydantic v2 + Uvicorn** | Tipado estricto, validación, OpenAPI automático para generar el cliente TypeScript. | Django (más pesado), Flask (menos validación) |
| Persistencia | **SQLite (WAL) + SQLAlchemy 2 + Alembic** | Local, sin servidor, migraciones. Cambiable a PostgreSQL para multiusuario. | SQLCipher (empaquetado complejo) |
| Cifrado | **`cryptography`** (AES-256-GCM; Argon2id si hay contraseña) | Estándar, cifrado autenticado. | Cifrado propio (nunca) |
| Análisis sintáctico | **tree-sitter + tree-sitter-language-pack** | Un único motor para todos los lenguajes requeridos, tolerante a errores de sintaxis, consultas declarativas (`.scm`). | Un parser distinto por lenguaje (inconsistente); expresiones regulares (insuficientes para relaciones) |
| Grafos | **NetworkX** | Ciclos, centralidad, alcanzabilidad (análisis de impacto). | Neo4j (servidor innecesario) |
| Regex sobre contenido no confiable | **google-re2** | Tiempo lineal garantizado: inmune a ReDoS. | `re` estándar (vulnerable a ReDoS con entradas maliciosas) |
| XML / YAML / TOML | **defusedxml**, `yaml.safe_load`, `tomllib` | Protección contra XXE, *billion laughs* y deserialización insegura. | `xml.etree` directo, `yaml.load` |
| Codificación de texto | **charset-normalizer** | Detecta UTF-8/UTF-16/Latin-1, etc. | — |
| Complejidad ciclomática | **lizard** (+ cálculo propio con tree-sitter donde no llegue) | Soporta la mayoría de lenguajes requeridos. | — |
| ZIP | **`zipfile`** (biblioteca estándar) + capa de validación propia | Lectura por flujo, control total de la escritura. | Extraer con `unzip` (no controlamos las rutas) |
| RAR | **`rarfile`** + binario **unrar** (o 7-Zip) | `rarfile` lee las cabeceras en Python (listado, cifrado, tamaños) antes de descomprimir; la descompresión se hace por flujo y **nuestro código** decide dónde se escribe cada archivo. | Dejar que `unrar` extraiga directamente (rutas fuera de nuestro control) |
| SAST externo | **Semgrep Community Edition** (o **Opengrep**, fork LGPL compatible) con **reglas propias de Funcode** | Herramienta reconocida, multilenguaje, con análisis de flujo de datos (taint) intraprocedural. | — |
| Vulnerabilidades de dependencias | **OSV** (base de datos offline + `osv-scanner`) como fuente principal; **OWASP Dependency-Check** como módulo opcional | OSV cubre npm, PyPI, Maven, Packagist, NuGet, Pub, Go, crates.io, RubyGems. Dependency-Check aporta profundidad en Java/.NET pero necesita Java y una base NVD local de gran tamaño. | — |
| Secretos | **Motor propio** (patrones por proveedor + entropía + contexto) y **gitleaks** opcional (`--redact`) | El motor propio garantiza que el secreto completo nunca sale del sandbox. | Solo herramienta externa (menos control sobre el enmascarado) |
| Diagramas en informes | **Graphviz** (SVG, offline) | Determinista, sin navegador. | Mermaid (requiere navegador sin cabeza) |
| Informes | **Jinja2** (autoescape) → HTML; **WeasyPrint** → PDF; **JSON Schema** versionado | Una sola plantilla para HTML y PDF. WeasyPrint permite bloquear toda descarga externa. | Chromium headless (pesado, más superficie de ataque) |
| Logs | **structlog** (JSON) | Logs estructurados que nunca incluyen contenido de archivos. | — |
| Calidad | **uv**, **ruff**, **mypy**, **pytest**, **hypothesis** | Dependencias bloqueadas, lint, tipos, pruebas basadas en propiedades. | — |

### 3.2 Frontend

| Necesidad | Tecnología |
|-----------|-----------|
| Base | **React + TypeScript + Vite** |
| Estado del servidor / de la UI | **TanStack Query** / **Zustand** |
| Navegación | **React Router** |
| Estilos | **Tailwind CSS** con tokens de diseño Funcode + primitivas accesibles **Radix UI** |
| Iconos / animaciones | **lucide-react** / **Framer Motion** (respeta `prefers-reduced-motion`) |
| Árbol de archivos | **react-arborist** (virtualizado: miles de nodos sin bloquear la UI) |
| Visor de código | **CodeMirror 6** en solo lectura, con resaltado de sintaxis y marcas en líneas con hallazgos |
| Diagramas interactivos | **Cytoscape.js + ELK** (diseño por capas, agrupación, filtrado, foco por nodo) |
| Gráficas | **Apache ECharts** (barras, anillos, treemap de carpetas, evolución temporal) |
| Carga de archivos | **react-dropzone** |
| Progreso | **Server-Sent Events** (`EventSource`) |
| Pruebas | **Vitest + Testing Library**; extremo a extremo con **Playwright** |

### 3.3 Identidad visual (tokens iniciales, ajustables)

| Token | Valor | Uso |
|-------|-------|-----|
| `--fc-bg` | `#05070D` | Fondo principal (negro) |
| `--fc-navy` | `#0A1A33` | Paneles (azul marino) |
| `--fc-navy-2` | `#10284D` | Paneles elevados, bordes |
| `--fc-electric` | `#1E90FF` | Acciones, enlaces, foco, nodos del grafo |
| `--fc-gold` | `#D4AF37` | Acentos de marca, títulos destacados, puntuaciones |
| Severidades | crítico `#FF3B5C`, alto `#FF8A3D`, medio `#F5C542`, bajo `#4FC3F7`, info `#8A9BB5` | Con contraste AA verificado sobre fondo oscuro |

Si Funcode Studio CB tiene logotipo, tipografía o colores oficiales, se sustituyen estos valores en la Fase 1.

### 3.4 Infraestructura

**Docker Compose** (servicios `api`, `worker`, `updater` opcional), **GitHub Actions** para CI.

---

## 4. Módulos

### 4.1 Backend (paquete `funcode_analyzer`)

| Módulo | Responsabilidad | Fase |
|--------|-----------------|------|
| `core` | Modelos de dominio, configuración, registro de plugins, orquestador del pipeline, contexto de análisis, registro de cobertura | 1 |
| `storage` | BD, repositorios, cifrado, gestión de claves | 1 |
| `jobs` | Spool, bucle del worker, sandbox (límites, tiempos, terminación del grupo de procesos), eventos de progreso | 1–2 |
| `api` | Endpoints REST, SSE, validación de cargas, cabeceras de seguridad | 1–2 |
| `ingest` | Manejadores ZIP/RAR, validación previa, extracción segura, exclusiones | 2 |
| `inventory` | Árbol, hashes, duplicados, binario/texto, roles de archivo, puntos de entrada, archivos posiblemente innecesarios | 3 |
| `languages` | Detección de lenguaje, conteo de líneas, carga de gramáticas | 4 |
| `technologies` | Motor de reglas declarativas con evidencia y confianza | 4 |
| `relations` | Extractores por lenguaje, resolutores por ecosistema, analizadores de framework, vinculación entre capas, grafo, impacto | 5 |
| `security` | Motor de reglas, secretos, redacción, auditores de configuración, adaptadores de herramientas externas, normalización | 6 |
| `dependencies` | Parsers de manifiestos y lockfiles, base de vulnerabilidades, uso real, criticidad | 7 |
| `diagrams` | Agregación del grafo, vistas, exportación DOT/SVG | 8 |
| `metrics` | Complejidad, duplicación, índice de mantenimiento | 9 |
| `reports` | Esquema JSON, HTML, PDF, comparación entre análisis | 9 |

### 4.2 Puntos de extensión (plugins)

Boceto de diseño (no es código definitivo). Cada plugin se registra mediante *entry points* de Python
(`funcode_analyzer.plugins`), de modo que se pueden añadir lenguajes, frameworks o herramientas sin tocar el núcleo.

```python
class ArchiveHandler(Protocol):          # zip, rar; futuro: 7z, tar.gz
    def probe(self, head: bytes) -> bool: ...
    def inspect(self, path) -> ArchiveListing: ...        # sin extraer nada
    def open_member(self, path, member) -> BinaryIO: ...  # flujo; la escritura la hace el núcleo

class LanguageExtractor(Protocol):       # uno por lenguaje, basado en consultas tree-sitter
    def extract(self, file: SourceFile, tree: Tree) -> Extraction: ...
    # -> símbolos, imports, rutas declaradas, llamadas HTTP, accesos a BD, claves de configuración

class ImportResolver(Protocol):          # uno por ecosistema (JS/TS, Python, JVM, PHP, Dart, .NET, C/C++)
    def resolve(self, ref: ImportRef, index: ProjectIndex) -> Resolution: ...  # destino + confianza + motivo

class FrameworkAnalyzer(Protocol):       # Express, Django, Laravel, Spring, Android, Unity, Godot...
    def applies(self, techs: TechnologyReport) -> bool: ...
    def analyze(self, ctx: AnalysisContext) -> FrameworkFacts: ...  # roles, rutas, enlaces semánticos

class SecurityRule(Protocol): ...        # regla propia: consulta tree-sitter, RE2 o chequeo de configuración
class ExternalTool(Protocol): ...        # Semgrep, osv-scanner, Dependency-Check, gitleaks
class DependencyParser(Protocol): ...    # package.json, pom.xml, pubspec.yaml...
class ReportRenderer(Protocol): ...      # json, html, pdf; futuro: sarif, cyclonedx
```

Las tecnologías se definen en YAML, no en código:

```yaml
id: laravel
name: Laravel
category: framework
language: php
role: "Framework web MVC del backend (rutas, controladores, ORM Eloquent)"
signals:                          # cada señal aporta evidencia con un peso
  - { kind: manifest_dependency, ecosystem: composer, package: laravel/framework, weight: 0.6 }
  - { kind: file_exists, path: artisan, weight: 0.25 }
  - { kind: code_reference, pattern: 'Illuminate\\', weight: 0.25 }
thresholds: { confirmed: 0.8, probable: 0.5 }
# Las menciones en documentación, comentarios o dependencias transitivas NUNCA suman:
# se listan aparte como "menciones no concluyentes".
```

### 4.3 Frontend (pantallas)

| Pantalla | Contenido |
|----------|-----------|
| **Dashboard** | Proyectos analizados, últimos análisis, vulnerabilidades por severidad, tecnologías más utilizadas, tendencia |
| **Nuevo análisis** | Zona de carga, ficha del archivo, configuración (exclusiones, módulos, límites, herramientas), progreso por etapas en tiempo real |
| **Explorador** | Árbol virtualizado con filtros por rol, visor de código con hallazgos marcados, panel de ficha del archivo y relaciones |
| **Arquitectura** | Diagrama por capas, grafo de archivos/módulos con foco y filtros, flujos de información, dependencias |
| **Seguridad** | Hallazgos por severidad y certeza, filtros, ficha detallada, recomendaciones, pruebas dinámicas recomendadas |
| **Informes** | Historial, descargas PDF/HTML/JSON, comparación entre análisis del mismo proyecto |

---

## 5. Comunicación entre módulos

### 5.1 Navegador ↔ API

- REST JSON bajo `/api/v1`. Los tipos TypeScript se **generan** desde el OpenAPI de FastAPI (sin contratos duplicados a mano).
- Progreso por SSE: `GET /api/v1/analyses/{id}/events`.
- Endpoints previstos (boceto):

```
POST   /analyses                      carga + configuración → id
GET    /analyses                      historial (paginado)
GET    /analyses/{id}                 estado y resumen
GET    /analyses/{id}/events          progreso (SSE)
GET    /analyses/{id}/tree            árbol de archivos
GET    /analyses/{id}/files/{fid}     ficha + contenido redactado
GET    /analyses/{id}/technologies
GET    /analyses/{id}/graph?view=layers|modules|files|flows&focus=…
GET    /analyses/{id}/findings?severity=…&certainty=…
GET    /analyses/{id}/dependencies
GET    /analyses/{id}/report.{pdf|html|json}
GET    /analyses/{a}/compare/{b}
DELETE /analyses/{id}                 borrado de resultados y contenido
GET    /dashboard
GET    /settings  ·  PUT /settings
```

### 5.2 API ↔ Worker: spool de archivos

El worker **no tiene red**, por lo que la comunicación se hace mediante un directorio compartido en memoria (tmpfs):

```
spool/
  incoming/<job>/archive.bin, job.json     API escribe en .tmp y renombra (atómico)
  claimed/<job>/                            el worker reclama renombrando (atómico)
  progress/<job>.jsonl                      el worker añade eventos; la API los lee y reenvía por SSE
  results/<job>/result.json, files.pack     el worker escribe; marca .done al terminar
  worker.heartbeat                          latido para que la API detecte un worker caído
```

Reglas del contrato:

- La API **valida** todo lo que produce el worker (esquema, tamaños, rutas) antes de guardarlo.
- El worker nunca recibe la clave de cifrado ni accede a la BD (mínimo privilegio).
- En modo sin Docker se usa exactamente el mismo spool; solo cambia quién lanza el bucle del worker.

### 5.3 Dentro del worker

- Las etapas del pipeline comparten un `AnalysisContext` (inventario inmutable + resultados acumulados) y declaran sus dependencias.
- El trabajo por archivo se paraleliza con un *pool* de procesos, con tiempo máximo por archivo.
- Cada etapa escribe en el registro de cobertura qué analizó, qué omitió y por qué.

---

## 6. Modelo de evidencia y niveles de confianza

Este modelo es el mecanismo que impide que la herramienta "invente". **Toda afirmación** del análisis es un
registro con evidencia:

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

### 6.2 Atributos de cada conexión (requisito 5)

| Atributo | Cómo se obtiene |
|----------|-----------------|
| Origen y destino | Archivo + símbolo, con línea |
| Tipo | `import`, `herencia`, `implementación`, `llamada`, `ruta→handler`, `http`, `acceso_bd`, `lectura_config`, `recurso` (layout, asset, escena), `ipc`, `inyección` |
| Información intercambiada | Datos extraídos, nunca redactados libremente: símbolos importados; método + ruta + campos del cuerpo; tablas/colecciones y operaciones (SELECT/INSERT…); claves de configuración leídas; canales IPC. Si no es determinable: "No determinable estáticamente". |
| Impacto de modificar/eliminar | Calculado sobre el grafo: símbolos que quedarían sin definir, archivos dependientes directos y transitivos (radio de impacto), si está en un camino desde un punto de entrada, y efecto específico del tipo (p. ej. "cambiar esta ruta rompe las llamadas desde `api.ts:42` y `UserService.kt:17`"). |
| Confianza y evidencia | Según §6.1 |

### 6.3 Cobertura

Sección obligatoria en la interfaz y en el informe: archivos analizados por cada analizador, archivos omitidos
(binario, demasiado grande, minificado, excluido, error de sintaxis, lenguaje sin soporte) con el motivo, y
herramientas no disponibles o fallidas.

---

## 7. Estrategia de análisis por tipo de proyecto

Un mismo comprimido puede contener varios subproyectos (monorepo, app + backend, Flutter con `android/`): cada
manifiesto define una raíz de subproyecto, cada uno se analiza con su estrategia y después se enlazan entre sí.

| Tipo | Detección | Qué se extrae | Relaciones específicas | Seguridad específica | Límites |
|------|-----------|---------------|------------------------|----------------------|---------|
| **Android** (Java/Kotlin) | `AndroidManifest.xml`, plugin `com.android.application`, `libs.versions.toml` | Activities, Services, Receivers, Providers, intent-filters (launcher = punto de entrada), permisos, layouts, `@Composable`, navegación, ViewModels, Room, Retrofit, Firebase | Layout XML ↔ Activity/Fragment (`setContentView(R.layout.x)`, ViewBinding); Manifest ↔ clases; interfaz Retrofit → endpoints; DAO → Entity → tabla | `debuggable`, `allowBackup`, `usesCleartextTraffic`, network security config, componentes exportados sin permiso, permisos peligrosos sin uso aparente, WebView con JS y `addJavascriptInterface`, TrustManager permisivo, secretos en `strings.xml`/Gradle | APK/AAB/DEX/.so no se decompilan; `R` y `BuildConfig` son generados y no están en el ZIP (se infieren) |
| **Web frontend** (React, Angular, Vue) | `package.json`, `angular.json`, configuración de Vite/Webpack | Componentes, rutas del router, stores, servicios HTTP (fetch, axios, HttpClient), variables de entorno | Componente → componente hijo; ruta → componente; servicio → endpoint del backend | Sinks XSS (`dangerouslySetInnerHTML`, `v-html`, `[innerHTML]`, `bypassSecurityTrust*`), secretos en variables públicas (`VITE_*`, `NEXT_PUBLIC_*`, `REACT_APP_*`), tokens en `localStorage` | Código minificado o compilado: análisis superficial |
| **Páginas web estáticas** | `*.html` sin framework | Enlaces, scripts, hojas de estilo, formularios, recursos | HTML → CSS/JS/imágenes; formulario → `action` | Scripts externos sin SRI, contenido mixto `http://`, formularios sin protección, manejadores en línea | — |
| **Backend / APIs** (Express, NestJS, Django, Flask, FastAPI, Laravel, Spring Boot, ASP.NET Core) | Manifiestos + marcadores (`manage.py`, `artisan`, `@SpringBootApplication`…) | Rutas, middleware, controladores, servicios, modelos ORM, migraciones, esquemas SQL | Ruta → handler → servicio → repositorio/modelo → tabla | SQLi, inyección de comandos, path traversal, CORS, CSRF, rutas sin middleware de autenticación, JWT inseguro, cookies sin flags, modo debug, asignación masiva | Inyección de dependencias y reflexión → relaciones *probables*; rutas construidas dinámicamente |
| **Unity** | `ProjectSettings/ProjectVersion.txt`, `Assets/`, `*.meta` | Scripts C# (MonoBehaviour, ScriptableObject), escenas, prefabs, escenas de build | Escena/prefab → script por GUID (`m_Script: {guid}` ↔ `.cs.meta`) | Claves de servicios, `PlayerPrefs` con datos sensibles, HTTP sin TLS | Assets con serialización binaria no son legibles; `Library/` se excluye |
| **Unreal Engine** | `*.uproject`, `Source/*.Build.cs`, `Config/*.ini` | Módulos, `UCLASS`/`UFUNCTION`, plugins | Módulo → módulo (`.Build.cs`), includes C++ | Configuración y secretos en `.ini` | **Blueprints (`.uasset`) son binarios: no se analizan** (se declara) |
| **Godot** | `project.godot` | Escenas `.tscn`/`.tres`, scripts `.gd`/`.cs`, autoloads, escena principal | Escena → script (`ext_resource res://`) | Secretos, HTTP sin TLS | Soporte de gramática GDScript a verificar en Fase 4 |
| **Flutter / Dart** | `pubspec.yaml` con `sdk: flutter` | Widgets, rutas (Navigator, go_router), servicios (http, dio), Firebase, providers/bloc | `package:<nombre>/` → `lib/`; subproyectos `android/` e `ios/` con su estrategia | Secretos, HTTP sin TLS, almacenamiento inseguro | — |
| **Escritorio** (Electron, WPF/WinForms, JavaFX/Swing, Qt, Python) | `electron` en dependencias, `.csproj`, FXML, `.ui`, `.pro` | Proceso main/preload/renderer, ventanas, vistas | Canales IPC (`ipcMain`/`ipcRenderer`); XAML ↔ code-behind (`x:Class`); FXML ↔ `fx:controller` | `nodeIntegration`, `contextIsolation: false`, `webSecurity: false`, `shell.openExternal` con entrada no validada | Binarios (`.exe`, `.dll`) no se analizan |

**Niveles de soporte por lenguaje** (se mostrarán en la interfaz para no prometer de más):

| Nivel | Lenguajes | Profundidad |
|-------|-----------|-------------|
| 1 | JavaScript, TypeScript, Python, Java, Kotlin, PHP | Símbolos, imports resueltos, rutas, flujo de datos intraprocedural |
| 2 | C#, Dart, HTML, CSS, SQL, GDScript | Símbolos, imports/referencias, patrones de seguridad |
| 3 | C, C++ | Includes y símbolos principales; las macros limitan la precisión |
| — | Otros | Inventario, conteo de líneas y búsqueda de secretos |

---

## 8. Motor de auditoría de seguridad

### 8.1 Fuentes de hallazgos

1. **Reglas propias de Funcode**: consultas tree-sitter, patrones RE2 y chequeos de configuración estructurada (Manifest, `settings.py`, `.env`, `application.properties`, reglas de Firebase…).
2. **Semgrep CE / Opengrep** con un conjunto de reglas propio, ejecutado con `--metrics=off` y sin descargar configuraciones remotas.
3. **OSV** (offline) para dependencias; **OWASP Dependency-Check** opcional.
4. **gitleaks** opcional como segunda opinión para secretos (siempre con `--redact`).

Los hallazgos de todas las fuentes se normalizan a un único formato y se deduplican (archivo + línea + CWE).

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
| Dependencias vulnerables | Versión exacta contra OSV/NVD | Confirmado (versión exacta en lockfile) / Posible (rango) |
| Librerías desactualizadas | Comparación con la última versión conocida | Confirmado con datos / **No evaluado** sin datos |
| CORS | `*` con credenciales, reflejo del origen | Confirmado |
| Información sensible expuesta | `.env` incluidos, claves privadas, volcados SQL, logs, source maps | Confirmado |
| Sesiones | Cookies sin `HttpOnly`/`Secure`/`SameSite`, tokens en `localStorage` | Probable / Requiere prueba dinámica |
| Falta de control de acceso | Rutas sin middleware de autenticación cuando las demás lo tienen; endpoints administrativos | Posible / Requiere prueba dinámica |

### 8.3 Ficha de cada hallazgo

`id` · nombre · categoría (CWE y OWASP Top 10) · archivo · líneas · severidad · certeza · explicación ·
consecuencias · fragmento problemático (redactado) · recomendación · ejemplo corregido (plantilla por
lenguaje, cuando sea viable) · referencias · fuente (regla propia o herramienta + versión).

### 8.4 Política de enmascarado de secretos

- El secreto se redacta **en el momento de detectarse, dentro del sandbox**: el valor completo nunca llega a la BD, a los informes, a los logs ni a la interfaz.
- Se muestra como máximo el prefijo de 4 caracteres y el sufijo de 2 (`AKIA••••••••••••Q7`), más el tipo y la longitud. Secretos de menos de 12 caracteres: solo tipo y longitud.
- Para poder decir "este secreto sigue presente" al comparar análisis, se guarda una huella HMAC-SHA256 con clave local, no el secreto.
- Todos los fragmentos, el visor de código y los informes pasan por el mismo redactor.

### 8.5 Protección contra evasión

Un proyecto puede intentar ocultar sus problemas. Por eso:

- Semgrep se ejecuta con `--disable-nosem` (ignora comentarios `nosemgrep`).
- Se neutralizan en la copia extraída los archivos que silencian herramientas: `.semgrepignore`, `osv-scanner.toml`, `.gitleaks.toml`, `.gitleaksignore`, supresiones de Dependency-Check.
- Su presencia se informa como hallazgo informativo ("el proyecto incluye configuración que ignora N vulnerabilidades").

### 8.6 Honestidad del resultado

- El informe incluye siempre: *"La ausencia de hallazgos no implica ausencia de vulnerabilidades"*, la cobertura del análisis y una lista de comprobaciones que requieren pruebas dinámicas (DAST, pruebas de penetración, revisión de la configuración de producción).
- Hallazgos en carpetas de pruebas o ejemplos y valores de marcador (`changeme`, `your_api_key`, `xxx`) se rebajan de severidad pero **no se ocultan**.
- Cada regla tiene casos vulnerables y casos seguros en las pruebas para medir falsos positivos.

---

## 9. Seguridad de la propia aplicación

### 9.1 Modelo de amenazas (resumen)

**Atacante:** el autor del comprimido. Controla nombres, rutas, tamaños, estructura y contenido de todos los archivos.

| Objetivo del atacante | Superficie | Controles principales |
|-----------------------|-----------|-----------------------|
| Ejecutar código en el equipo | Descompresores, parsers, herramientas externas | Sin ejecución, sandbox, tmpfs `noexec`, contenedor sin privilegios |
| Escribir o leer fuera del directorio de trabajo | Rutas en el comprimido, enlaces simbólicos | Validación y escritura controlada de rutas |
| Denegación de servicio | ZIP bombs, ReDoS, XML/YAML bombs, archivos enormes | Límites de tamaño, ratio, memoria, tiempo; RE2; parsers seguros |
| Exfiltrar datos | Red | El worker no tiene red |
| XSS en la interfaz o el informe | Nombres y contenido de archivos | Todo se renderiza como texto, CSP estricta, informe sin JavaScript |
| Ocultar vulnerabilidades | Supresiones en el código | §8.5 |
| Envenenar resultados guardados | Worker comprometido | La API valida la salida del worker como no confiable |

### 9.2 Controles por requisito

| Requisito | Implementación |
|-----------|----------------|
| Límites de tamaño | Corte durante la transmisión de la carga; límites por archivo y total; los tamaños declarados en el comprimido **no se creen**: se cuentan los bytes reales al descomprimir. |
| Protección contra ZIP Bombs | Ratio de compresión por entrada y global; número máximo de entradas; detección de entradas solapadas (bombas no recursivas); los comprimidos anidados se registran pero **no se expanden**. |
| Path Traversal en la extracción | Normalización (`\` → `/`, Unicode NFC); rechazo de rutas absolutas, letras de unidad, `..`, NUL, caracteres de control, nombres reservados de Windows y rutas excesivas; verificación final de que la ruta resuelta queda dentro del directorio raíz; apertura con `O_NOFOLLOW`/`O_EXCL`; **nunca** se crean enlaces simbólicos ni duros (se registran como hallazgo). |
| Aislamiento del análisis | Contenedor del worker con `network_mode: none`, raíz de solo lectura, `cap_drop: ALL`, `no-new-privileges`, usuario sin privilegios, perfil seccomp por defecto, límites de memoria, CPU y número de procesos. Un proceso hijo por análisis. |
| Límites de memoria y tiempo | cgroups del contenedor (principal) + `RLIMIT_AS`, `RLIMIT_CPU`, `RLIMIT_FSIZE`, `RLIMIT_NOFILE`, `RLIMIT_NPROC` en el proceso hijo; tiempo máximo total con terminación del grupo de procesos; tiempo máximo por archivo y por herramienta. |
| Prohibición de ejecutar archivos extraídos | Extracción en tmpfs montado con `noexec,nosuid,nodev`; archivos con permisos `0600` sin bit de ejecución; ninguna herramienta de compilación instalada ni invocada; `setup.py` se analiza con `ast`, nunca se ejecuta; en Dependency-Check se desactivan los analizadores que invocan gestores de paquetes o acceden a la red. |
| Protección de archivos temporales | Directorio por trabajo con nombre aleatorio y permisos `0700`, propiedad del usuario del worker. |
| Eliminación segura | En tmpfs (por defecto) los datos están en memoria: al borrar no queda rastro en disco. En modo disco se sobrescribe antes de borrar, **con la advertencia honesta** de que en SSD y sistemas de archivos con *journaling* o copy-on-write la sobrescritura no garantiza el borrado físico. Limpieza de huérfanos al arrancar. El comprimido subido se borra al terminar. |
| Cifrado de información almacenada | AES-256-GCM de resultados y contenidos; clave en un volumen separado al que solo accede la API, permisos `0600`; contraseña opcional (Argon2id) para desbloquear al iniciar; comando de rotación de clave. Advertencia honesta: protege copias y respaldos de los datos, no frente a alguien con acceso a la sesión del usuario mientras la app está desbloqueada. |
| No enviar código a servicios externos | Sin telemetría; Semgrep con `--metrics=off` y reglas locales; worker sin red; el actualizador solo descarga datos públicos; la consulta opcional de versiones solo envía nombre y versión de paquetes y muestra al usuario qué se va a enviar antes de hacerlo. |
| Secretos nunca completos | §8.4 |

Controles adicionales:

- **Parsers**: `defusedxml`, YAML seguro, límites de tamaño y profundidad en JSON, RE2, tiempos máximos en tree-sitter.
- **Interfaz**: React renderiza el contenido del proyecto solo como texto; CodeMirror muestra texto plano; los caracteres de control y de dirección bidireccional (p. ej. U+202E) de los nombres de archivo se muestran escapados; CSP estricta sin scripts en línea.
- **Informes**: Jinja2 con autoescape; el HTML no contiene JavaScript y lleva su propia CSP; WeasyPrint usa un *fetcher* que bloquea cualquier recurso externo o local.
- **API**: escucha solo en `127.0.0.1`; contraseña local opcional con sesión en cookie `HttpOnly`/`SameSite=Strict` y token CSRF; cabeceras de seguridad.
- **Cadena de suministro propia**: dependencias bloqueadas con hashes, `pip-audit` y `npm audit` en CI y, desde la Fase 6, la herramienta se analiza a sí misma en CI.

### 9.3 Límites por defecto (configurables)

| Límite | Valor |
|--------|-------|
| Tamaño del comprimido | 500 MB |
| Tamaño total descomprimido (bytes reales) | 2 GB |
| Número de entradas | 100 000 |
| Tamaño máximo por archivo extraído | 100 MB |
| Tamaño máximo para análisis sintáctico | 2 MB (los mayores reciben solo inventario y búsqueda de secretos) |
| Ratio de compresión por entrada | 250:1 (en entradas de más de 1 MB) |
| Ratio de compresión global | 100:1 |
| Profundidad / longitud de ruta | 64 niveles / 1 024 caracteres |
| Comprimidos anidados | Se registran, no se expanden |
| Memoria del worker | 3 GB |
| Tiempo total del análisis | 20 min |
| Tiempo por archivo (análisis sintáctico) | 5 s |

---

## 10. Informes y métricas

### 10.1 Secciones del informe

Las 15 secciones solicitadas, más dos anexos obligatorios:

1. Resumen ejecutivo · 2. Descripción general · 3. Tecnologías · 4. Arquitectura · 5. Estructura de carpetas ·
6. Archivos más importantes · 7. Relaciones entre archivos · 8. Bases de datos y conexiones · 9. APIs y servicios
externos · 10. Dependencias · 11. Auditoría de seguridad · 12. Problemas encontrados · 13. Recomendaciones ·
14. Nivel general de mantenimiento · 15. Conclusiones ·
**Anexo A – Cobertura y limitaciones del análisis** · **Anexo B – Metadatos** (hash del comprimido, fecha,
versiones de la herramienta, reglas y bases de vulnerabilidades).

- **Resumen ejecutivo y conclusiones**: generados con plantillas deterministas a partir de los datos; cada frase es trazable a un dato del análisis. No se usa IA generativa.
- **Archivos más importantes**: puntos de entrada + centralidad en el grafo (PageRank) + rol (configuración, autenticación, BD) + radio de impacto, con la explicación de por qué cada archivo es importante.

### 10.2 Índice de mantenimiento (0–100)

Indicador **orientativo, no un estándar**, compuesto por sub-índices visibles con su valor y explicación:
complejidad ciclomática · tamaño de archivos y funciones · duplicación · presencia y proporción de pruebas ·
documentación · salud de dependencias (vulnerables, obsoletas, ausencia de lockfile) · herramientas de calidad
(linters, formateadores, CI) · deuda señalada (TODO/FIXME/HACK) · estructura (ciclos de dependencias, archivos
con acoplamiento extremo). La fórmula y los pesos se documentan y se muestran en el informe.

### 10.3 Formatos

| Formato | Características |
|---------|-----------------|
| **HTML** | Un único archivo autocontenido, sin JavaScript, con CSP, secciones plegables (`<details>`), diagramas SVG embebidos |
| **PDF** | Generado desde el mismo HTML con estilos de impresión: portada con la marca Funcode, índice, numeración |
| **JSON** | Datos completos, esquema publicado y versionado (`schema_version`), secretos enmascarados, identificadores estables para comparar |

**Comparación**: diferencias entre dos análisis del mismo proyecto: hallazgos nuevos/resueltos, dependencias
añadidas/eliminadas/actualizadas, tecnologías, archivos añadidos/eliminados/modificados (por hash) y evolución de métricas.

**Extensiones opcionales futuras**: SARIF 2.1.0 (estándar para hallazgos de seguridad, compatible con GitHub
Code Scanning) y SBOM CycloneDX.

---

## 11. Limitaciones técnicas

Conocidas antes de empezar; se documentarán en la aplicación y en cada informe.

1. **Análisis solo estático.** No observa el comportamiento en ejecución: importaciones dinámicas, reflexión, contenedores de inyección de dependencias y rutas construidas en tiempo de ejecución generan relaciones marcadas como *probables* o *no verificadas*, nunca como verificadas.
2. **Flujo de datos limitado.** El análisis de flujo es intraprocedural (dentro de una función). Semgrep CE no hace análisis entre archivos (es una función de su versión de pago). Algunos SQLi/XSS reales que atraviesan varias funciones o archivos pueden pasar desapercibidos.
3. **Binarios no analizados.** APK/AAB/DEX, `.so`, `.dll`, `.exe`, `.jar`, Blueprints de Unreal (`.uasset`) y assets binarios de Unity se inventarían, pero no se decompilan.
4. **Código minificado u ofuscado**: análisis superficial.
5. **Frescura de las vulnerabilidades.** Sin conexión, la detección depende de la fecha de la última actualización de la base local. El informe muestra esa fecha.
6. **Obsolescencia.** Saber si una versión está desactualizada requiere datos de los registros de paquetes (instantánea u opción en línea). Sin ellos: "no evaluado".
7. **Resolución de versiones incompleta sin conexión**: POM padres, BOMs de Maven/Gradle y catálogos externos no incluidos en el ZIP no se pueden resolver; sin lockfile no hay dependencias transitivas.
8. **RAR.** Depende de `unrar` (o 7-Zip), software gratuito redistribuible pero no libre. No se admiten RAR multivolumen. Los RAR con cabeceras cifradas no permiten ver ni siquiera los nombres de los archivos.
9. **Comprimidos con contraseña**: se detectan, no se descifran. Opcionalmente (decisión D6) el usuario podría proporcionar la contraseña, que se mantendría solo en memoria.
10. **Falsos positivos y negativos.** Toda detección basada en patrones los tiene; por eso existen los niveles de certeza.
11. **Validez de secretos.** Se detecta el formato, no si la credencial está activa (comprobarlo exigiría contactar el servicio).
12. **Proyectos muy grandes.** Los grafos de más de unos pocos miles de nodos se muestran agregados por carpeta o módulo; el detalle se obtiene con filtros y foco.
13. **Modo sin Docker**: aislamiento reducido (sin aislamiento de red ni `noexec` garantizados). En Windows sin Docker los límites de recursos son más débiles.
14. **Licencias de terceros.** Las reglas comunitarias de Semgrep (repositorio `semgrep-rules`) se distribuyen bajo la *Semgrep Rules License v1.0*, que restringe su uso en productos o servicios ofrecidos a terceros. Por eso se propone un conjunto de reglas propio de Funcode; las comunitarias quedarían como opción de uso interno, sujeta a revisión legal (ver decisión D2).
15. **El índice de mantenimiento es una heurística**, no una medida objetiva de calidad.

---

## 12. Estrategia de pruebas

| Tipo | Contenido |
|------|-----------|
| **Unitarias** | Validador de rutas (pruebas basadas en propiedades con Hypothesis), límites, redactor de secretos, resolutores, parsers de manifiestos, cada regla de seguridad |
| **Corpus malicioso** | ZIP slip, rutas absolutas, enlaces simbólicos, ZIP bomb clásica y por solapamiento, ZIP cifrado (ZipCrypto y AES), CRC corrupto, archivo truncado, RAR4/RAR5 válidos, RAR con datos cifrados y con cabeceras cifradas, RAR multivolumen, extensión falsa, nombres con caracteres de control/bidi, nombres reservados de Windows, 100 000 entradas vacías. Los RAR de prueba se generan una vez (requiere la herramienta `rar`) y se versionan con su procedencia documentada. |
| **Proyectos de referencia** | Mini proyectos por tipo (Android, React+Express, Django, Laravel, Spring, Flutter, Unity, Godot, Electron) con resultados esperados, **incluidas aserciones negativas** (la tecnología X no debe detectarse; la relación Y debe salir como "no verificada") |
| **Reglas de seguridad** | Cada regla con fixtures vulnerables y seguros; seguimiento de precisión y exhaustividad |
| **Seguridad de la aplicación** | XSS mediante nombres/contenidos en la UI y en el informe HTML; XXE en `AndroidManifest.xml`/`pom.xml`; bomba YAML; cadenas ReDoS; descarga externa bloqueada en PDF; `result.json` manipulado rechazado; terminación del sandbox por memoria y tiempo |
| **Frontend** | Vitest + Testing Library |
| **Extremo a extremo** | Playwright: cargar ZIP → progreso → explorador → seguridad → exportar |
| **Rendimiento** | Proyectos grandes de código abierto (Fase 10) |
| **CI** | ruff, mypy, pytest con cobertura (objetivo ≥ 90 % en `ingest`, `security` y redacción), eslint, tsc, vitest, Playwright, construcción de imágenes Docker, pip-audit, npm audit |

---

## 13. Plan de implementación por fases

Cada fase entrega su parte de backend **y** de interfaz, para que pueda comprobarse de verdad en la aplicación
(la Fase 10 es pulido, no la primera vez que se ve la UI). Al terminar cada fase: pruebas ejecutadas, verificación
funcional, documentación en `docs/fases/FASE-N.md`, lista de errores pendientes y **espera de su aprobación**.

| Fase | Entregables | Pruebas clave | Criterio de aceptación |
|------|-------------|---------------|------------------------|
| **1. Arquitectura y configuración inicial** | Monorepo; backend (configuración, logs, BD + migraciones, cifrado, registro de plugins, modelos de dominio, orquestador, spool + sandbox del worker); frontend (Vite, tema Funcode, layout y navegación entre las 6 pantallas, vacías y marcadas como tales); Docker Compose endurecido; CI; ADRs | Cifrado ida y vuelta; validación de configuración; el sandbox mata procesos que exceden memoria/tiempo; atomicidad del spool | `docker compose up` levanta la app; la UI navega; la API responde `/health`; CI en verde |
| **2. Carga y extracción ZIP/RAR** | Carga por streaming con límites, firma real, manejadores ZIP/RAR, todas las protecciones de §9.2, exclusiones configurables, duplicados, progreso SSE, limpieza segura; pantalla **Nuevo análisis** completa | Corpus malicioso completo | Un ZIP/RAR legítimo se extrae; cada muestra maliciosa se rechaza con un mensaje claro |
| **3. Explorador y estructura** | Inventario, roles de archivo, puntos de entrada, archivos posiblemente innecesarios, almacenamiento cifrado del contenido redactado; pantalla **Explorador** (árbol, visor, ficha) | Clasificación sobre proyectos de referencia | Se navega el árbol y se consulta la ficha de cualquier archivo |
| **4. Lenguajes y tecnologías** | Detección de lenguajes y líneas, motor de reglas YAML, detección de BD y servicios externos, panel de tecnologías con evidencia | Proyectos de referencia con aserciones positivas y negativas | Cada tecnología muestra dónde se encontró y su nivel de confianza |
| **5. Relaciones entre archivos** *(la más grande; se entregará en 5A y 5B)* | 5A: extractores tree-sitter, resolutores de imports, grafo, impacto. 5B: analizadores de framework (rutas, controladores, modelos, layouts Android, GUIDs de Unity, escenas Godot, IPC Electron) y enlace frontend↔backend | Relaciones esperadas por proyecto, incluidas las no verificadas | Cada conexión muestra los 5 atributos y su evidencia |
| **6. Auditoría de seguridad** | Motor de reglas, secretos y enmascarado, auditores de configuración, adaptador Semgrep/Opengrep, protección contra evasión, deduplicación; pantalla **Seguridad** | Fixtures vulnerables y seguros por regla | Fichas completas con certeza y severidad; ningún secreto completo en la salida |
| **7. Dependencias** | Parsers de manifiestos y lockfiles, base OSV offline + actualizador, adaptadores osv-scanner y Dependency-Check (opcional), uso real, criticidad, obsolescencia | Manifiestos de cada ecosistema | Lista de dependencias directas/indirectas con vulnerabilidades y uso |
| **8. Diagramas** | Agregación, vistas por capas/módulos/archivos/flujos en Cytoscape, SVG con Graphviz; pantalla **Arquitectura** | Algoritmos de agregación; renderizado con proyectos grandes | Diagrama legible del flujo Frontend → API → Controlador → Servicio → Modelo → BD cuando existe |
| **9. Informes** | Esquema JSON, HTML, PDF, índice de mantenimiento, resumen ejecutivo, historial, comparación; pantalla **Informes** | Validación del esquema; XSS e intentos de descarga en informes | Los tres formatos se descargan y contienen las 15 secciones + anexos |
| **10. Interfaz final, pruebas y optimización** | Dashboard completo, pulido visual y animaciones, accesibilidad, rendimiento, E2E, revisión de seguridad final, manuales de usuario y de administración | E2E, rendimiento, revisión de seguridad | Proyecto desconocido → diagnóstico completo sin intervención |

---

## 14. Estructura del repositorio

```
Lector-de-archivos/
├── backend/
│   ├── pyproject.toml · uv.lock
│   ├── src/funcode_analyzer/
│   │   ├── api/            routers, esquemas, SSE, seguridad HTTP
│   │   ├── core/           modelos, configuración, plugins, pipeline, cobertura
│   │   ├── storage/        BD, repositorios, cifrado, claves
│   │   ├── jobs/           spool, worker, sandbox, progreso
│   │   ├── ingest/         zip, rar, validación, extracción segura, exclusiones
│   │   ├── inventory/      árbol, hashes, duplicados, roles
│   │   ├── languages/      detección, LOC, gramáticas
│   │   ├── technologies/   motor + rules/*.yaml
│   │   ├── relations/      extractors/, resolvers/, frameworks/, graph.py, impact.py
│   │   ├── security/       rules/, secrets/, redaction.py, adapters/
│   │   ├── dependencies/   parsers/, vulndb/, usage.py
│   │   ├── metrics/        complejidad, duplicación, mantenimiento
│   │   ├── diagrams/       agregación, DOT/SVG
│   │   └── reports/        schema/, templates/, html.py, pdf.py, compare.py
│   └── tests/              unit/, corpus/ (malicioso), projects/ (referencia), security/
├── frontend/
│   ├── package.json · package-lock.json
│   └── src/  pages/, components/, api/ (cliente generado), theme/, i18n/
├── deploy/                 docker-compose.yml, Dockerfiles, perfiles de seguridad
├── docs/                   ARQUITECTURA.md, adr/, fases/, LIMITACIONES.md
└── .github/workflows/      ci.yml
```

---

## 15. Decisiones que requieren su aprobación

Para cada punto se indica la opción recomendada. Si está de acuerdo con todas, basta con responder
**"aprobado"**.

| # | Decisión | Recomendación | Alternativas |
|---|----------|---------------|--------------|
| D1 | Plataforma de entrega | **Aplicación web local con Docker Compose** (máximo aislamiento, funciona en Windows, macOS y Linux) | Aplicación de escritorio (Tauri/Electron, posible como capa posterior sobre la misma API); servidor multiusuario (requiere autenticación completa y PostgreSQL) |
| D2 | ¿Se comercializará la herramienta o se ofrecerá como servicio a terceros? | Reglas de seguridad **propias de Funcode** en cualquier caso; reglas comunitarias de Semgrep solo si el uso es interno | Si va a ser comercial, conviene una revisión legal de las licencias de terceros (Semgrep Rules License, UnRAR) |
| D3 | Política de red | **100 % sin conexión** + actualizador opcional de bases públicas | Permitir además la consulta en línea de últimas versiones (solo nombre y versión de paquetes, con confirmación) |
| D4 | ¿Guardar una copia del código analizado? | **Sí, cifrada y con secretos redactados**, para el visor de código y las comparaciones; desactivable | No guardar código: solo resultados (el visor no estaría disponible tras el análisis) |
| D5 | Idioma de la interfaz | **Español**, con estructura i18n preparada para inglés | Bilingüe desde el inicio |
| D6 | Comprimidos con contraseña | **Detectar y avisar**; opcionalmente permitir que el usuario introduzca la contraseña (solo en memoria) | Solo detectar y rechazar |
| D7 | Protección de acceso a la app | **Contraseña local opcional** (desactivada por defecto en modo `127.0.0.1`) | Siempre obligatoria |
