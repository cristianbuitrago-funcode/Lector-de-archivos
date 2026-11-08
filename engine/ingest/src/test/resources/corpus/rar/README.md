# Corpus RAR de prueba

Archivos RAR tomados del conjunto de pruebas de **libarchive**
(`libarchive/test/*.rar.uu`, rama `master`, descargados el 2026-11-03 y decodificados de uuencode).
Licencia: BSD de 2 cláusulas, ver `LICENSE-libarchive.txt`.

Se usan para comprobar que el lector RAR de Funcode Code Analyzer:

- lee RAR4 y RAR5, comprimidos, almacenados y sólidos;
- detecta el cifrado de datos y de nombres (contraseña de prueba: `password`; en los `rar4_encrypted`, `d.txt`
  usa `password2`);
- rechaza los multivolumen (`*.part01.rar`, `*.part0001.rar`);
- registra enlaces simbólicos y duros sin seguirlos;
- registra comprimidos anidados (`zip_in_rar`) sin expandirlos;
- no se fía del tamaño declarado (`unpacked_size_exceeds_declared`);
- resiste archivos dañados o diseñados para provocar fallos (`truncated_huff`, `noeof`, `distance_overflow`,
  `invalid_dict_reference`, `window_buf_and_size_desync`, `ppmd_use_after_free`).
