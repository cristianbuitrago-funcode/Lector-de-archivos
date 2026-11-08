# ADR 0007 — Relaciones entre archivos (Fase 5A)

- **Estado:** aceptada (2026-11-04, Fase 5A)

## Decisiones

1. **Símbolos en la misma pasada de tree-sitter.** El puente JNI de la Fase 4 ya devolvía los nodos pedidos; ahora
   cada nodo lleva también el rango de su nombre (campo `name` de la gramática, con
   `ts_language_field_id_for_name` y `ts_node_child_by_field_id`). Las importaciones, las declaraciones de nivel
   superior, sus supertipos, el paquete y los bloques `#if`/`#ifdef` se obtienen del mismo `int[]` que el recuento de
   líneas: no hay un segundo análisis ni se conserva ningún árbol. Lo que el árbol no da directamente (cabeceras de
   clase, `interface`/`enum class` en Kotlin) se lee del texto con operaciones de cadena, no con expresiones
   regulares de Java (ICU en Android, ver incidente 10 de la Fase 4).
2. **Respaldo léxico de declaraciones.** Las gramáticas fallan con algunas construcciones válidas (en Kotlin,
   `var open by …`) y lo que viene después del error se pierde o queda mal anidado. Si un archivo tiene errores de
   sintaxis, se descartan las declaraciones con sangría (salvo en C#, C y C++, que se sangran dentro de su espacio de
   nombres) y se añaden las de un lector por líneas (RE2/J, sin repeticiones acotadas grandes).
3. **Un resolutor por ecosistema**, con las reglas de cada uno (rutas relativas y `index`, `tsconfig`, monorepos,
   módulos de Node; paquetes y raíces de Python; paquetes de la JVM, clases anidadas, `.aidl`, clases generadas;
   PSR-4; espacios de nombres de C#; `package:` de Dart; cabeceras de C/C++; `res://` de Godot; recursos de HTML y
   CSS). La configuración que cambia la resolución (`tsconfig`/`jsconfig`, `package.json`, `composer.json`,
   `pubspec.yaml`, `project.godot`) se lee en el mismo recorrido. Las dependencias declaradas de la Fase 4 deciden si
   un paquete externo es "verificado" o "no verificado".
4. **Escala de confianza** (arquitectura §6.1):
   - **verificada**: resolución sintáctica a un archivo que existe, o a un paquete declarado o de la plataforma;
   - **probable**: heurística con evidencia fuerte (uso por nombre en el mismo paquete, alias `@/` sin tsconfig,
     `import paquete.*` con el tipo usado, ruta que solo coincide cambiando mayúsculas, cabecera única encontrada en
     otra carpeta);
   - **no verificada**: no se puede comprobar (paquete no declarado, clase generada al compilar, inclusión
     condicional, cabecera de una biblioteca ausente, varios candidatos);
   - **rota**: el destino debería estar en el proyecto y no está. Nunca se marca rota una referencia a algo externo.
5. **Uso sin importación en el mismo paquete** (Java, Kotlin, C#, PHP). Los nombres usados fuera de los comentarios
   se guardan como un conjunto ordenado de huellas (`hashCode`), no como texto; por cada paquete hay un índice de
   huella → declaraciones. Para evitar falsos positivos y tiempos cuadráticos: un nombre declarado en más de 8
   archivos se descarta por ambiguo, las funciones y constantes necesitan 4 letras o más, cada script `.kts` es su
   propio paquete y el presupuesto total es de 6 millones de caracteres. El resultado es siempre "probable".
6. **Impacto** de cada conexión, calculado sobre el grafo: símbolos importados que el destino declara (dejarían de
   estar definidos), dependientes directos y transitivos del destino (radio de impacto) y puntos de entrada desde los
   que se llega al origen. Para un paquete externo, cuántos archivos lo usan; para una conexión rota, el origen y sus
   dependientes.
7. **Algoritmos del grafo iterativos** (las pilas de los hilos de Android son pequeñas): Tarjan sin recursión para
   los componentes y ciclos, PageRank (30 iteraciones, amortiguación 0,85) para la importancia, dependientes
   transitivos con conjuntos de bits sobre el grafo de componentes (exacto hasta 6 000 componentes; por encima, BFS
   acotada a 2 000 y marcada como "o más") y BFS desde un máximo de 100 puntos de entrada. Probado con una cadena de
   200 000 archivos.
8. **"Sin referencias encontradas" solo cuando debería haberlas.** Se señala un archivo de código sin conexiones de
   entrada si su lenguaje resuelve importaciones y no es punto de entrada, prueba, configuración, compilación,
   documentación, `__init__.py`, `index`/`main`, migración, ni una clase que un framework registra por su cuenta
   (actividades, controladores, `MonoBehaviour`…). En C/C++ solo las cabeceras. Y **nunca** si a algún archivo de su
   ecosistema le faltan las importaciones (tree-sitter no disponible o tiempo agotado): la cobertura lo explica.
9. **Esquema de resultados v5** (sin cambio de BD: los registros se guardan como hasta ahora). Registros nuevos:
   `relation` (con sus cinco atributos), `file_graph`, `dependency_cycle` y `relations_summary`. Límites: 60 000
   conexiones (rotas e internas primero), 300 símbolos por archivo, 200 ciclos con hasta 100 rutas cada uno, 50
   símbolos intercambiados por conexión y 3 puntos de entrada por impacto.

## Consecuencias

- Las relaciones de lenguaje son exactas cuando la resolución es sintáctica; las de uso por nombre son probables y
  lo dicen.
- La 5B (frameworks) se añade como resolutores y tipos de conexión nuevos sobre el mismo grafo y la misma ficha.
- Sin tree-sitter no hay relaciones de lenguaje, pero el análisis no afirma nada falso: no hay "sin referencias" y
  la cobertura dice qué falta.
