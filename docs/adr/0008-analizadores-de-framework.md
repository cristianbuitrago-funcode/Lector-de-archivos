# ADR 0008 — Analizadores de framework (Fase 5B)

- **Estado:** aceptada (2026-11-05, Fase 5B)

## Decisiones

1. **Extracción léxica guiada, en la misma pasada.** Los analizadores de framework trabajan sobre el texto ya
   redactado que reciben del recorrido del comprimido (no hay segunda lectura ni árbol sintáctico guardado). Cada
   uno busca primero textos fijos (`@GetMapping`, `Route::`, `axios.`, `R.layout.`…) y solo entonces lee la llamada,
   sus argumentos y sus cadenas con un lector propio (`Src`): paréntesis equilibrados, cadenas con las comillas e
   interpolaciones de cada lenguaje (`${x}`, `$x`, `{x}` de f-strings y C#, `\(x)` de Swift), objetos y listas
   literales. Las partes de una cadena o URL que solo se conocen al ejecutar se marcan y nunca se inventan.
   Se descarta lo que está en comentarios (rangos del análisis de lenguaje) y lo que está dentro de una cadena: cada
   archivo de código se recorre una vez para marcar sus cadenas, también las de varias líneas, sin sus
   interpolaciones (que son código). Las anotaciones, decoradores y atributos además tienen que estar en posición de
   anotación. El SQL es la excepción: se lee cuando la cadena entera es la sentencia. Patrones con RE2/J (tiempo lineal,
   igual en el PC y en Android); nada de `java.util.regex` (ver incidente 10 de la Fase 4).
2. **Hechos primero, enlace después.** El recolector guarda por archivo solo hechos pequeños (rutas, montajes,
   llamadas HTTP, tablas, accesos, claves, inyecciones, recursos, GUID, canales). La etapa de relaciones los enlaza
   con el índice del proyecto de la 5A (archivos, tipos por paquete, importaciones ya resueltas) y crea las conexiones
   con la misma ficha de cinco atributos. Límites: 300 llamadas por archivo y 200 000 hechos en total; al superarlos,
   la cobertura lo dice.
3. **Rutas con sus prefijos.** Los prefijos se componen siguiendo los montajes (`app.use('/api', r)`, `include()`,
   `register_blueprint`, `include_router`, grupos de Laravel y de `MapGroup`), los prefijos de clase (`@RequestMapping`,
   `@Controller`, `[Route]`) y los globales (`setGlobalPrefix`, `server.servlet.context-path`, `UsePathBase`). Un
   enrutador que nadie monta tiene el prefijo **desconocido** y se marca así; sus coincidencias son "no verificadas".
   Next.js, Nuxt y SvelteKit: rutas por la estructura de carpetas cuando el `package.json` declara el framework.
4. **Llamadas HTTP enlazadas con rutas.** La URL se normaliza (sin la consulta, que puede llevar claves; la URL base
   de axios o de Retrofit si se conoce; `localhost`, `10.0.2.2` y nombres sin dominio son el propio backend) y se
   compara por segmentos con las rutas (los parámetros aceptan cualquier valor). Confianza (arquitectura §6.1):
   - **probable**: ruta literal idéntica y mismo método, con el prefijo conocido;
   - **no verificada**: parte de la URL se construye al ejecutar, coincide con varias rutas, el prefijo no se
     conoce, o la ruta existe con otro método (posible error);
   - sin coincidencia: paquete externo "GET /api/x" no verificado, y el informe lo lista como posible error si el
     proyecto tiene backend. Un dominio externo es un servicio externo (la URL está escrita: verificada).
5. **Datos.** Tablas definidas por SQL (`CREATE TABLE`, solo en código y `.sql`), migraciones (Laravel, Knex,
   Sequelize) y modelos de ORM (Django, SQLAlchemy, JPA, Room, Entity Framework, Eloquent, Sequelize, Mongoose,
   TypeORM, Prisma), con el nombre por convención del ORM cuando no está escrito ("probable"). Accesos por SQL escrito
   (solo cadenas con forma de SQL: `SELECT … FROM`, `UPDATE … SET`…) y por los métodos del ORM. Una tabla que no se
   define en el proyecto se registra como externa, no como rota (puede crearse fuera); las colecciones de Firestore y
   MongoDB no tienen definición en el código y se dice.
6. **Configuración sin valores.** Se guardan los **nombres** de las claves definidas (`.env*`, `application.*`,
   `appsettings*.json`, `config/*.php`, `settings.py`, docker-compose, Dockerfile, `buildConfigField`) y leídas
   (`process.env`, `os.environ`, `env()`, `@Value`, `IConfiguration`, `BuildConfig`…). Nunca un valor; la única
   excepción es el prefijo de rutas de Spring (`context-path`), que no es secreto. Una clave que no se define en el
   comprimido es "no verificada" (se configura al desplegar), y las variables estándar del sistema se distinguen.
7. **Inyección de dependencias siempre "probable" como máximo.** Registros explícitos (`AddScoped<I, C>`, `@Binds`,
   `@Provides`, `@Bean`, `bind()` de Laravel, `useClass`, Koin) y, sin ellos, las clases del proyecto que implementan
   el tipo. Con varias candidatas, "no verificada" y se nombran.
8. **Plataformas.** Android: componentes del manifiesto (con el `namespace` de Gradle si el manifiesto no tiene
   `package`), recursos `R.tipo.nombre` y `@tipo/nombre` contra `res/` (calificadores incluidos), ViewBinding, vistas
   propias en layouts, grafos de navegación, Intents explícitos y broadcasts con acción propia. Unity: GUID de los
   `.meta` en escenas, prefabs y `EditorBuildSettings` (si están en texto). Godot: `ext_resource`, escena principal y
   autoloads. Escritorio: XAML (`x:Class`), FXML (`fx:controller`), Qt (`ui_x.h` → `x.ui`). Compilación: CMake (con
   sus variables), qmake, `settings.gradle` y `package.json`. Canales: Electron (incluido `contextBridge`),
   socket.io, Flutter (`MethodChannel`), Tauri y broadcasts de Android, emparejados por el nombre del canal.
9. **Rotas solo cuando el destino debería estar.** Componente del manifiesto en un paquete del proyecto que no
   existe, método inexistente en un controlador de Laravel, `res://` o fuente de CMake escrita tal cual que no está.
   Lo que puede venir de una biblioteca o generarse al compilar es "no verificado", con el motivo.
10. **Impacto específico.** Cada conexión de framework lleva los otros lugares que dependen del mismo destino
    concreto (`affected`): las llamadas que fallan si cambia una ruta, quién más lee una clave, accede a una tabla,
    recibe el mismo tipo o envía por el mismo canal. Esquema de resultados **v6**: tipo `REGISTRATION`, registros
    `endpoint`, `data_entity` y `config_key`, y totales nuevos en el resumen.
11. **Tolerancia a fallos.** Un error de un analizador en un archivo no detiene el análisis: se cuenta y la
    cobertura dice qué analizadores fallaron y en cuántos archivos.

## Consecuencias

- Las conexiones de framework dependen de convenciones y nombres: rara vez son "verificadas" (solo cuando el
  destino está escrito: clase del manifiesto, recurso, GUID, ruta de archivo).
- Lo que se construye al ejecutar (rutas dinámicas, reflexión, URL base desde variables de entorno) se enlaza como
  mucho por coincidencia parcial y se dice.
- No se analizan backends en Go, Ruby o Rust (solo sus llamadas HTTP de Go), ni el enrutado por convención de
  ASP.NET MVC (`{controller}/{action}`).
