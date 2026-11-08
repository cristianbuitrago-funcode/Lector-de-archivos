# ADR 0011 — Diagramas de arquitectura: capas, disposición y flujos de información (Fase 8)

- **Estado:** aceptada (2026-11-07, Fase 8)

## Contexto

Las fases 5A y 5B encuentran las conexiones entre archivos (importaciones, llamadas, rutas, llamadas HTTP, accesos a
datos, inyección…), cada una con su certeza y su evidencia. La Fase 8 tiene que convertirlas en **diagramas
legibles en un teléfono**: capas, módulos, proyectos y el grafo de un archivo, y el **flujo Frontend → API →
Controlador → Servicio → Modelo → BD** cuando existe. Restricciones: no ejecutar nada, **no inventar conexiones**,
decir lo que no se puede saber, y que todo lo que sale del proceso aislado pase por la validación del proceso
principal (tramas de 1 MiB como máximo).

## Decisiones

1. **Disposición por capas propia (`engine:layout`)**, del tipo Sugiyama, en Kotlin puro y sin dependencias.
   Se descartaron Graphviz (C nativo: otra biblioteca del NDK con su superficie de ataque, para algo que solo
   necesita aritmética) y ELK (Java, pero arrastra EMF y varios megabytes, pensado para escritorio). La propia son
   unas 400 líneas auditables, **deterministas** (el mismo proyecto da siempre el mismo dibujo) y **acotadas**
   (1000 nodos, 5000 aristas y 6000 nodos ficticios como máximo; si se superan, se rechaza en lugar de colgarse).
   Pasos:
   - **Ciclos**: búsqueda en profundidad; las aristas de retroceso se invierten para dibujar y se marcan
     (`reversed`), de modo que la flecha sigue apuntando en su sentido real.
   - **Capas**: fijas cuando el diagrama las impone (el de capas de arquitectura), o por **camino más largo** con
     los nodos sin entradas bajados junto a sus sucesores.
   - **Nodos ficticios** en las aristas que cruzan varias capas, para que se dibujen como polilíneas sin atravesar
     nodos.
   - **Reducción de cruces** con barridos de baricentro (12), contando los cruces con un árbol de Fenwick y
     quedándose con el mejor orden visto.
   - **Coordenadas**: media de la colocación empujada a la izquierda y a la derecha (8 refinamientos), sin solapes.
     El tamaño final incluye los puntos de las aristas.
   La misma biblioteca sirve para la pantalla y servirá para el PDF y el SVG de la Fase 9, así que los tres
   formatos mostrarán el mismo dibujo.

2. **Capa de cada archivo con certeza y motivo** (`engine:architecture`, `LayerClassifier`). Capas: Frontend, API,
   Controlador, Servicio, Modelo, BD, Compartido, Configuración y Pruebas. La evidencia se aplica en este orden y la
   primera que encaja decide:
   1. Archivos de **pruebas** → Pruebas; de **configuración o compilación** → Configuración (salvo que el código
      tenga una marca de capa).
   2. **Marcas en el código** → certeza *confirmada*: `@RestController`, `[ApiController]`, `(APIView)`, `@Service`,
      `@Injectable(`, `@Entity`, `models.Model`, `mongoose.Schema`, `sequelize.define(`, `CREATE TABLE`,
      `Schema::create(`, `@Composable`, `from 'react'`, ViewModel… Se buscan **fuera de cadenas y comentarios**
      (un `CREATE TABLE` en un comentario de documentación no cuenta) y, si hay varias, gana la más interior
      (BD > Modelo > Controlador > Servicio > Frontend).
   3. Lo que encontraron las fases anteriores: archivos que **definen tablas o entidades** → Modelo; que **definen
      rutas de una API** → API; que **atienden** esas rutas → Controlador; **páginas** → Frontend.
   4. **Convenciones** de carpetas y nombres (`controllers/`, `services/`, `models/`, `repositories/`, `components/`,
      `userService.js`…) → *probable*. Las de API y las carpetas `client/`, `web/`, `public/` o `static/` solo
      cuentan si el proyecto tiene rutas: en una app Android, `engine/api/` no es una API web.
   5. Funciones del inventario (Fase 3) y archivos que **hacen llamadas HTTP** → Frontend.
   6. Lo demás → **Compartido**, y el resumen dice cuántos archivos quedaron así.
   Los recursos XML solo se clasifican si son diseños de pantalla (`res/layout*`).

3. **Los diagramas se calculan en el proceso aislado y viajan ya dispuestos.** Cada `diagram` lleva sus nodos con
   coordenadas y sus aristas como polilíneas; la app solo dibuja. Tres vistas:
   - **Capas**: una fila fija por capa (de Frontend arriba a BD abajo), con el número de archivos y las conexiones
     agregadas entre capas. Las pruebas no se dibujan (dependen de casi todo) y el diagrama lo dice.
   - **Módulos**: carpetas con una profundidad que se adapta al proyecto, como mucho 30 nodos (el resto en
     «otros», con nota); las conexiones que forman **ciclos** entre módulos se marcan.
   - **Proyectos**: subproyectos (como mucho 40), los **servicios externos** a los que llaman (8 servidores como
     máximo) y hasta 6 dependencias directas por proyecto, primero las vulnerables (con su severidad, de la Fase 7)
     y luego las más usadas.
   El **grafo de un archivo** (foco a uno o dos saltos, por sentido y solo conexiones fuertes) se calcula **en el
   teléfono** con la misma biblioteca cuando se pide, como mucho con 60 nodos: precalcularlo para todos los archivos
   multiplicaría el tamaño del resultado sin necesidad.

4. **Dependencias inversas.** Una conexión de código (importación, llamada, inyección, herencia…) desde una capa
   interior hacia una exterior según el orden Frontend → API → Controlador → Servicio → Modelo → BD (por ejemplo, un
   modelo que importa un servicio) se marca como **inversa**. No se trata como error: puede ser intencionada, y la
   nota lo dice. Las conexiones de configuración, compilación o recursos no cuentan.

5. **Flujos de información: cada paso es una conexión encontrada.** Para cada ruta de API (como mucho 500):
   quien la llama (la primera llamada HTTP encontrada desde el frontend) → la ruta → el archivo que la atiende
   (`ROUTE_HANDLER`) → un servicio al que este se conecta directamente → el modelo (preferentemente por un acceso a
   datos, que lleva la tabla; si no, por una conexión a un archivo de la capa Modelo) → la BD (la tabla de ese
   acceso, la que define el modelo o la base externa a la que se conecta). **Si falta una conexión, el flujo se
   corta ahí** y se muestra como incompleto; nunca se rellena un paso por convención. La certeza del flujo es la de
   su paso más débil, y se cuenta cuántos **caminos alternativos** hay (otros llamadores, otros servicios). Las
   rutas que no se conectan con nada no se muestran, y el resumen dice cuántas son.

6. **Esquema de resultados v9** con cuatro registros nuevos, validados en el proceso principal: `file_layer`
   (capa, certeza y motivo), `diagram` (como mucho 120 nodos, 300 aristas, 48 puntos por arista, 3 ejemplos por
   arista y 100 000 unidades de tamaño), `information_flow` (10 pasos como mucho) y `architecture_summary` (archivos
   por capa, inversas con ejemplos, flujos completos, patrón aparente y hasta 20 notas). El motor recorta antes de
   llegar a esos límites y lo anota.

7. **Patrón aparente**, solo como orientación y con lo que se ve: MVVM si hay ViewModel; «Capas: controlador,
   servicio y modelo» si están las tres; MVC si hay controladores y modelos. Si no encaja nada, no se nombra ninguno.

8. **Lienzo propio con Compose Canvas.** Sin WebView ni bibliotecas de JavaScript (la app no carga contenido web) y
   sin dependencias nuevas. Zoom con dos dedos (0,15× a 4×), desplazamiento, toque para seleccionar nodos y aristas,
   doble toque para ajustar; línea continua para conexiones verificadas, discontinua para probables y de puntos
   para el resto; inversas en naranja y ciclos en amarillo; aristas opuestas separadas para que no se tapen.
   **Accesibilidad**: los elementos del diagrama se repiten como una lista de botones que TalkBack puede leer, y la
   ficha de lo seleccionado es texto normal.

## Consecuencias

- El diagrama de capas y los flujos dependen de la calidad de las relaciones de las fases 5A y 5B: lo que esas fases
  no ven (llamadas por reflexión, rutas construidas en tiempo de ejecución, inyección por configuración externa)
  tampoco aparece aquí, y los flujos se quedan incompletos en lugar de suponerse.
- La capa por convención es *probable*: un archivo mal nombrado puede caer en una capa equivocada. La ficha de cada
  archivo dice el motivo para que se pueda juzgar.
- Un proyecto muy grande (más de 60 000 archivos de código) solo recibe capa en los primeros, y el resumen lo dice.
- El PDF y el SVG de la Fase 9 podrán reutilizar las coordenadas guardadas sin volver a calcular nada.
