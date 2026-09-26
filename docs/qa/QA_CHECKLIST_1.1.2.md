# QA — Checklist de aceptación v1.1.2

> Checklist **vivo**: se actualiza con cada cambio de comportamiento. El histórico
> `QA_CHECKLIST.md` (v1.0.0) queda como snapshot.
>
> **Regla de oro: un PASS sin evidencia no cuenta.** Anota fecha, `versionCode` y
> comando o captura. Un caso marcado `[x]` sin evidencia está mal marcado.

**Fecha de esta revisión:** 2026-09-26 · `versionCode 159` · POCO F3 (alioth, Android 15)

---

## 0. Precondiciones

```sh
# Hub vivo
curl -s -m 5 -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8765/     # 200
# Vigilante de ejecuciones conectado (una vez al arrancar)
grep "vigilante conectado" /sdcard/projects/Aegis/backend/hub.log
# API de estado de ejecución
TOK=$(cat /sdcard/projects/Aegis/backend/.aegis_token)
curl -s -H "X-Aegis-Token: $TOK" "http://127.0.0.1:8765/api/sessions/inflight"
```

---

## 1. El "final del final" (divisor de respuesta)

| # | Pasos | Esperado | |
|---|---|---|---|
| 1.1 | Pide algo que requiera **varios `bash` seguidos** | El divisor "✓ respuesta final" **NO** aparece hasta el final | [ ] |
| 1.2 | Mientras un `bash` está en "ejecutando" | Aparece "Trabajando en ello…" y **NO** el divisor | [ ] |
| 1.3 | Cuando el turno termina | El divisor aparece **anclado a su mensaje**, no al final de la lista | [ ] |
| 1.4 | Abre una encuesta y **no la respondas** | El divisor **no** aparece (el agente te espera) | [ ] |
| 1.5 | Un turno lanzado **desde el CLI** | El estado también se refleja: el CLI no pasa por el Hub, pero el vigilante sí lo ve | [ ] |

**Evidencia de referencia (2026-09-26):** sesión con `bash sleep 25` ocupada en
t+5, 10, 15, 20 y 25 s; liberada al terminar con `turnOver=true`.

```sh
# durante el turno
curl -s -H "X-Aegis-Token: $TOK" "http://127.0.0.1:8765/api/sessions/inflight?ids=1"
# al terminar
curl -s -H "X-Aegis-Token: $TOK" "http://127.0.0.1:8765/api/sessions/inflight"
```

> Si el divisor salta antes de tiempo con la app en la versión actual, el problema ya
> no sería de inferencia sino de que `session.execution.succeeded` se emite antes de
> tiempo. Anotar el momento exacto.

---

## 2. Fases de envío

| # | Pasos | Esperado | |
|---|---|---|---|
| 2.1 | Envía un mensaje | "Enviando…" **solo** bajo tu mensaje. Nada más | [ ] |
| 2.2 | Cuando el servidor acepta | "Enviando…" desaparece, entra "Generando respuesta…" | [ ] |
| 2.3 | Observa un envío largo | **Nunca** los dos a la vez | [ ] |
| 2.4 | Con un tool ejecutándose | No aparece "Enviando…" (el mensaje ya se aceptó) | [ ] |
| 2.5 | Provoca un rechazo (p. ej. sin cuota de Antigravity) | Mensaje en error con el **motivo real** y el código HTTP | [ ] |

> Regresión 2026-09-26: la fase de envío se guardaba en `info.status`, un campo del
> mensaje, y hay tres sitios que reemplazan la lista entera por la del servidor, así
> que una carrera entre el poll y el ack devolvía la píldora a PENDING. Ahora se lee de
> `sendingInFlight`, estado que ningún poll toca. Si vuelve a pasar, el estado ha
> vuelto a mudarse al mensaje.

---

## 3. Navegación del historial

| # | Pasos | Esperado | |
|---|---|---|---|
| 3.1 | Sube a releer historia con contenido llegando | La lista **no** te arrastra al final | [ ] |
| 3.2 | Mirando historia | Aparece un botón flotante con **flecha hacia abajo** | [ ] |
| 3.3 | Tócalo | Vuelve al mensaje más reciente, sin saltos | [ ] |
| 3.4 | Abre un chat | Sí va directo al más reciente (no se rompe lo anterior) | [ ] |

---

## 4. Estado de ejecución en la lista

| # | Pasos | Esperado | |
|---|---|---|---|
| 4.1 | Con un turno vivo, ve a la lista de chats | Esa fila tiene un **círculo girando** | [ ] |
| 4.2 | Cuando el turno termina | El círculo desaparece | [ ] |
| 4.3 | Turno lanzado desde el CLI | También sale el círculo | [ ] |

---

## 5. Formularios / encuestas

| # | Pasos | Esperado | |
|---|---|---|---|
| 5.1 | Encuesta de **una** pregunta | Un toque envía y contesta | [ ] |
| 5.2 | Encuesta de **varias** | Se pintan todas, numeradas; al elegir se resalta | [ ] |
| 5.3 | Con varias | El botón marca `n/N` y se habilita al estar todas | [ ] |
| 5.4 | Envía | El agente recibe **todas** las respuestas | [ ] |
| 5.5 | Si algún campo no trae opciones | Avisa de cuántos se descartan, y el botón lo dice | [ ] |

**Evidencia (2026-09-26, versionCode 159):** formulario real de 3 campos rellenado
por API con valores a propósito **no primeros** (`q0=Sevilla, q1=42, q2=Verde`); el
modelo devolvió "Ciudad: Sevilla, Número: 42, Color: Verde".

> Ojo al verificar: **no se puede leer el `output` de la herramienta `question`**,
> OpenCode 2.0.14 lo deja a `null`. La única prueba válida es preguntarle al modelo.

> Trampa: `POST /api/session/:id/form/:fid/reply` **resuelve el formulario entero** con
> lo que le llegue. Una respuesta parcial **descarta el resto en silencio**. No hay
> acumula.

---

## 6. Proyectos

| # | Pasos | Esperado | |
|---|---|---|---|
| 6.1 | + Nuevo proyecto → Vincular carpeta | Elige una carpeta existente | [ ] |
| 6.2 | "Usar esta carpeta" | Aparece en la lista de proyectos | [ ] |
| 6.3 | Carpeta fuera de `/sdcard/projects` | Rechazo claro, no registro silencioso | [ ] |
| 6.4 | Carpeta ya reclamada por otro proyecto | Rechazo con el nombre del proyecto que la tiene | [ ] |

> Regresión 2026-09-26: `treeUriToFsPath()` leía `pathSegments.firstOrNull()`, pero
> `OpenDocumentTree` devuelve una URI **de árbol** donde ese segmento es el literal
> `"tree"`. Devolvía `null` siempre y no llegaba ni un POST al Hub.

---

## 7. Integridad del repositorio (añadido tras el incidente de FUSE)

```sh
# Todos los blobs de HEAD legibles y coincidentes con el disco
MALOS=0
for F in $(git ls-tree -r --name-only HEAD | grep -E '\.kt$'); do
  S=$(git cat-file -s HEAD:$F 2>&1 | head -1)
  case "$S" in error*|'') echo "ILEGIBLE: $F"; MALOS=$((MALOS+1)); continue;; esac
  [ -f "$F" ] && { [ "$(git cat-file -p HEAD:$F | md5sum)" = "$(md5sum "$F")" ] || { echo "DIFIERE: $F"; MALOS=$((MALOS+1)); }; }
done
echo "problemas: $MALOS"     # debe ser 0
```

| # | Comprobación | | |
|---|---|---|---|
| 7.1 | El script anterior da 0 problemas | [ ] |
| 7.2 | `git push` funciona sin `hung up unexpectedly` | [ ] |

> **Un PASS de `git fsck --connectivity-only` NO vale aquí**: solo comprueba
> conectividad, no el contenido de los objetos. Es lo que dejó pasar el incidente del
> 2026-09-26, donde `git add` commiteó tres ficheros de 0 bytes y el push falló
> pareciendo un problema de red. Ver la trampa en `ponytail-global.md` §2.

---

## Resultado de esta revisión

| Caso | Resultado | Evidencia |
|---|---|---|
| 1.1–1.3 | PASS | capturas del usuario, 2026-09-26 |
| 1.4, 1.5 | PASS | verificado por API |
| 2.1–2.3 | PASS | capturas del usuario; se corrigió una regresión |
| 2.4, 2.5 | pendiente | |
| 3.1–3.4 | pendiente | compilado e instalado, sin confirmar visualmente |
| 4.1–4.3 | pendiente | idem |
| 5.1 | PASS | captura del usuario |
| 5.2–5.5 | PASS | prueba de ida y vuelta por API |
| 6.1–6.4 | pendiente | corregido, sin reintentar todavía |
| 7.1–7.2 | PASS | verificado en cada push |
