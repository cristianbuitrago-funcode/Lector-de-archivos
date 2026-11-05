# ADR 0009 — Auditoría de seguridad estática (Fase 6)

- **Estado:** aceptada (2026-11-05, Fase 6)

## Decisiones

1. **Módulo propio, en la misma pasada.** La auditoría vive en `engine:audit` (depende de `engine:relations` para el
   lector de código y las rutas, y de `engine:security` para el catálogo y el redactor). Un recolector recibe el texto
   ya redactado de cada archivo durante el único recorrido del comprimido (no hay segunda lectura) y la etapa
   `security`, que se ejecuta después de las relaciones, añade lo que depende del proyecto entero, elimina duplicados
   y emite las fichas. Si la etapa de relaciones falla, la auditoría sigue y la cobertura dice que no se comprobó el
   control de acceso de las rutas.
2. **Reglas en código, con su ficha completa.** El catálogo (`SecurityRules`, 61 reglas `FC-…` con versión) tiene los
   textos de cada ficha (§8.3): nombre, categoría, CWE, OWASP Top 10 2021, severidad base, explicación, consecuencias,
   recomendación, ejemplo corregido por lenguaje y referencias. La detección está en `engine:audit`, separada por
   familias (secretos, inyecciones, web y sesiones, configuración, plataformas, control de acceso, supresiones). Se
   descartó un formato declarativo de reglas: los detectores necesitan leer argumentos, seguir variables y combinar
   señales de varios archivos, y un lenguaje de reglas propio sería otro intérprete que proteger.
3. **Flujo de datos aproximado y certeza honesta.** No hay árbol sintético ni grafo de llamadas: se usa el lector
   léxico de la 5B (paréntesis, cadenas, interpolaciones, comentarios) y un seguimiento intraprocedural hacia atrás:
   - el valor que llega al punto peligroso se descompone en texto fijo y partes variables (concatenación, `+`, `.` de
     PHP, `%` de Python, interpolación, `format`, `sprintf`);
   - una parte es **entrada del usuario** si la contiene (`req.query`, `request.args`, `$_GET`, `getParameter`,
     `Request.Query`, `r.FormValue`…), si se asignó desde una en las 80 líneas anteriores, si es un parámetro marcado
     (`@RequestParam`, `[FromQuery]`) o un parámetro de una ruta de Flask o FastAPI;
   - una conversión a número o una función de escape la **limpia**; cualquier otra función la vuelve **derivada**;
   - las constantes (`TABLE_NAME`, `__DIR__`, `len(x)`) no cuentan.
   Certeza: **probable** con recorrido desde una entrada (la ficha muestra cada paso con su línea), **posible** sin él,
   **confirmada** para configuraciones que se ven escritas, **requiere prueba dinámica** cuando la protección puede
   estar en otra capa. Las operaciones habituales con archivos y URL solo dan hallazgo con una entrada del usuario;
   `eval` o un comando con valores variables, siempre. Los datos de la línea de órdenes (`sys.argv`) cuentan como de
   quien ejecuta el programa: como mucho "posible".
4. **La evidencia tiene que estar en código.** Un patrón dentro de una cadena o de un comentario no cuenta (el propio
   catálogo de reglas, la documentación, las pruebas con proyectos de ejemplo). El SQL es la excepción: se lee cuando
   la cadena entera es la consulta.
5. **Secretos: nunca completos, con huella local.** El redactor (Fase 3) ya ocultaba los secretos antes de que nada
   saliera del proceso aislado; ahora además calcula la **huella HMAC-SHA256** (16 bytes en hexadecimal) con una
   clave de 32 bytes que la app genera una vez con `SecureRandom` y guarda solo en la base de datos cifrada. La misma
   credencial tiene la misma huella en todos los análisis del teléfono (para compararlos en la Fase 9) y ninguna huella
   sirve fuera de él. El redactor también oculta las claves de firma escritas como argumento (`jwt.sign(x, '…')`,
   `HMAC256("…")`, `cookieParser('…')`), que antes solo se ocultaban si iban asignadas a un nombre sensible. La ficha
   muestra el tipo, la máscara (4 + 2 caracteres como máximo) y la huella; el fragmento sale de la copia redactada.
6. **Rebajas sin ocultar.** Se rebaja la severidad, y se dice por qué, en archivos de pruebas (un nivel para secretos,
   como mucho "baja" para patrones de código), en ejemplos y documentación cerca de la raíz (`com/example` es un
   paquete, no un ejemplo), en configuración de desarrollo (`application-dev.yml`, `settings_dev.py`), en valores de
   marcador (`changeme`, `your_api_key`, el ejemplo de AWS, la contraseña pública `android` de los almacenes de
   depuración), en claves de cliente de Firebase y Google, en claves de pruebas de Stripe y al desactivar CSRF en una
   API que usa tokens y no cookies.
7. **Control de acceso con lo que se ve.** Con las rutas de la 5B, se buscan protecciones junto a cada una:
   anotaciones y decoradores encima o debajo (`@PreAuthorize`, `@login_required`, `[Authorize]`), middleware en la
   misma línea, grupos con middleware (Laravel), `router.use(auth)` antes, protección de la clase o del controlador,
   comprobaciones al principio del manejador (`if (!req.user)`, `abort(403)`) y lo marcado como público a propósito
   (`[AllowAnonymous]`, `AllowAny`). Se señalan las rutas de administración sin ninguna protección visible y las que
   rompen el patrón de su archivo (al menos dos tercios de sus rutas, sin contar las públicas por su nombre, están
   protegidas). Certeza "posible", o "requiere prueba dinámica" si el proyecto tiene configuración global (Spring
   Security, middleware global, política por defecto).
8. **Supresiones informadas, no respetadas.** `nosemgrep`, `# nosec`, `NOSONAR`, `eslint-disable` de reglas de
   seguridad, `@SuppressLint` de seguridad, `gitleaks:allow` y los archivos de exclusiones son hallazgos informativos;
   lo que silencian se analiza igual.
9. **Límites y tolerancia a fallos.** Como mucho 5 000 hallazgos y 25 por regla y archivo; 10 archivos por regla de
   archivos sensibles (el resto se cuenta). Un detector que falla en un archivo no detiene el análisis: la cobertura lo
   dice. Cada registro se valida en el motor antes de emitirse y otra vez en el proceso principal (esquema **v7**).
10. **Fuera de esta fase.** Dependencias con vulnerabilidades conocidas y librerías desactualizadas (Fase 7, con OSV
    sin conexión): el resumen las lista como no evaluadas, igual que la detección de inyecciones en lenguajes sin
    reglas de código (Ruby, Rust, Swift, C, C++…: solo secretos, configuración y supresiones).

## Consecuencias

- Pocos falsos positivos en código corriente (este repositorio, que contiene las propias reglas: 3 hallazgos fuera de
  las pruebas, todos correctos e informativos), a cambio de no ver flujos entre funciones o archivos: un dato de la
  petición que pasa por otra función llega como "posible", nunca como "probable".
- Las protecciones globales que no se ven junto a la ruta (pasarela, proxy, middleware registrado en otro archivo que
  no es el de la app) no se pueden asociar: por eso el control de acceso nunca pasa de "posible".
- Coste: unos 2–3 s más de lectura en el proyecto sintético de 3 000 archivos (dentro del ruido de la medición) y
  15–25 ms la etapa final.
