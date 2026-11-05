# Fase 6 — Auditoría de seguridad

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación:** fichas completas con certeza y severidad; ningún secreto completo en la salida.

## Objetivo

Revisar el proyecto en busca de problemas de seguridad **sin ejecutarlo**: secretos, inyecciones, configuraciones
inseguras, permisos, autenticación, sesiones, CORS, CSRF, control de acceso y supresiones de herramientas. Cada
hallazgo con su ficha completa, su certeza honesta (lo que no se puede saber sin ejecutar se dice) y sin mostrar nunca
un secreto completo. Las dependencias vulnerables y las librerías desactualizadas son de la Fase 7 y se indican como
no evaluadas.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:security` | **Catálogo de 61 reglas** (`FC-…`, con versión) con los textos de cada ficha: nombre, categoría, CWE, OWASP Top 10 2021, severidad base, explicación, consecuencias, recomendación, ejemplo corregido por lenguaje y referencias. El **redactor** calcula ahora la **huella HMAC-SHA256** de cada secreto con la clave local, marca los valores de ejemplo (`changeme`, `your_api_key`, el ejemplo de AWS, la contraseña pública `android` de los almacenes de depuración) y oculta también las **claves de firma escritas como argumento** (`jwt.sign(x, '…')`, `HMAC256("…")`, `cookieParser('…')`). |
| `engine:audit` (nuevo) | Detectores en la misma pasada del comprimido, sobre el texto redactado (ADR 0009). **Secretos**: claves privadas, credenciales de proveedores (AWS, GitHub, GitLab, Google, Slack, Stripe, SendGrid, OpenAI, Anthropic, Firebase), contraseñas, cadenas de conexión, JWT y cuentas de servicio. **Inyecciones con recorrido del dato** en JavaScript/TypeScript, Python, PHP, Java/Kotlin, C# y Go: SQL, comandos, `eval`, deserialización, plantillas, rutas de archivo, inclusión, SSRF, redirecciones y XSS (DOM, respuesta del servidor y plantillas sin escape). **Web y sesiones**: hash débil de contraseñas, JWT sin verificar, secretos de firma, aleatorios no criptográficos, TLS desactivado, CORS, cookies, tokens en `localStorage`, CSRF, asignación masiva, validación y datos sensibles en registros. **Configuración**: manifiesto de Android (copia de seguridad, depuración, tráfico sin cifrar, componentes exportados, permisos peligrosos y si se usan), seguridad de red, Info.plist, Django, Spring (Actuator, consola H2), ASP.NET, PHP, Docker, GitHub Actions, reglas de Firebase, seguridad por filas de Supabase, variables públicas del frontend y plantillas. **Plataformas**: WebView y Electron. **Archivos sensibles**: `.env` con valores, almacenes de claves, bases de datos y volcados, mapas de fuente, registros y copias. **Control de acceso** de las rutas de la 5B. **Supresiones** informadas sin respetarlas. Etapa `security`: deduplicación, rebajas, resumen y cobertura. |
| `engine:api` | Esquema de resultados **v7**: registros `finding` y `security_summary`, validados en el motor y en el proceso principal (texto obligatorio, fragmento con su línea y como máximo 12 líneas, máscara con 6 caracteres visibles como mucho, huella de 32 caracteres hexadecimales, recorrido de 10 pasos, totales coherentes). Opción `security.fingerprintKey` en la configuración, que nunca se imprime. |
| `app` | **Clave local de huellas**: 32 bytes de `SecureRandom`, creada una vez y guardada solo en la base de datos cifrada. **Pantalla Seguridad** (sustituye a la marcada como "en construcción"): aviso *"La ausencia de hallazgos no implica ausencia de vulnerabilidades"*, totales, filtros por severidad, certeza y categoría, lista de hallazgos y **ficha completa**, comprobaciones que requieren prueba dinámica, lo no evaluado y la cobertura; en pantallas anchas, lista y ficha lado a lado. **Explorador**: icono de severidad en el árbol, panel Seguridad en la ficha del archivo y marcas en las líneas del visor. **Informe**: resumen de la auditoría con los hallazgos más graves. |

### La ficha de cada hallazgo (§8.3)

| Campo | De dónde sale |
|-------|---------------|
| Nombre, regla y versión, categoría, CWE, OWASP | catálogo |
| Ubicación | archivo y línea (o "todo el proyecto") |
| Severidad | la de la regla, ajustada por el caso (por ejemplo, una clave de Google vale "media") y **rebajada con el motivo** en pruebas, ejemplos, configuración de desarrollo y valores de marcador |
| Certeza | **confirmado** (se ve escrito), **probable** (recorrido desde una entrada del usuario), **posible** (patrón sin recorrido), **requiere prueba dinámica** (la protección puede estar en otra capa) |
| Qué ocurre | frase concreta del caso y explicación de la regla |
| Fragmento | líneas de la copia ya redactada, con la del hallazgo marcada; una clave de firma escrita se oculta también aquí |
| Recorrido del dato | cada paso con su línea: "«id» recibe un dato de la petición (req.params)" → "llega a la consulta SQL" |
| Recomendación y ejemplo corregido | por lenguaje cuando hay uno |
| Referencias | CWE, OWASP y hojas de referencia de OWASP (solo texto: la app no abre enlaces) |
| Secreto | tipo, máscara (4 + 2 caracteres como máximo) y huella local |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:audit` | 8 | Proyectos de prueba con casos **vulnerables y seguros** por familia, con el pipeline real. **Inyecciones**: SQL por concatenación, plantilla y f-string con su recorrido línea a línea; comando, ruta de archivo, SSRF, redirección y respuesta HTML desde `req.query`; `eval` de un parámetro sin origen (posible); parámetro de ruta de Flask; `@RequestParam` de Spring; `include` y `echo` de PHP. **Seguros**: consultas con parámetros, número convertido con `parseInt`, constante, `execFile` con lista, `RegExp.exec`, `htmlspecialchars`, `require __DIR__`, `prepareStatement` con `?`. **Secretos**: ninguno completo en todo el flujo serializado, misma huella en dos archivos, ejemplo de AWS informativo, rebaja en pruebas, clave de cliente de Firebase, almacenes de claves (el de depuración informativo). **Configuración**: manifiesto (solo el componente exportado sin permiso, no el lanzador ni el servicio con permiso), permiso usado y no usado, WebView, seguridad de red, Docker (la imagen con `USER` no cuenta), GitHub Actions (pasar el dato por `env` no cuenta), Firebase, Supabase, Django, Spring y su configuración de desarrollo rebajada. **Web**: CORS con credenciales, cookies, TLS, asignación masiva, registro de una contraseña (un texto fijo no cuenta), aleatorio para un código (barajar no cuenta), XSS desde `location.hash`, token en `localStorage` (el tema no cuenta), `DOMPurify`, CSRF de Spring, MD5 de una contraseña (el de un archivo no cuenta) y **patrones dentro de cadenas que no cuentan**. **Control de acceso**: ruta de administración sin protección, ruta que rompe el patrón de su archivo, `@PreAuthorize` en la clase. **Supresiones** y **resumen** (lo no evaluado, Ruby sin reglas de código). Además, las 61 reglas tienen su ficha completa. |
| `engine:security` | 10 | Las 8 anteriores y 2 nuevas: claves de firma escritas como argumento (y un texto que solo describe la llamada no cuenta), huella estable con la misma clave y distinta con otra, valores de ejemplo y la contraseña `android`. |
| `engine:api` | 33 | Las anteriores con el esquema v7 y 3 nuevas: los registros de seguridad van y vuelven sin cambios; se rechazan fichas incompletas, fragmentos sin línea o demasiado largos, máscaras que muestran demasiado, huellas mal formadas y totales incoherentes; la clave de huellas se valida y no aparece al imprimir la configuración. |
| `app` (Robolectric) | 30 | Las anteriores más el **recorrido de la pantalla Seguridad**: la clave de huellas se crea una vez; aviso, totales, filtro por certeza, ficha con regla, CWE, OWASP, ubicación, fragmento, recorrido, recomendación y ejemplo; ficha del secreto con su huella y **sin el secreto ni sus primeros 12 caracteres en ninguna parte de la pantalla**; apertura del archivo en el Explorador con su panel Seguridad y la línea marcada en el visor. |
| Resto | 136 | `engine:relations` 11, `engine:languages` 25, `engine:pipeline` 6, `engine:technologies` 5, `engine:dependencies` 10, `engine:inventory` 14, `engine:ingest` 36, `engine:core` 10, `data` 18, `analyzer-service` 1. |
| **Total** | **217** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto y dependencias verificadas en modo
estricto (la Fase 6 no añade ninguna dependencia externa).

**Prueba con un proyecto real** (este repositorio, que contiene las propias reglas y sus pruebas: 436 archivos, 408
de texto examinados; la etapa de seguridad tarda 21 ms): **21 hallazgos, ninguno falso fuera de las pruebas**.
- Fuera de las pruebas, 3, todos correctos e informativos: el almacén de depuración `keystore/debug.keystore` y sus
  dos contraseñas `android` en `app/build.gradle.kts` (valores públicos y conocidos).
- En las pruebas, 18 secretos de ejemplo de las pruebas del redactor y de la ingesta, todos con la severidad rebajada
  y el motivo ("archivo de pruebas").
- El catálogo de reglas, los detectores y sus listas de patrones no se detectan a sí mismos (están dentro de cadenas).

**Proyecto de demostración vulnerable** (Express, Flask, PHP, Spring, Android, Docker, GitHub Actions, Firebase y
Supabase; el de las capturas): 69 hallazgos en 16 categorías, cada uno donde se esperaba; los casos seguros del mismo
proyecto no dan ninguno; ningún secreto aparece completo en la salida del motor ni en la pantalla.

**Rendimiento** (proyecto sintético de 3 000 archivos, medición alternada sin y con auditoría): la lectura pasa de
31,5–33,5 s a 32,6–36,4 s en el PC (unos 2–3 s, dentro del ruido de la medición) y la etapa final tarda 15–25 ms. La
primera versión tardaba 19 s más: se corrigió con filtros por archivo y por línea y con la búsqueda de variables en el
texto en lugar de línea a línea (incidente 3).

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Además de las de fases anteriores, `FullAnalysisInstrumentedTest`
comprueba en el proceso aislado que la auditoría se ejecuta: el resumen de seguridad llega con sus totales, la clave
escrita en `src/Config.kt` llega como hallazgo en su línea, enmascarada y con la huella de la clave local de la app, y
ningún registro contiene el secreto completo.

**Resultado: 13 de 13 correctas en las tres versiones** (ejecución n.º 25 del CI: 3 de aislamiento, 3 de cifrado,
3 de migración, 3 de análisis completo y 1 recorrido de extremo a extremo), y el trabajo de compilación, pruebas JVM
y Lint también correcto. La ejecución n.º 23 (solo el motor) también fue correcta; la n.º 24 se canceló sola al
empezar la n.º 25, que contiene el mismo código más este informe.

## Capturas

Pantalla Seguridad con el proyecto de demostración vulnerable: lista con el aviso, los totales y los filtros; ficha
de una inyección SQL con su fragmento y recorrido; ficha de un secreto enmascarado con su huella; comprobaciones que
requieren prueba dinámica; panel Seguridad en la ficha del archivo y líneas marcadas en el visor.

## Incidentes y cómo se resolvieron

1. **Las reglas se detectaban a sí mismas.** Al analizar este repositorio, las listas de patrones del detector
   (`"rejectUnauthorized:false"`, `"csrf().disable()"`…) daban hallazgos. Ahora la evidencia tiene que empezar en
   código, no dentro de una cadena ni de un comentario; el redactor tampoco toma por clave de firma un texto que solo
   describe la llamada.
2. **`com/demo` se tomaba por código de ejemplo.** La rebaja por "ejemplos" solo mira las carpetas cercanas a la raíz.
3. **Coste inicial de 19 s** en el proyecto sintético: cada `fetch(…)` buscaba su variable 80 líneas hacia atrás,
   línea a línea. Ahora un punto que solo cuenta con datos del usuario no se examina si el archivo no tiene ninguna
   entrada, la búsqueda salta a las líneas donde aparece el nombre y los detectores de una línea se filtran por
   archivo y por línea.
4. **GitHub rechazó el envío** porque una prueba contenía una clave de Stripe falsa con formato real. Las claves de
   prueba se construyen al ejecutar (`"sk_" + "live_" + …`): el código fuente no contiene ninguna.

## Limitaciones conocidas (honestidad del resultado)

- **Flujo intraprocedural aproximado**: no sigue datos entre funciones ni archivos; lo que pasa por otra función es
  "posible", nunca "probable". Sin árbol sintáctico: construcciones muy dinámicas pueden escapar.
- **Control de acceso**: solo protecciones visibles junto a la ruta, en su archivo o en su controlador. Una
  protección global en otro sitio (pasarela, proxy) no se puede asociar: por eso nunca pasa de "posible" y la
  pantalla pide comprobarlo con la app en marcha.
- **Secretos**: por formato de proveedor y por nombre; un valor aleatorio sin nombre ni formato conocido no se
  detecta (no hay medida de entropía). La validez de las claves no se comprueba (no se contacta ningún servicio).
- **Lenguajes sin reglas de código** (Ruby, Rust, Swift, C, C++, Dart…): solo secretos, configuración y supresiones;
  el resumen lo dice.
- **Fase 7**: dependencias con vulnerabilidades conocidas (OSV sin conexión) y librerías desactualizadas.

## Errores pendientes

Ninguno conocido.
