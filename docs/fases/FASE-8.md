# Fase 8 — Diagramas

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación:** flujo Frontend → API → Controlador → Servicio → Modelo → BD legible cuando existe.

## Objetivo

Ver la arquitectura del proyecto en el teléfono: en qué **capa** está cada archivo y por qué, cómo se conectan las
capas, los módulos y los proyectos, qué dependencias van **al revés**, el grafo de cualquier archivo con sus vecinos y,
sobre todo, el **recorrido de cada petición** desde la pantalla que la hace hasta la tabla de la base de datos. Sin
ejecutar nada, **sin inventar conexiones** (cada paso es una conexión encontrada por las fases 5A y 5B) y diciendo lo
que no se ha podido seguir. Decisiones en el ADR 0011.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:layout` (nuevo) | **Disposición por capas propia** (tipo Sugiyama), unas 400 líneas sin dependencias: ciclos (aristas invertidas y marcadas), capas fijas o por camino más largo, nodos ficticios para las aristas largas, **reducción de cruces** por baricentro con recuento exacto (árbol de Fenwick), coordenadas sin solapes y aristas como polilíneas; de arriba abajo o de izquierda a derecha. **Determinista** y **acotada** (1000 nodos, 5000 aristas). La usan el motor y la app, y la usará el PDF de la Fase 9. |
| `engine:architecture` (nuevo) | Etapa **`architecture`**: **capa de cada archivo** (Frontend, API, Controlador, Servicio, Modelo, BD, Compartido, Configuración, Pruebas) con certeza y motivo; diagramas de **capas** (con dependencias inversas), **módulos** (con ciclos) y **proyectos** (servicios externos y dependencias principales con su severidad), ya dispuestos; **flujos de información** por cada ruta de la API; resumen con el **patrón aparente** (MVVM, capas, MVC) y notas de lo que falta. |
| `engine:relations`, `engine:audit` | Exponen a las etapas siguientes las conexiones, las entidades de datos y las dependencias resueltas (sin cambiar sus resultados). |
| `engine:api` | Esquema de resultados **v9**: `file_layer`, `diagram`, `information_flow` y `architecture_summary`, con límites y validados en el proceso principal. |
| `app` | **Pantalla Arquitectura** (deja de estar «en construcción»), capa del archivo en el **Explorador** con acceso a su grafo, y panel de arquitectura en el **informe**. |

### La pantalla Arquitectura

| Vista | Contenido |
|-------|-----------|
| **Capas** | Resumen (patrón aparente, archivos con capa clara, dependencias inversas), notas y leyenda, y el diagrama: una fila por capa con su número de archivos y las conexiones entre capas. Al tocar una capa: qué significa, conexiones que salen y llegan, y sus archivos con el motivo de cada uno (se abren en el Explorador). Al tocar una conexión: tipos, certeza y ejemplos con archivo y línea. |
| **Flujos** | Una tarjeta por ruta: **Frontend `api.js:6` → llama por HTTP → API `POST /api/orders` → la atiende → Controlador `orderController.js · ctrl.create` → importa → Servicio `orderService.js` → accede a datos → Modelo `order.js` → BD tabla `orders`**, con la certeza del flujo (la de su paso más débil), si llega a la base de datos y cuántos caminos alternativos hay. Búsqueda por ruta o archivo y filtro «solo los que llegan a la base de datos». |
| **Módulos** | Carpetas del proyecto con sus dependencias; los **ciclos** entre módulos en amarillo. |
| **Proyectos** | Subproyectos (por ejemplo `tienda/client` y `tienda/server`), las llamadas entre ellos, los **servicios externos** (`api.stripe.com`) y sus dependencias directas principales, coloreadas por su vulnerabilidad más grave (Fase 7), con acceso a su ficha en Dependencias. |
| **Archivos** | Se elige un archivo (búsqueda o los más importantes) y se dibuja **su grafo**: a uno o dos saltos, en los dos sentidos, solo lo que usa o solo lo que lo usa, y solo conexiones fuertes. Desde cualquier nodo: abrir en el Explorador o **poner el foco** en él. |

El lienzo admite zoom con dos dedos, desplazamiento, toque para seleccionar y doble toque para ajustar, con botones
de zoom para quien no pueda pellizcar. Línea **continua** = verificada, **discontinua** = probable, **de puntos** = no
verificada; **naranja** = dependencia inversa; **amarillo** = ciclo. Cada capa tiene su color (Modelo y BD con colores
propios para no confundirse con el dorado del controlador ni con los avisos). Todo lo dibujado se repite como una
**lista de botones** que TalkBack puede leer, y la ficha de lo seleccionado es texto normal.

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:layout` (nuevo) | 8 | Una cadena queda en capas sucesivas, alineada y con las aristas pegadas a sus nodos; **la misma entrada da siempre el mismo dibujo**; los cruces evitables desaparecen; un ciclo se dibuja invirtiendo una arista (marcada) sin repetir capas; capas fijas con aristas hacia atrás y dentro de la misma capa; una arista larga pasa por un punto en cada capa intermedia; de izquierda a derecha es la misma disposición traspuesta; grafo mediano sin solapes, con aristas a nodos desconocidos ignoradas y tamaños dentro de los límites. |
| `engine:architecture` (nuevo) | 4 | Con el pipeline real: **Express + React + Sequelize** (capa de cada archivo con su motivo, flujo completo `GET /api/users` de `api.js` a la tabla `users`, la dependencia inversa del modelo al servicio, diagramas válidos); **Django** (urls → vistas → modelos → tabla); **Spring** (controlador, servicio, repositorio y entidad); **las marcas dentro de cadenas no cuentan** y, sin rutas, no se inventa ningún flujo y la nota lo dice. |
| `engine:api` | 35 | Las 33 anteriores y 2 nuevas: los cuatro registros de arquitectura van y vuelven sin cambios; se rechazan diagramas fuera de límites (nodos, aristas, puntos, tamaño) y flujos o totales incoherentes. |
| `app` (Robolectric) | 39 | Las 37 anteriores y 2 nuevas. **Recorrido de la pantalla** con un proyecto Express + React: capas con su patrón, notas y leyenda, ficha de la capa de servicios con `userService.js`; flujos con la cadena completa hasta «tabla users» y el filtro; módulos; proyectos y ficha de `tienda/client`; grafo de `controllers/users.js`, ampliación a dos saltos y foco en `userService.js`. **Del Explorador al grafo**: la ficha de `userService.js` dice «Servicio» y abre su grafo en Arquitectura. |
| Resto | 169 | `engine:vulndb` 8, `engine:dependencies` 15, `engine:audit` 10, `engine:relations` 11, `engine:languages` 25, `engine:pipeline` 6, `engine:technologies` 5, `engine:inventory` 14, `engine:ingest` 36, `engine:security` 10, `engine:core` 10, `data` 18, `analyzer-service` 1. |
| **Total** | **255** | **0 fallos** |

Además: **Android Lint sin errores y con 2 avisos**, los dos iguales: ya existe el Android Gradle Plugin 9.5.1 (salió
después de la Fase 7; el proyecto usa 9.5.0). No afecta al funcionamiento; ver «Errores pendientes». APK de publicación
con R8 correcto y dependencias verificadas en modo estricto (la Fase 8 no añade ninguna dependencia externa).

**Proyecto de demostración** (tienda con cliente React y servidor Express + Sequelize, 23 archivos; el de las
capturas): 23 de 23 archivos con capa clara, patrón «Capas: controlador, servicio y modelo», **4 flujos completos**
(`GET /api/users`, `POST /api/users`, `GET /api/products`, `POST /api/orders`), cada uno Frontend → API → Controlador
→ Servicio → Modelo → BD con su tabla; **1 dependencia inversa** encontrada (`models/order.js:3` usa
`services/audit.js`, Modelo → Servicio); el archivo de pruebas fuera del dibujo con su nota. La etapa tarda 26 ms.

**Prueba con un proyecto real** (este repositorio, 437 archivos): patrón MVVM; 201 archivos con capa (30 Frontend, 9
Controlador, que aquí son los ViewModel, 11 Servicio, 16 Modelo, 48 Configuración y 87 Pruebas) y **236 como
«Compartido»**, que es correcto: el motor de análisis no es una capa web y el informe lo dice; **11 dependencias
inversas**, todas reales (archivos que mezclan un ViewModel con funciones de interfaz, y el servicio en primer plano
que abre la actividad desde su notificación); **ningún flujo**, porque la app no tiene rutas HTTP, y la nota lo dice
en lugar de inventarlos. Diagramas: capas (6 nodos), módulos (25) y proyectos (41). La etapa tarda 99 ms y el
análisis completo 4,8 s en el PC.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Nuevo en esta fase: la prueba del análisis completo en el **proceso
aislado** comprueba también la capa de los archivos, que los tres diagramas pasan la validación y el flujo de la ruta
del proyecto de prueba (`web/util.js` → `web/server.js`).

- **Ejecución n.º 33** (la app de la fase): compilación, pruebas JVM y Lint correctos; **14 de 14 correctas en
  Android 8.0 y 16**, con las comprobaciones nuevas de arquitectura; en **Android 15** pasaron las 4 de análisis
  completo (con las nuevas), las de aislamiento, cifrado y migración, y falló la **prueba de extremo a extremo de la
  Fase 1**, la misma que falló una vez en la Fase 7. Esta vez se buscó la causa en el registro del emulador (ver
  incidente 8) y se corrigió.
- **Ejecución n.º 32** (solo el motor): falló una prueba JVM **de la Fase 7** del actualizador; se buscó la causa y
  era un fallo real de la app (ver incidente 9), corregido.
- **Ejecución n.º 35** (la fase completa con las correcciones): _pendiente de completar_.

## Capturas

Capas con el resumen y el diagrama; ficha de la capa Servicio; ficha de la capa Modelo con la conexión inversa;
notas y leyenda; flujos completos con el paso por el que se llega a cada uno; módulos; proyectos con un servicio
externo y la ficha de una dependencia; grafo de `orderController.js` a dos saltos; panel de arquitectura del
informe; capa de un archivo en el Explorador.

## Incidentes y cómo se resolvieron

1. **«CREATE TABLE» en comentarios de documentación** convertía archivos de este repositorio en capa BD. Las marcas
   del código se buscan ahora fuera de cadenas y comentarios (hay una prueba con marcas dentro de cadenas).
2. **En una app Android, `engine/api/` salía como capa API** y la carpeta `client/` como Frontend. Las convenciones de
   API y las carpetas `client/`, `web/`, `public/` y `static/` solo cuentan si el proyecto tiene rutas.
3. **Los recursos XML salían como «Compartido»** y los archivos de servidor de proyectos que no son web, como API.
   Solo se clasifican los diseños de pantalla (`res/layout*`) y el papel de servidor solo cuenta en proyectos web.
4. **El cliente HTTP del frontend (`api.js`) salía como API** por su nombre. Un archivo que llama por HTTP a las rutas
   del propio proyecto es Frontend.
5. **Aristas opuestas superpuestas** (A → B y B → A se tapaban) y **nodo seleccionado fuera de la vista** al elegirlo
   en la lista: las aristas opuestas se separan y el lienzo se centra en lo seleccionado.
6. **Textos poco claros en las capturas**: «Las 1 pruebas» (ahora concuerda) y los pasos del flujo sin decir por qué
   se pasa de uno a otro (ahora «llama por HTTP», «la atiende», «importa», «accede a datos»…).
7. **Colores confundibles**: Controlador y Modelo eran casi del mismo dorado, y Modelo y BD coincidían con el amarillo
   de los ciclos y el naranja de las dependencias inversas. Modelo es ahora violeta y BD rosa.
8. **Prueba de extremo a extremo intermitente en Android 15** (Fase 1, ya vista en la Fase 7). El registro del
   emulador mostró la causa: tras escribir la contraseña, una petición de mostrar el teclado que quedaba pendiente
   lo volvía a abrir justo después de cerrarlo, y la pulsación de «Crear acceso» se perdía mientras se movía. La
   prueba espera ahora a que el teclado siga cerrado un segundo y, si la sesión no se abre, vuelve a pulsar (el botón
   está desactivado mientras se crea el acceso, así que una pulsación de más no hace nada). No era un fallo de la app.
9. **Actualizador de la base de vulnerabilidades (Fase 7): reintentar justo después de un error no hacía nada.**
   Publicaba «falló» antes de borrar sus temporales y no admitía otra actualización hasta terminar de borrarlos; en
   un equipo lento la prueba lo detectó. Ahora el resultado se publica cuando ya ha terminado todo, y la prueba exige
   que cada reintento empiece.
10. **Prueba de la navegación intermitente en el PC**: comprobaba el aviso «sin análisis» de Arquitectura nada más
    abrirla, pero ese aviso llega cuando responde la consulta a la base de datos cifrada, en segundo plano. La prueba
    espera ahora a la respuesta (igual que ya hacía en otros pasos), también en Seguridad.

## Limitaciones conocidas (honestidad del resultado)

- **Los diagramas y los flujos dependen de las relaciones de las fases 5A y 5B.** Lo que esas fases no ven (llamadas
  por reflexión, rutas construidas en tiempo de ejecución, inyección por configuración externa, frameworks sin
  analizador) tampoco aparece aquí, y los flujos se quedan incompletos en lugar de suponerse.
- **Un flujo muestra un camino**: el primer llamador y el servicio y el modelo más directos; los demás se cuentan
  como «caminos alternativos», pero no se dibujan.
- **La capa por convención es probable**: un archivo mal nombrado o con varias funciones puede caer en otra capa. La
  ficha dice siempre el motivo para que se pueda juzgar; un archivo con varias funciones aparece en la principal.
- **El patrón es solo orientativo** y se basa en lo que se ve (ViewModel, controladores, servicios, modelos); si no
  encaja ninguno, no se nombra.
- **Límites de legibilidad**: 30 módulos (el resto en «otros»), 40 proyectos, 8 servicios externos, 6 dependencias por
  proyecto y 60 archivos en el grafo con foco; cada recorte se dice en el diagrama. Los proyectos de más de 60 000
  archivos de código solo reciben capa en los primeros.
- **Las pruebas no se dibujan** en el diagrama de capas (dependen de casi todo), aunque cuentan en el resumen.

## Errores pendientes

Ninguno conocido en esta fase. La prueba intermitente de la Fase 1 y el fallo del actualizador de la Fase 7 quedan
corregidos (incidentes 8 y 9); se comprueba en la ejecución n.º 35 de la CI (ver «En emulador»).

Pendiente de decidir: **actualizar el Android Gradle Plugin de 9.5.0 a 9.5.1** (los 2 avisos de Lint). Es una versión
de corrección, pero cambia la herramienta de compilación y obliga a regenerar la verificación de dependencias, así
que se propone hacerlo al empezar la Fase 9, como cambio aparte.
