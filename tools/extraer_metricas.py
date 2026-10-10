#!/usr/bin/env python3
"""Extrae metricas E3/E4/E8 de un logcat guardado (G1, T-G1.2).

Lee lineas `AegisMark:` (ventanas abrir/enviar/skills/reposo) y `AegisTrace:`
(peticiones, para `GET api/model` por 10 min) y emite una tabla Markdown lista
para `docs/PERF-BASELINE.md`. Todo lo no emparejado se declara, no se inventa.

Uso:
    python3 tools/extraer_metricas.py docs/qa/METRICAS-20261010.log
    python3 tools/extraer_metricas.py --self-test   # sobre el ejemplo versionado
"""
import re
import sys
from collections import defaultdict

MARK_RE = re.compile(r"AegisMark\(\s*\d+\):\s+(\S+)(?:\s+(\S+))?(?:\s+(.*))?$")
TS_RE = re.compile(r"^(\d\d-\d\d \d\d:\d\d):\d\d\.\d+")
TRACE_MODEL_RE = re.compile(r"GET api/model=(\d+)")


def bucket10(ts):
    """'10-09 04:1' -> ventana de 10 min. Sin fecha -> '?'."""
    if not ts:
        return "?"
    return ts[:-1] + "x"


def extraer(lineas):
    marcas = []          # (evento, sid, extras, ts)
    modelos10 = defaultdict(int)
    for ln in lineas:
        ts = (TS_RE.match(ln).group(1) if TS_RE.match(ln) else None)
        m = MARK_RE.search(ln)
        if m:
            evento, sid, resto = m.group(1), m.group(2) or "", m.group(3) or ""
            if "=" in sid:
                # Evento sin sid (p. ej. skills): el primer token era un extra.
                resto, sid = (sid + " " + resto).strip(), ""
            extras = dict(re.findall(r"(\w+)=(\d+)", resto))
            marcas.append((evento, sid, {k: int(v) for k, v in extras.items()}, ts))
            continue
        t = TRACE_MODEL_RE.search(ln)
        if t:
            modelos10[bucket10(ts)] += int(t.group(1))
    return marcas, modelos10


def tabla(marcas, modelos10):
    filas = []
    for evento, sid, ex, _ in marcas:
        if evento.endswith(":fin") or evento.startswith("reposo"):
            filas.append((evento, sid, ex.get("peticiones", "-"), ex.get("su", "-")))
    md = ["| ventana | sesion | peticiones | su |",
          "|---|---|---|---|"]
    for evento, sid, pet, su in filas:
        md.append(f"| {evento} | {sid} | {pet} | {su} |")
    md.append("")
    md.append(f"GET api/model por ventana de 10 min: " +
              (", ".join(f"{k}={v}" for k, v in sorted(modelos10.items())) or "sin datos"))
    inicios = sum(1 for e, _, _, _ in marcas if e.endswith(":inicio"))
    fines = sum(1 for e, _, _, _ in marcas if e.endswith(":fin"))
    if inicios != fines:
        md.append(f"AVISO: {inicios} inicios sin sus {fines} fines (ventanas incompletas, no rellenar).")
    return "\n".join(md)


def self_test():
    ej = open("tools/ejemplo-logcat-metricas.txt", encoding="utf-8").read().splitlines()
    marcas, modelos10 = extraer(ej)
    out = tabla(marcas, modelos10)
    assert "| chat.abrir:fin | ses_a | 3 | - |" in out, out
    assert "| chat.enviar:fin | ses_a | 8 | - |" in out, out
    assert "| skills.abrir:fin |  | 2 | 1 |" in out, out
    assert "10-09 04:1x=2" in out, out
    assert "AVISO: 4 inicios sin sus 3 fines" in out, out
    print("self-test OK")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
    elif len(sys.argv) == 2:
        marcas, modelos10 = extraer(open(sys.argv[1], encoding="utf-8").read().splitlines())
        print(tabla(marcas, modelos10))
    else:
        sys.exit("uso: extraer_metricas.py <logcat> | --self-test")
