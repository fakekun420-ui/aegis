#!/usr/bin/env python3
"""
Detecta funciones top-level de Kotlin que LLAMAN a composables pero no llevan @Composable.

Por que existe: no hay toolchain de Android en este host (ni java, ni gradle, ni
kotlinc), asi que la unica compilacion real es el CI. Y el CI tardo 9 minutos en
avisar de un @Composable que se habia quedado pegado a la funcion de al lado, al
anclar un parche en el substring "private fun PendingFormCard(" sin incluir el
decorador que tenia encima. Este chequeo atrapa esa clase de fallo en un segundo.

Heuristica: se recorre el fichero, se localizan las declaraciones top-level
"fun nombre(" y "private fun nombre(", se mira si la linea anterior (o las dos
anteriores, por si hay un decorador partido) es @Composable, y se comprueba si el
cuerpo contiene llamadas tipicas de composable.

Falsos positivos posibles: una funcion que mentione "Text(" solo en un comentario, o
que devuelva un valor de composable sin pintarlo. Se listan como SOSPECHOSAS y se
revisan a ojo, no se pasan por alto en silencio.
"""
import re
import sys
from pathlib import Path

# Llamadas que solo existen dentro de un contexto @Composable.
SENALES = re.compile(
    r'\b(MaterialTheme\.|LocalContext\.current|LocalConfiguration\.current|'
    r'Column\(|Row\(|Box\(|Spacer\(|Text\(|Button\(|Icon\(|Card\(|Surface\(|'
    r'LazyColumn|LazyRow|items\(|remember|collectAsState|AnimatedVisibility|'
    r'OutlinedButton\(|IconButton\(|Divider\(|CircularProgressIndicator\()'
)

# Solo declaraciones de NIVEL SUPERIOR (columna 0). Una función local declarada dentro
# de un @Composable SÍ puede llamar a composables sin llevar @Composable, porque el
# compilador Compose la compila dentro del ámbito del composable que la contiene. Mirar
# esas produce falsos positivos:PendingFormCard, queueTts y scheduleDuplexRestart son
# locales, y compilan.
DECL = re.compile(r'^(?:private |internal |public )?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')


def analizar(fichero: Path):
    lineas = fichero.read_text(encoding='utf-8', errors='replace').splitlines()
    # Ultima llave de cierre a nivel de indentacion 0, para saber donde acaba cada cuerpo.
    hallazgos = []
    for i, linea in enumerate(lineas):
        m = DECL.match(linea)
        if not m:
            continue
        # El decorador puede estar en la linea anterior, o dos atras si hay un KDoc
        # partido. Se mira hacia atras saltando lineas en blanco.
        j = i - 1
        while j >= 0 and not lineas[j].strip():
            j -= 1
        tiene_composable = j >= 0 and '@Composable' in lineas[j]
        if tiene_composable:
            continue
        # Cuerpo: hasta la siguiente declaracion top-level o hasta el fin.
        cuerpo = []
        for k in range(i, min(i + 400, len(lineas))):
            if k > i and DECL.match(lineas[k]):
                break
            cuerpo.append(lineas[k])
        texto = '\n'.join(cuerpo)
        if SENALES.search(texto):
            # Se descarta si TODO lo que casa esta en comentarios.
            sin_comentarios = re.sub(r'//.*', '', texto)
            sin_comentarios = re.sub(r'/\*.*?\*/', '', sin_comentarios, flags=re.S)
            if SENALES.search(sin_comentarios):
                senal = SENALES.search(sin_comentarios)
                hallazgos.append((i + 1, m.group(1), senal.group(0)))
    return hallazgos


def main(raiz: str):
    raiz = Path(raiz)
    if raiz.is_file():
        ficheros = [raiz]
    else:
        ficheros = sorted(raiz.rglob('*.kt'))
    total = 0
    for f in ficheros:
        for linea, nombre, senal in analizar(f):
            total += 1
            print(f'  {f}:{linea}  {nombre}() usa {senal} sin @Composable')
    if total == 0:
        print('  ✅ ninguna funcion top-level usa composables sin @Composable')
    else:
        print(f'\n  {total} sospechosa(s). Revisar a ojo: puede haber falsos positivos,')
        print('  pero TODAS las de la clase "se robo el decorador" salen aqui.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else '.'))
