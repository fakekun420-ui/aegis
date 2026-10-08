#!/usr/bin/env python3
"""Guardia automatica de hilo principal / su unico / fallos mudos (F7, T-F7.5).

Falla (exit 2) si:
 1. aparece `Regex(`, `.toRegex()` o `Pattern.compile(` dentro de `ui/` o `data/`
    en una linea que NO es una constante (`private val`/`val X =` de nivel
    superior, companion u object). Las constantes compiladas una vez estan bien.
 2. aparece `RootShell.exec(` o `RootShell.readFile(` fuera de los ficheros con
    permiso (abajo): todo `su` sale por `Raiz` o por lambdas inyectables que ya
    corren en IO, verificadas una a una.
 3. aparece `catch (_: Exception) {}` con cuerpo vacio en `ui/viewmodel/`,
    salvo que la linea anterior (o la misma) lleve `GUARD-SILENCIO-OK:` con el
    motivo (polls con reintento por diseno, best-effort con respaldo).

Uso: `python3 tools/check_hilo_principal.py` (tambien en el job `lint` de CI).
"""

import re
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
APP = RAIZ / "app"

# Ficheros con permiso para nombrar RootShell (verificado: todos fuera de main):
# - data/RootShell.kt (el que bifurca), data/Raiz.kt (punto unico con semaforo),
# - data/RutaNativa.kt (`sh()` + default SHELL_REAL), data/OpenCodeLauncher.kt,
# - data/SetupNative.kt (`shellExecutor` inyectable, corre en ioDispatcher),
# - data/Credentials.kt (`fileReader` inyectable; `getPassword` en IO y el
#   interceptor de OkHttp corre en hilos de OkHttp).
PERMISO_SU = {
    "app/app/src/main/kotlin/com/aegis/hub/RootShell.kt",
    "app/app/src/main/kotlin/com/aegis/hub/data/Raiz.kt",
    "app/app/src/main/kotlin/com/aegis/hub/data/RutaNativa.kt",
    "app/app/src/main/kotlin/com/aegis/hub/data/OpenCodeLauncher.kt",
    "app/app/src/main/kotlin/com/aegis/hub/data/SetupNative.kt",
    "app/app/src/main/kotlin/com/aegis/hub/data/Credentials.kt",
}

RX_REGEX = re.compile(r"(Regex\(|\.toRegex\(|Pattern\.compile\()")
RX_CONST = re.compile(r"^\s*(private\s+)?val\s+\w+\s*=\s*Regex")
RX_SU = re.compile(r"RootShell\.(exec|readFile)\(")
RX_CATCH_MUDO = re.compile(r"catch\s*\(\s*_:\s*Exception\s*\)\s*\{\s*\}")


def main() -> int:
    fallos = []

    for f in sorted((APP / "app" / "src" / "main").rglob("*.kt")):
        rel = str(f.relative_to(RAIZ))
        try:
            lineas = f.read_text(encoding="utf-8", errors="replace").split("\n")
        except OSError as e:
            fallos.append(f"{rel}: no se pudo leer ({e})")
            continue
        en_ui_data = "/ui/" in rel or "/data/" in rel
        en_vm = "/ui/viewmodel/" in rel
        for i, linea in enumerate(lineas, start=1):
            if en_ui_data and RX_REGEX.search(linea) and not RX_CONST.match(linea):
                fallos.append(f"{rel}:{i}: Regex compilado fuera de constante: {linea.strip()[:100]}")
            if RX_SU.search(linea) and rel not in PERMISO_SU:
                fallos.append(f"{rel}:{i}: RootShell directo sin permiso: {linea.strip()[:100]}")
            if en_vm and RX_CATCH_MUDO.search(linea):
                contexto = " ".join(lineas[max(0, i - 4):i])
                if "GUARD-SILENCIO-OK:" not in contexto:
                    fallos.append(f"{rel}:{i}: catch mudo en ViewModel sin motivo: {linea.strip()[:100]}")

    if fallos:
        print("check_hilo_principal: FALLO")
        for x in fallos:
            print("  " + x)
        return 2
    print("check_hilo_principal: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
