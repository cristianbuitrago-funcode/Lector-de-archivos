# Fase 5B — Relaciones de framework

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación de la Fase 5:** cada conexión muestra sus 5 atributos y su evidencia. Con la 5B se cumple
  también para las conexiones propias de cada framework.

## Objetivo

Encontrar las conexiones que no son importaciones: qué ruta del backend atiende cada llamada del frontend, qué
controlador atiende cada ruta, qué código lee o escribe cada tabla, de dónde sale cada clave de configuración, qué
implementación recibe cada inyección de dependencias, y cómo se enlazan manifiestos, recursos, escenas, módulos de
compilación y canales entre procesos. Sin ejecutar nada, sin inventar conexiones y sin mostrar nunca un valor de
configuración.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:relations` | **Analizadores de framework** (`framework/`), en la misma pasada del comprimido y sobre el texto ya redactado, con un lector de código propio que entiende paréntesis, cadenas e interpolaciones de cada lenguaje y descarta comentarios, cadenas y textos que solo parecen código. **Rutas**: Express, Fastify, Koa, Hono, NestJS, Next.js, Nuxt, SvelteKit, Django (con Django REST framework), Flask, FastAPI, Laravel (grupos y `resource`), Spring, JAX-RS y ASP.NET Core (atributos y API mínimas); enrutadores de React, Vue, Angular, Flutter y go_router. **Llamadas HTTP**: fetch, axios (instancias con URL base), HttpClient de Angular, jQuery, SWR, Nuxt, requests, httpx, http y Dio de Flutter, Retrofit (con su `baseUrl`), OkHttp, Ktor, RestTemplate, WebClient, Volley, HttpClient de .NET, UnityWebRequest, Http de Laravel, Guzzle, cURL, GDScript, Go, Alamofire y formularios HTML; con método, ruta y campos enviados. **Datos**: tablas y colecciones de SQL, migraciones y diez ORM, y sus lecturas y escrituras. **Configuración**: claves de `.env`, Spring, .NET, Laravel, Django, docker-compose, Dockerfile y Gradle, y sus lecturas en cada lenguaje. **Inyección**: Spring, Hilt/Dagger, Koin, NestJS, Angular, ASP.NET Core, Laravel y FastAPI. **Plataformas**: Android, Unity, Godot, XAML, FXML, Qt, CMake, qmake, Gradle y package.json. **Canales**: Electron, socket.io, Flutter, Tauri y broadcasts de Android. **Enlazador** que compone los prefijos de las rutas, compara las llamadas con las rutas, resuelve clases, recursos y GUID, y calcula los lugares afectados de cada conexión. |
| `engine:api` | Esquema de resultados **v6**: tipo de conexión **Registro en el framework**; registros **ruta** (`endpoint`, con su controlador y sus llamadas), **tabla o colección** (`data_entity`) y **clave de configuración** (`config_key`, solo el nombre); en el impacto, los **otros lugares afectados** por el mismo destino; nuevos totales en el resumen. Todo validado en el proceso principal. |
| `app` | **Informe → Rutas, datos y configuración**: rutas del backend con lo que las atiende y cuántas llamadas reciben (al tocarlas se abre el controlador), páginas del frontend, **llamadas sin ruta en el backend** (posibles errores), tablas y colecciones con su definición y operaciones, y claves que se leen sin definirse en el comprimido. **Ficha del archivo**: rutas que declara. **Ficha de la conexión**: tipos nuevos, destinos externos con su naturaleza («Fuera del proyecto: tabla stats») e **impacto propio de cada tipo** con los lugares afectados (por ejemplo, las llamadas del frontend que fallan si cambia una ruta). |

### Los cinco atributos en las conexiones de framework

| Tipo | Origen → destino | Información intercambiada | Impacto | Confianza |
|------|------------------|---------------------------|---------|-----------|
| Ruta → controlador o página | archivo de rutas → controlador o componente | `GET /api/users → UserController@index` | qué llamadas del proyecto fallan si cambia | verificada si el controlador se resuelve por su importación; rota si el método no existe |
| Llamada HTTP | archivo del cliente → controlador que atiende la ruta | `POST /api/users {name, email}` | la petición falla si la ruta cambia; quién más la llama | probable si la ruta es literal e idéntica |
| Acceso a datos | código → archivo que define la tabla o el modelo | `tabla users: lectura, escritura` | el acceso falla si cambia la tabla; quién más la usa | verificada si se escribe el nombre o el modelo se importa; probable por convención del ORM |
| Lectura de configuración | código → archivo que define la clave | el **nombre** de la clave, nunca su valor | lee un valor vacío o falla si desaparece; quién más la lee | verificada si se define; no verificada si se configura al desplegar |
| Inyección | consumidor → implementación | el tipo inyectado | el framework no puede crear el origen | probable con una candidata; no verificada con varias |
| Registro | manifiesto, montaje de rutas, CMake, escena principal… → archivo registrado | la clase o el archivo | el registro queda roto | verificada; rota si debería existir y no está |
| Recurso | código, layout, escena → recurso | `R.string.x`, `@drawable/y`, script por GUID | el origen deja de encontrarlo | verificada; no verificada si puede venir de una biblioteca |
| Comunicación entre procesos | quien envía → quien atiende | el canal o el Intent | el mensaje se pierde | verificada (Intent explícito); probable (mismo nombre de canal) |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:relations` | 11 | Las 6 de la 5A y 5 nuevas con proyectos de prueba de conexiones conocidas. **JavaScript**: Express con montaje de rutas y controladores, React Router, axios con URL base, fetch con plantilla, campos del cuerpo, método distinto (posible error), llamada sin ruta, servicio externo sin la consulta, Sequelize, `.env`, variables sin definir y ningún valor en los registros. **Python**: Django con `include` y Django REST framework, ORM, `settings`; FastAPI con `include_router`, `Depends` y SQLAlchemy; Flask con blueprints; cliente con URL base variable (coincidencia parcial). **JVM y Android**: Spring con `context-path`, inyección por la interfaz, Spring Data y `@Value`; Retrofit con su `baseUrl` (10.0.2.2) contra Spring; manifiesto con el `namespace` de Gradle y una actividad que no existe (rota); recursos, `string-array`, ViewBinding, vista propia en el layout; Intent explícito; broadcast hasta la clase del receptor; `BuildConfig`; Hilt con `@Binds`; Room. **Resto**: Laravel con grupos, `apiResource` y un método inexistente (roto), Eloquent, `DB::table`, `config/` y `.env`; ASP.NET Core con atributos, `MapGroup`, `AddScoped`, Entity Framework y `appsettings.json`; Next.js (pages y app router) con SWR; NestJS con `setGlobalPrefix` e inyección; Unity (GUID de script, `EditorBuildSettings`, `Resources.Load`, `LoadScene`, GUID que falta en la cobertura); Godot (`ext_resource`, recurso que falta, escena principal, autoload); Electron (IPC en los dos sentidos, `contextBridge`, canal sin nadie que lo escuche); Flutter ↔ Android; Qt y CMake (con variables y fuente que falta); WPF. **Trampas**: anotaciones y SQL dentro de cadenas, un proyecto de ejemplo entero dentro de una cadena de varias líneas, mensajes como "Update available", documentación y esquemas JSON no generan ninguna conexión, ruta, llamada ni clave. |
| `engine:api` | 30 | Las anteriores con el esquema v6, incluidos los registros nuevos (van y vuelven sin cambios y son válidos) y los incoherentes que se rechazan (método desconocido, ruta sin barra, línea 0, definición incompleta, recuentos negativos o menores que sus muestras). |
| `app` (Robolectric) | 29 | Las anteriores más el **recorrido de la 5B**: el informe muestra la ruta con su controlador y su llamada, la llamada sin ruta, la tabla y la clave sin definir; la ruta abre el controlador, y la conexión HTTP muestra sus cinco atributos con el impacto propio de su tipo. |
| Resto | 133 | `engine:languages` 25, `engine:pipeline` 6, `engine:technologies` 5, `engine:dependencies` 10, `engine:inventory` 14, `engine:ingest` 36, `engine:core` 10, `engine:security` 8, `data` 18, `analyzer-service` 1. |
| **Total** | **203** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto y dependencias verificadas en modo
estricto (la 5B no añade ninguna dependencia externa).

**Prueba con un proyecto real** (este repositorio, una app de Android con módulos nativos: 413 archivos, 333 en el
grafo; análisis completo en unos 2,9 s en el PC, la etapa de relaciones en 0,1 s): **1 364 conexiones, 0 rotas**. De
framework:
- **70 registros verificados**: las 4 clases del manifiesto (aplicación, actividad y los dos servicios), los 16
  módulos de `settings.gradle.kts` y las 50 fuentes de los dos `CMakeLists.txt` (48 de UnRAR siguiendo
  `list(TRANSFORM … PREPEND)`).
- **23 usos de recursos** (`R.string`, `R.array`, `R.drawable`, `@color`…) enlazados con `res/`, 2 Intents explícitos
  y las 5 tablas de Room con sus consultas.
- 1 sola conexión de framework no verificada, y con motivo: una consulta WMI de UnRAR (`SELECT * FROM
  Win32_OperatingSystem`) a una tabla que no es del proyecto.
- Ninguna ruta, llamada HTTP ni clave inventada: los proyectos de ejemplo que contienen las propias pruebas de la 5B
  (dentro de cadenas de varias líneas) no cuentan.

**Proyecto de demostración** (Express + React + Sequelize + `.env`, el de las capturas): 6 rutas con su prefijo y su
controlador, 2 páginas, 4 llamadas enlazadas (probables), `GET /api/stats` señalada como llamada sin ruta, Stripe
como servicio externo, 2 tablas y 4 claves (3 definidas en `.env.example`, 1 sin definir).

**Rendimiento**: con los analizadores de framework, la lectura del proyecto sintético de 3 000 archivos y 65 MB
tarda lo mismo que antes de la 5B (unos 22 s en el PC) y la etapa de relaciones menos de 1 s; con el de 3 000
archivos interconectados, 14 s y 1 s.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Además de las de fases anteriores, `FullAnalysisInstrumentedTest`
comprueba en el proceso aislado que la ruta `GET /api/ping` de Express se registra con su archivo y su llamada, y que
la petición desde el cliente se enlaza con el servidor como probable.

**Resultado: 13 de 13 correctas en las tres versiones** (ejecución n.º 21 del CI: 3 de aislamiento, 3 de cifrado,
3 de migración, 3 de análisis completo y 1 recorrido de extremo a extremo), y el trabajo de compilación, pruebas JVM
y Lint también correcto. A la primera, sin incidentes en los emuladores.

### Errores encontrados y corregidos durante la fase

1. **Rutas y entidades inventadas a partir de textos.** En este repositorio, los propios analizadores contienen
   textos como `"@GetMapping"` y `"@Entity"`: salían dos rutas de Spring y una entidad de Room que no existen.
   Ahora una anotación, un decorador o un atributo solo cuenta en su posición (principio de línea, tras otra
   anotación o un paréntesis), nunca dentro de una cadena; lo mismo para `R.layout.x`.
2. **48 fuentes de CMake "rotas".** El `CMakeLists.txt` de UnRAR declara las fuentes en `set()` y les añade la
   carpeta con `list(TRANSFORM … PREPEND ${UNRAR_DIR}/)`. Ahora se siguen las variables de `set` y `list(APPEND |
   TRANSFORM … PREPEND)`, y una ruta construida con variables que no se encuentra es "no verificada", no rota.
3. **Una tabla llamada «IF».** El esquema exportado por Room (`schemas/…json`) contiene `CREATE TABLE IF NOT EXISTS
   \`${TABLE_NAME}\``. Ahora el SQL solo se busca en código y archivos `.sql`, y un nombre calculado al ejecutar no
   es una tabla.
4. **`R.array.x` no se encontraba**: los `<string-array>` se leían como `<string>` (orden de las alternativas del
   patrón).
5. Al preparar las pruebas, antes de ejecutarlas: los montajes de FastAPI y Flask en archivos sin decoradores, el
   acceso `db.query(Item)`, `builder.Configuration["Jwt:Key"]`, el `setGlobalPrefix` de NestJS en `main.ts`, los
   GUID de `EditorBuildSettings.asset` y el broadcast que llegaba al manifiesto en vez de a la clase del receptor.
6. **Proyectos de ejemplo dentro de cadenas.** Al analizar este repositorio con las pruebas de la 5B ya escritas,
   salían 6 rutas, 3 llamadas HTTP y varias claves de los proyectos de prueba que esas pruebas contienen en cadenas
   de varias líneas. Ahora cada archivo de código se recorre una vez para marcar sus cadenas (con sus
   interpolaciones, que sí son código) y ningún analizador toma por código lo que está dentro de una; el SQL de
   `"CREATE TABLE …"` se sigue leyendo porque es la cadena entera. Prueba nueva.
7. **Rendimiento y memoria.** En el proyecto de 65 MB, los analizadores añadían unos 5 s (búsqueda de SQL sin
   distinguir mayúsculas y ligaduras de importaciones calculadas sin necesidad) y guardaban 300 000 llamadas HTTP.
   Ahora el SQL se busca en una sola pasada de caracteres, las ligaduras solo se calculan donde hacen falta y hay
   límites (300 llamadas por archivo, 200 000 hechos en total) que la cobertura avisa al alcanzarse.

## Cómo probarlo en tu teléfono

1. Instala **`app-arm64-v8a-debug.apk`** encima del anterior (los análisis anteriores no tienen relaciones de
   framework hasta que los vuelvas a analizar).
2. Analiza un proyecto con backend y frontend (o una app de Android). En el informe, mira **Rutas, datos y
   configuración**: cada ruta con lo que la atiende y cuántas llamadas recibe.
3. Revisa **Llamadas HTTP sin ruta en el backend analizado**: cada una es un posible error (o una API que no está
   en el comprimido).
4. Toca una ruta: se abre el controlador. En su ficha, **Lo usan** muestra las llamadas del frontend; toca una para
   ver sus cinco atributos y su impacto.
5. Comprueba que en **Configuración** solo aparecen nombres de claves, nunca valores.
6. En una app de Android, abre `AndroidManifest.xml`: **Usa** lista cada actividad, servicio y receptor; una clase
   declarada que no existe sale como rota.

## Diferencias respecto a la arquitectura v0.2

- Los extractores de framework no usan consultas de tree-sitter: un lector léxico guiado por textos fijos, con
  comentarios y cadenas descartados (ADR 0008). Es más rápido en el dispositivo y cubre también lenguajes sin
  gramática (Swift, Go, archivos de configuración).
- El tipo "llamada" (de función a función) sigue sin calcularse: las conexiones son entre archivos, con lo que se
  intercambia.
- Tipo nuevo **Registro en el framework** para lo que un archivo de configuración o del framework registra
  (manifiesto, montaje de rutas, CMake, autoload).

## Errores y limitaciones pendientes

1. **Rutas y URL construidas al ejecutar** (prefijos desde variables de entorno, rutas generadas): coincidencia
   parcial como mucho, o ninguna. Sin el prefijo con el que se monta un enrutador, sus rutas salen con "prefijo
   desconocido".
2. **No cubierto**: backends en Go, Ruby (Rails) o Rust; enrutado por convención de ASP.NET MVC; GraphQL y gRPC;
   WebSocket fuera de socket.io; colas de mensajes.
3. **ORM**: los nombres de tabla por convención (pluralización inglesa sencilla) pueden no coincidir con la tabla
   real si se cambia la convención; la conexión es "probable".
4. **Unity**: escenas y prefabs guardados en binario no se pueden leer (la cobertura los cuenta); los GUID de
   paquetes no están en el comprimido.
5. **Inyección**: los registros por escaneo de ensamblados, por configuración externa o por reflexión no se ven.
6. Varias llamadas del mismo archivo al mismo destino forman **una** conexión: la confianza es la de la primera y
   las demás quedan como evidencias (cada una con su motivo, también los posibles errores).
7. Pendientes de fases anteriores: biometría probada solo con simulación, APK de publicación sin firma propia,
   contraseñas en `String` dentro de Compose, detekt/ktlint sin configurar, sin pantalla de licencias.

## Siguiente fase

**Fase 6 — Auditoría de seguridad**: reglas, secretos y enmascarado, auditores de configuración, protección contra
evasión; pantalla **Seguridad**. Las rutas, llamadas, datos y configuración de esta fase son la base de varias reglas
(rutas sin autenticación, SQL construido con datos de la petición, claves en el código).
