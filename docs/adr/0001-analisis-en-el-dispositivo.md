# ADR 0001 — El análisis se ejecuta en el teléfono, en un proceso aislado

- **Estado:** aceptada (2026-11-03, decisión N1 = A del propietario)
- **Contexto:** la herramienta es un APK de uso interno. El propietario eligió que todo el análisis ocurra en el
  teléfono, sin servidor, frente a la alternativa de un APK cliente de un servidor propio.
- **Decisión:** el motor corre en un servicio con `android:isolatedProcess="true"`. El proceso principal le pasa
  descriptores de archivo por Binder (AIDL) y recibe los resultados por una tubería con tramas de longitud prefijada
  que valida como datos no confiables. Cada análisis usa un proceso aislado nuevo, que se destruye al terminar.
- **Consecuencias:**
  - El sistema impide al motor usar la red, leer archivos de la app o el almacenamiento compartido, aunque un parser
    sea explotado.
  - Semgrep y OWASP Dependency-Check no pueden ejecutarse en Android: se sustituyen por reglas propias y la base OSV
    (ver `docs/ARQUITECTURA.md` §3.2 y §11).
  - Un fallo o bloqueo del motor no afecta a la app; un vigilante en el proceso principal lo detiene por tiempo.
