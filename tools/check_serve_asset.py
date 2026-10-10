#!/usr/bin/env python3
"""Guardia del lanzador `aegis-serve.sh` (G2; corre en CI).

Motivo: F11 midio que un APK con el asset viejo relanza el bug del entorno
(PATH Android, sin toolchain) aunque el repo este bien. Este chequeo falla si
el asset pierde los fijados criticos. No ejecuta nada: solo lee el texto.

Uso: python3 tools/check_serve_asset.py
"""
import sys
from pathlib import Path

ASSET = Path("app/app/src/main/assets/aegis-serve.sh")

OBLIGATORIOS = [
    "CHROOT_PATH=",
    "PATH=$CHROOT_PATH",
    "SHELL=/usr/bin/bash",
    "HOME=/root",
    "oom_score_adj",
    "[o]pencode(.exe)? serve --service",
    "mkdir ",
]

FALLOS = []


def main():
    if not ASSET.is_file():
        print(f"FALLO: no existe {ASSET}")
        return 2
    texto = ASSET.read_text(encoding="utf-8", errors="replace")
    for marca in OBLIGATORIOS:
        if marca not in texto:
            FALLOS.append(marca)
    if FALLOS:
        print("FALLO: el asset perdio fijados criticos:")
        for f in FALLOS:
            print(f"  - {f!r}")
        return 2
    print(f"check_serve_asset: OK ({ASSET}, {len(texto)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
