#!/usr/bin/env python3
"""graphify-staleness — comprobacion unica de si el mapa esta desactualizado.

Fuente de verdad del proyecto. La usan, y solo ella:
  * .githooks/pre-commit        -> git commit (bloquea)
  * .opencode/plugins/graphify-staleness.js -> aviso al modelo (no bloquea)

Que NO usa mtime, y por que: /sdcard es FUSE y su mtime miente (ponytail-global
§2). Con el mtime congelado y el contenido nuevo, tanto el test de §4.3.1 como
el detect_incremental(kind="ast") de graphify dan "sin cambios" — falso
negativo, la direccion peligrosa. Aqui el veredicto sale de
md5(contenido) == manifest.ast_hash, que es el mismo hash que graphify estampa
al indexar (detect._md5_file = hashlib.md5 en trozos de 64 KB). El mtime no
participa en ninguna rama.

El inventario de ficheros tampoco es propio: es detect() de graphify, el mismo
que usa `graphify update`. Una lista de extensiones escrita a mano discrepa (dio
por bueno un mapa viejo con package-lock.json, que detect() excluye, y por
malo un .vue nuevo que graphify si indexa).

Sin dependencia de OpenCode. Necesita un python con `graphify` importable; si no
lo hay, sale con codigo 2 y lo dice (quien llama decide si eso bloquea o no).

Uso:
    stale-check.py [--root DIR] [--json] [--quiet]

Codigos de salida:
    0  el mapa esta al dia
    1  el mapa esta desactualizado
    2  no se pudo comprobar (falta graphify, manifest ilegible, etc.)

El informe legible va a stderr (para que el hook lo muestre tal cual); con
--json sale un objeto por stdout. Sin argumentos, la raíz se busca hacia arriba
desde el directorio actual, igual que git busca .git.
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

MARKER = "graphify-out/manifest.json"


def find_root(start):
    """Ancestor search de graphify-out/manifest.json, como el de git con .git."""
    try:
        cur = Path(start).resolve()
    except OSError:
        return None
    for cand in (cur, *cur.parents):
        if (cand / MARKER).is_file():
            return cand
    return None


def die(msg, code=2):
    sys.stderr.write(msg if msg.endswith("\n") else msg + "\n")
    sys.exit(code)


def main():
    args = sys.argv[1:]
    root_arg = None
    as_json = False
    quiet = False
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--root" and i + 1 < len(args):
            root_arg = args[i + 1]
            i += 2
        elif a.startswith("--root="):
            root_arg = a.split("=", 1)[1]
            i += 1
        elif a == "--json":
            as_json = True
            i += 1
        elif a == "--quiet":
            quiet = True
            i += 1
        else:
            die("stale-check: argumento desconocido %r" % a)

    if root_arg:
        root = Path(root_arg).resolve()
    else:
        root = find_root(os.getcwd())
    if root is None:
        die("stale-check: no encuentro graphify-out/manifest.json ni hacia arriba "
            "desde %s" % os.getcwd())
    if not (root / "graphify-out" / "graph.json").is_file():
        die("stale-check: %s no tiene graphify-out/graph.json" % root)

    try:
        from graphify.detect import detect, load_manifest, _md5_file
    except Exception as exc:
        die("stale-check: no puedo importar graphify.detect con este python "
            "(%s): %s" % (sys.executable, exc))

    t0 = time.time()
    try:
        full = detect(root)
    except Exception as exc:
        die("stale-check: detect() falló en %s: %s" % (root, exc))
    try:
        man = load_manifest(str(root / "graphify-out" / "manifest.json"), root=root)
    except Exception as exc:
        die("stale-check: manifest ilegible en %s: %s" % (root, exc))

    inventory = set()
    for _ft, flist in full["files"].items():
        inventory.update(flist)

    changed, blank, unread, deleted = [], [], [], []
    for key, val in man.items():
        p = Path(key)
        if not p.exists():
            deleted.append(key)
            continue
        stored = (val or {}).get("ast_hash") or ""
        if not stored:
            blank.append(key)          # el AST fallo en su dia: hay que reindexar
            continue
        h = _md5_file(p)
        if not h:
            unread.append(key)
            continue
        if h != stored:
            changed.append(key)

    new = sorted(inventory - set(man))
    elapsed = time.time() - t0
    verdict = {
        "root": str(root),
        "indexed": len(man),
        "changed": changed,
        "deleted": deleted,
        "new": new,
        "blank": blank,
        "unread": unread,
        "seconds": round(elapsed, 3),
    }
    verdict["stale"] = bool(changed or deleted or new or blank or unread)
    verdict["fix"] = "graphify update %s%s" % (root, " --force" if deleted else "")

    if as_json:
        sys.stdout.write(json.dumps(verdict) + "\n")
        if not verdict["stale"] and quiet:
            sys.exit(0)
    elif not quiet:
        report(verdict)

    if verdict["stale"]:
        sys.exit(1)
    if os.environ.get("GRAPHIFY_HOOK_VERBOSE") == "1" and not as_json:
        sys.stderr.write("graphify: mapa al día (%d ficheros, md5 verificado en %.2fs)\n"
                         % (verdict["indexed"], elapsed))
    sys.exit(0)


def rel(root, paths, n=8):
    out = []
    for p in paths[:n]:
        try:
            p = str(Path(p).relative_to(root))
        except Exception:
            pass
        out.append("   %s" % p)
    return out


def report(v):
    """Texto legible. Lo imprime el hook tal cual, sin reformatear."""
    w = sys.stderr.write
    if not v["stale"]:
        return
    root = v["root"]
    w("\n")
    w("================================================================\n")
    w(" graphify: COMMIT BLOQUEADO - el mapa esta desactualizado\n")
    w("================================================================\n")
    w("  indexados y verificados por md5 : %d\n" % v["indexed"])
    w("  modificados                      : %d\n" % len(v["changed"]))
    w("  borrados                         : %d\n" % len(v["deleted"]))
    w("  nuevos (los ve graphify, sin indice) : %d\n" % len(v["new"]))
    w("  sin ast_hash (AST fallo antes)   : %d\n" % len(v["blank"]))
    w("  ilegibles                        : %d\n" % len(v["unread"]))
    w("  (%.2fs)\n" % v["seconds"])
    if v["changed"]:
        w("  modificados, por ejemplo:\n" + "\n".join(rel(root, v["changed"])) + "\n")
    if v["deleted"]:
        w("  borrados:\n" + "\n".join(rel(root, v["deleted"])) + "\n")
    if v["new"]:
        w("  nuevos:\n" + "\n".join(rel(root, v["new"])) + "\n")
    if v["blank"]:
        w("  sin ast_hash:\n" + "\n".join(rel(root, v["blank"])) + "\n")
    if v["unread"]:
        w("  ilegibles:\n" + "\n".join(rel(root, v["unread"])) + "\n")
    w("\n")
    w(" arreglo:\n")
    w("   %s\n" % v["fix"])
    w(" y repite el commit. El mapa es un indice: si queda viejo, el agente\n")
    w(" que se oriente por el arranca de codigo que ya no existe.\n")
    w("\n")
    w(" este commit en concreto no sale por el:\n")
    w("   GRAPHIFY_SKIP_HOOK=1 git commit -m '...'\n")
    w("================================================================\n")


if __name__ == "__main__":
    main()
