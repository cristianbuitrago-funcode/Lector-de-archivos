# ADR 0005 — Inventario, puntos de entrada y copia redactada del código

- **Estado:** aceptada (2026-11-03, Fase 3)

## Decisiones

1. **Una sola pasada por el comprimido.** El contenido de cada archivo de texto se examina en el mismo recorrido de
   la ingesta (los RAR sólidos solo se pueden leer una vez y en orden). La ingesta ofrece un punto de extensión
   (`FileContentConsumer`) que recibe cada archivo con su contenido en memoria, hasta 2 MB por archivo. El contenido
   no se conserva después: lo que se necesita más tarde (manifiestos, paquetes declarados, señales) se resume.
2. **Certeza honesta.** Cada función, punto de entrada o subproyecto lleva su evidencia (archivo, línea y motivo
   redactado por el motor, nunca fragmentos del código) y un nivel:
   - *confirmado*: nombre de archivo estándar, declaración en un manifiesto o tipo comprobado al leerlo;
   - *probable*: convenciones de nombre o ubicación y **patrones de texto**, aunque sean muy característicos
     (`if __name__ == "__main__"`), porque sin análisis sintáctico (fases 4–5) pueden estar en una cadena;
   - *posible*: archivos posiblemente innecesarios, que la herramienta nunca da por sobrantes.
   Lo que un manifiesto declara y no está en el comprimido se informa **sin ruta** ("puede venir de una
   biblioteca o generarse al compilar"), en lugar de inventar un archivo.
3. **Lector XML propio en lugar de `XmlPullParser`.** El motor es JVM puro y debe comportarse igual en el PC y en
   Android, donde los analizadores XML de la plataforma no admiten las mismas opciones de seguridad. El lector propio
   salta el DTD sin interpretarlo y solo resuelve las cinco entidades predefinidas y las numéricas: no puede haber
   XXE ni expansión exponencial por construcción. Tiene límites de profundidad, atributos y eventos.
4. **JSON con comprobación previa de profundidad** (máximo 64) antes de analizarlo con kotlinx.serialization, que
   admite comentarios y comas finales.
5. **RE2/J** (expresiones regulares de tiempo lineal) para todo patrón sobre contenido del proyecto.
6. **Redactor de secretos ya en la Fase 3** (módulo `engine:security`, que crecerá en la Fase 6). La copia del código
   no puede guardarse sin él (§8.4). Reconoce formatos conocidos de credenciales y valores asignados a nombres
   sensibles por **palabras completas** (`dbPassword` sí; `bypass`, `passwordHint`, `apiKeyHeaderName` no). La máscara
   ocupa lo mismo que el secreto, así que las líneas y columnas no cambian. Solo muestra 4 + 2 caracteres ASCII
   visibles, y el proceso principal **vuelve a comprobarlo** al validar cada registro.
   - Un valor con espacios se considera texto natural (etiquetas de interfaz como "Contraseña actual") y no se oculta;
     tampoco las etiquetas típicas ("Contraseña", "Password"). Consecuencia aceptada: una contraseña con espacios
     asignada en el código no se detecta por asignación.
7. **Copia del código cifrada con clave efímera hasta guardarse.** Mientras el análisis termina (y si la sesión
   está bloqueada, hasta que se desbloquea) la copia redactada se guarda en un archivo cifrado con AES-256-GCM y una
   clave que solo existe en memoria; cada fragmento lleva su número de orden en el vector de inicialización, así que
   cualquier alteración, reordenación o truncado se detecta. Al guardarse en la BD cifrada se borran el archivo y la
   clave (**borrado criptográfico**). A diferencia de la copia del comprimido (ADR 0004), aquí sí es posible porque
   la escribe y la lee la propia app. El proceso principal también impone el tamaño máximo de la copia.
8. **Copia desactivable** en Ajustes (decisión D4). Sin copia se siguen señalando los posibles secretos (sin su valor).
9. **No se guarda copia** de archivos generados (bloqueos de dependencias, código minificado); su función sí se registra.

## Consecuencias

- El esquema de resultados pasa a la versión 3 y la BD a la versión 3 (tabla `file_contents`, migración automática).
- Los análisis anteriores se pueden abrir en el Explorador, pero sin funciones ni copia: la app lo explica.
- El resaltado de sintaxis precalculado en el proceso aislado (E12) llega con tree-sitter (fases 4–5).
