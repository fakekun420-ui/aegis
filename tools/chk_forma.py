#!/usr/bin/env python3
"""Valida los tipos DECLARADOS de NativeModels.kt contra el JSON REAL de cada endpoint.

POR QUE ESTE SCRIPT EXISTE, y es la leccion de dos fallos del usuario:

  1. `model` de /api/agent: declarado `String?`, el JSON trae un OBJETO.
     -> `Expected a string but was BEGIN_OBJECT at $.data[0].model`
  2. `permissions`: declarado `Map<String, Any?>`, el JSON trae una LISTA de objetos.
     -> `Expected BEGIN_ARRAY but was BEGIN_OBJECT at $.data[0].permissions[0]`

Los dos estan en la MISMA clase y los dos los escribi de memoria. Uno por sesion es un ciclo de
CI; seis son seis ciclos. Este script los mide TODOS de una vez.

QUE COMPARA, y por que no es perfecto:
  - Tipo declarado: se lee de la anotacion `@SerializedName("x") val x: TIPO`.
  - Tipo real: se mide sobre el JSON de verdad, con el primer elemento de la lista.
  - Solo compara la CATEGORIA (scalar, lista, objeto). Kotlin no permite comprobar genericos, y
    `List<Any>` frente a `List<Foo>` a Gson le da igual: el fallo ocurre por ARRAY vs OBJECT.
    Por eso el script marca `?` cuando la categoria encaja pero el generico no se puede verificar.
"""
import json
import re
import subprocess
import sys
import urllib.request
import base64

PW = ""
try:
    PW = json.load(open("/root/.local/state/opencode/service.json"))["password"]
except Exception:
    pass


def pedir(url):
    req = urllib.request.Request(url)
    tok = base64.b64encode(("opencode:" + PW).encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def categoria(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    if isinstance(v, dict):
        return "object"
    return "?"


def kotlin_a_categoria(t):
    """Traduce el tipo declarado en Kotlin a la categoria que Glow espera."""
    t = t.strip()
    if t.endswith("?"):
        t = t[:-1]
    if t.startswith("List<") or t.startswith("MutableList<") or t in ("List", "Array"):
        return "array"
    if t.startswith("Map<") or t.startswith("MutableMap<") or t in ("Map", "HashMap"):
        return "object"
    if t in ("String", "Char", "CharSequence"):
        return "string"
    if t in ("Boolean",):
        return "bool"
    if t in ("Int", "Long", "Double", "Float", "Short", "Byte"):
        return "number"
    if t in ("Any", "Any?"):
        return "*"
    # Un data class propio es un objeto.
    return "object"


def declarados(ruta):
    """Extrae {campo: tipo Kotlin} de las data class del fichero."""
    txt = open(ruta, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'@SerializedName\("(\w+)"\)\s*val\s+(\w+)\s*:\s*([^,=)]+)', txt):
        out[m.group(1)] = m.group(3).strip()
    return out


def primera_de_lista(d):
    while isinstance(d, dict):
        for k in ("data", "items"):
            if k in d:
                d = d[k]
                break
        else:
            return d
    if isinstance(d, list) and d:
        return d[0]
    return d


RUTA = "/sdcard/projects/Aegis/app/app/src/main/kotlin/com/aegis/hub/data/NativeModels.kt"
DECL = declarados(RUTA)

ENDPOINTS = [
    ("/api/agent", "OpenCodeNativeAgent"),
    ("/api/model", "OpenCodeNativeModel"),
    ("/api/session", "OpenCodeSession"),
    ("/api/session/active", None),
]

problemas = 0
for ep, etiqueta in ENDPOINTS:
    try:
        raw = pedir("http://127.0.0.1:49374" + ep)
    except Exception as e:
        print("  %-24s no se pudo pedir: %s" % (ep, str(e)[:50]))
        continue
    obj = primera_de_lista(raw)
    if not isinstance(obj, dict):
        print("  %-24s (no es una lista de objetos; nada que validar)" % ep)
        continue
    print("  === %s  (%s) ===" % (ep, etiqueta or "libre"))
    for campo, valor in sorted(obj.items()):
        if campo not in DECL:
            print("      %-14s SIN DECLARAR (real: %s)" % (campo, categoria(valor)))
            continue
        decl = DECL[campo]
        cat_decl = kotlin_a_categoria(decl)
        cat_real = categoria(valor)
        if cat_decl == "*":
            continue
        ok = (cat_decl == cat_real) or (cat_real == "null")
        marca = "OK " if ok else "<<< MAL"
        if not ok:
            problemas += 1
        print("      %s %-14s declarado=%-22s real=%s" % (marca, campo, decl, cat_real))

print()
print("  campos con tipo incompatible: %d" % problemas)
sys.exit(1 if problemas else 0)