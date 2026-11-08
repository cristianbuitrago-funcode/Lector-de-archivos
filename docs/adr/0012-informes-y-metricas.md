# ADR 0012 — Informes, métricas e índice de mantenimiento (Fase 9)

- **Estado:** aceptada (2026-11-08, Fase 9)

## Contexto

La Fase 9 cierra el diagnóstico: un informe con las 15 secciones y los dos anexos de la arquitectura (§10.1), en PDF,
HTML y JSON (§10.3), un índice de mantenimiento orientativo con sub-índices visibles (§10.2), el historial y la
comparación entre análisis, y la pantalla **Informes**. Las reglas de siempre siguen valiendo: nada del código sale del
teléfono sin una acción del usuario, ningún secreto aparece completo, no se inventa nada y lo que no se pudo medir se
dice.

## Decisiones

1. **Complejidad ciclomática en el árbol sintáctico.** Para cada lenguaje con gramática se piden a tree-sitter tres
   grupos de nodos: las **funciones** (con su extensión completa), los **puntos de decisión** (condiciones, bucles,
   casos, capturas, ternarios) y los **operadores lógicos** (`&&`, `||`, `and`, `or`, `??`). Cada decisión cuenta para
   la función más interior que la contiene, así que una función anidada (una lambda) se mide aparte; la rama por
   defecto (`default:`, `else ->`, `_ =>`) no abre un camino nuevo y no cuenta. Se mide en JavaScript, TypeScript,
   Python, Java, Kotlin, PHP, C#, Dart, GDScript, C y C++; en el resto se dice «no medido». Los operadores son símbolos
   anónimos de la gramática y el puente nativo solo los busca cuando se piden con el prefijo `=`: así un tipo con
   nombre que hoy no existe no puede empezar a coincidir con una palabra clave y cambiar otros resultados. El nombre de
   cada función sale de su campo `name`, de su cabecera, de la variable a la que se asigna (`const Usuarios = () => …`)
   o, en Dart, de la firma que precede al cuerpo.

2. **Etapa `metrics` en el proceso aislado** (`engine:metrics`). Recoge del texto ya redactado de cada archivo de
   código, en el mismo recorrido del comprimido: líneas, funciones, marcas de deuda y huellas para buscar código
   repetido; y combina después la cobertura de pruebas (función «pruebas» del inventario), la documentación (comentarios
   y README), las herramientas de calidad, la estructura (relaciones de las fases 5A y 5B) y la salud de las
   dependencias (Fase 7). Los archivos generados (minificados, de bloqueo) no cuentan y las pruebas se cuentan aparte.
   - **Código repetido:** líneas normalizadas (sin espacios sobrantes, sin comentarios, sin líneas triviales ni
     importaciones) en ventanas de 6, con una huella de 64 bits y una tabla de direccionamiento abierto con un máximo
     de 500 000 ventanas; si un proyecto la llena, el resto se compara con lo ya visto y el resumen lo dice.
   - **Deuda señalada:** `TODO`, `FIXME`, `HACK` y `XXX` solo cuando se usan para señalarla (al principio del
     comentario o seguidas de `:` o `(`); una documentación que menciona la palabra no es deuda.
   - **Herramientas de calidad:** por el nombre del archivo de configuración (ESLint, detekt, Ruff, Android Lint,
     Prettier, EditorConfig, mypy, GitHub Actions, GitLab CI…) y por lo que activan `package.json`, `pyproject.toml`,
     `build.gradle` y similares.
   - **Estructura:** ciclos de dependencias entre archivos (componentes fuertemente conexos, Tarjan iterativo) y
     acoplamiento extremo (archivos que dependen de 20 o más).

3. **Índice de mantenimiento: nueve sub-índices con su regla a la vista.** Complejidad 20 %, tamaño 10 %, duplicación
   15 %, pruebas 15 %, documentación 10 %, salud de dependencias 10 %, herramientas 5 %, deuda 5 % y estructura 10 %.
   Cada uno guarda lo medido en palabras y la regla que lo convierte en un valor de 0 a 100. **Lo que no se puede
   evaluar no cuenta** (sin funciones medidas, sin base de vulnerabilidades, sin relaciones) y los pesos de los demás se
   reparten en proporción; si lo evaluable pesa menos del 50 %, el índice lo advierte. Niveles: 85 o más alto, 70 bueno,
   50 medio, 30 bajo, menos muy bajo. Es un indicador **orientativo**, no un estándar, y el informe lo dice junto a la
   fórmula. Se calcula y se guarda con el análisis: si la fórmula cambia en una versión futura, el historial no cambia.

4. **El informe se construye en `engine:report`, sin Android**, a partir de los registros ya validados: un modelo de
   documento (secciones y bloques: párrafos, frases trazables, datos, tablas, listas, avisos, barras, indicador y
   diagramas) que se dibuja igual en HTML, en PDF y en la pantalla. El resumen ejecutivo, los problemas, las
   recomendaciones y las conclusiones salen de **plantillas deterministas**: cada frase se basa en un dato y cita la
   sección donde está (`§11`, `Anexo A`); no se usa IA generativa. La CLI genera los mismos informes en el PC
   (`--informe salida.html|json`), lo que permite probarlos con proyectos reales.

5. **HTML propio en lugar de kotlinx.html.** La arquitectura preveía kotlinx.html; el informe es pequeño y todo el
   texto pasa por una sola función de escape, así que se evita añadir una dependencia. El archivo es **autocontenido y
   sin JavaScript**, con una política de seguridad de contenidos (`default-src 'none'`) que solo admite su propia hoja
   de estilos por su **huella SHA-256**. Por eso no hay ningún atributo `style`: las barras, el indicador y los
   diagramas son SVG en línea con atributos de presentación. Las secciones se pliegan con `<details>`, las rutas
   largas pueden partirse tras cada `/` y hay estilos para modo oscuro e impresión.

6. **JSON con esquema propio** (`funcode-report/1`) y la versión del esquema de registros (`result_schema`): metadatos
   (incluida la huella SHA-256 del comprimido), el resumen, las recomendaciones, las conclusiones y **todos los
   registros agrupados por tipo**, tal como los validó el proceso principal (secretos enmascarados) y sin la copia del
   código. Las rutas, las reglas y las huellas sirven de identificadores estables entre análisis.

7. **PDF nativo con `PdfDocument`**: texto y diagramas vectoriales dibujados con el lienzo de Android, en A4, con
   portada de Funcode, índice con la página de cada sección, «Página X de Y» y cabeceras de tabla repetidas al cambiar
   de página. Las columnas se reparten midiendo el texto: cada una tiene al menos el ancho de su palabra más larga
   (una ruta muy larga sí se parte) y el resto del ancho va a las que más lo necesitan. Se compone en **dos pasadas** (la primera solo mide). Como la JVM de pruebas no tiene `PdfDocument`, el
   PDF se comprueba en el emulador con `PdfRenderer` (páginas, tamaño, portada) y la CI adjunta el PDF generado.

8. **Exportar sin permisos y compartir solo por acción explícita.** Guardar usa el selector de documentos de Android
   (`CreateDocument`): el usuario elige dónde y la app no pide permisos de almacenamiento. Compartir escribe una copia
   temporal en una carpeta de la caché que es lo único que expone un `FileProvider` (solo lectura y con permiso
   temporal para la app elegida); se borra antes de cada copia nueva, al arrancar y al borrar los datos. La pantalla
   avisa de que lo exportado ya no está cifrado.

9. **Comparación entre análisis.** Hallazgos por regla, archivo y huella (la línea puede moverse; se cuentan
   repeticiones), dependencias por ecosistema, nombre y proyecto (cambio de versión = actualizada), tecnologías por
   nombre, archivos por su huella SHA-256 y la evolución de las cifras (índice y sub-índices, hallazgos,
   vulnerabilidades, complejidad, duplicación). Si los análisis parecen de proyectos distintos, se advierte.

10. **El resultado de un análisis guardado viene de su estado.** El historial guarda el resultado de la sesión en el
    estado del análisis, no como registro; al reconstruir el informe se recupera de ahí, para que el informe diga lo
    mismo que el historial.

## Consecuencias

- La complejidad y la duplicación dependen de lo que se pudo leer: los lenguajes sin gramática no se miden y el
  código con errores de sintaxis puede quedar medido a medias (los dos casos se dicen).
- El índice compara bien análisis del mismo proyecto; entre proyectos distintos (otros lenguajes, otro tamaño) es solo
  una referencia.
- El código de terceros copiado en el repositorio (no instalado con un gestor de paquetes) cuenta como propio: no hay
  forma fiable de distinguirlo sin que lo indique el proyecto.
- El PDF y el HTML de un análisis antiguo (anterior a la Fase 9) se generan igual, sin las secciones de métricas, que
  dicen que no se analizaron.
