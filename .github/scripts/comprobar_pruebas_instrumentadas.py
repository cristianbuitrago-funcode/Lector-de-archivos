#!/usr/bin/env python3
"""Comprueba que cada módulo ejecutó en el emulador todas sus pruebas instrumentadas.

El plugin de Android puede dar por buena la tarea `connectedDebugAndroidTest` aunque el ejecutor de pruebas no llegue
a arrancar (por ejemplo, si ADB no responde): el módulo queda con 0 pruebas y la CI en verde. Este script cuenta los
`@Test` de `src/androidTest` de cada módulo y los compara con los resultados XML que dejó el emulador.

Uso:
  comprobar_pruebas_instrumentadas.py                  informe y código 0 si todo cuadra, 1 si no
  comprobar_pruebas_instrumentadas.py --sin-resultados  solo imprime las tareas de los módulos sin ningún resultado
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

RAIZ = pathlib.Path(__file__).resolve().parents[2]
PRUEBA = re.compile(r"^\s*@Test\b", re.MULTILINE)


def modulos():
    """Módulos con pruebas instrumentadas: (ruta relativa, número de @Test)."""
    encontrados = []
    for carpeta in sorted(RAIZ.glob("**/src/androidTest")):
        relativa = carpeta.parent.parent.relative_to(RAIZ)
        if "build" in relativa.parts or relativa.parts[:1] == (".gradle",):
            continue
        pruebas = sum(len(PRUEBA.findall(f.read_text(encoding="utf-8"))) for f in carpeta.rglob("*.kt"))
        if pruebas:
            encontrados.append((relativa, pruebas))
    return encontrados


def resultados(modulo):
    """Suma de los resultados XML del módulo, o None si no hay ninguno."""
    carpeta = RAIZ / modulo / "build" / "outputs" / "androidTest-results" / "connected"
    totales = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    hay = False
    for archivo in sorted(carpeta.rglob("*.xml")) if carpeta.is_dir() else []:
        raiz = ElementTree.parse(archivo).getroot()
        suites = [raiz] if raiz.tag == "testsuite" else raiz.findall("testsuite")
        for suite in suites:
            hay = True
            for clave in totales:
                totales[clave] += int(suite.get(clave, "0") or 0)
    return totales if hay else None


def tarea(modulo):
    return ":" + ":".join(modulo.parts) + ":connectedDebugAndroidTest"


def main():
    lista = modulos()
    if not lista:
        print("No se encontró ningún módulo con pruebas instrumentadas.")
        return 1
    if "--sin-resultados" in sys.argv[1:]:
        print(" ".join(tarea(m) for m, _ in lista if resultados(m) is None))
        return 0
    correcto = True
    for modulo, esperadas in lista:
        r = resultados(modulo)
        if r is None:
            print(f"ERROR {modulo}: sin resultados; se esperaban {esperadas} pruebas (el ejecutor no llegó a correr).")
            correcto = False
            continue
        ejecutadas = r["tests"] - r["skipped"]
        fallidas = r["failures"] + r["errors"]
        linea = f"{modulo}: {ejecutadas} de {esperadas} pruebas ejecutadas (fallidas: {fallidas}, omitidas: {r['skipped']})"
        if ejecutadas != esperadas or fallidas or r["skipped"]:
            print("ERROR " + linea)
            correcto = False
        else:
            print("OK    " + linea)
    return 0 if correcto else 1


if __name__ == "__main__":
    sys.exit(main())
