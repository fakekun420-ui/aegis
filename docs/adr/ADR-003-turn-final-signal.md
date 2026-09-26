# ADR-003: La señal de "fin de turno" es `session.execution.*`, no el mensaje

**Estado:** Aceptada
**Fecha:** 2026-09-26
**Decisores:** implementación del "final del final" del divisor de respuesta y del estado de ejecución de los chats
**Relacionado:** `CHANGELOG.md` [1.1.2], commit `2fdfea4` (backend) y `663029a` (app)

---

## Contexto

Aegis muestra un separador de "✓ respuesta final" cuando el agente ha terminado su
turno. La primera señal que se usó fue `info.time.completed` del último mensaje del
asistente, y **no funciona**, por una razón estructural que no es evidente:

`time.completed` cierra el **mensaje**, no el **turno**. Un mensaje del asistente
lleva `completed` en cuanto *su segmento* deja de crecer, y un turno agéntico tiene
muchos segmentos: el modelo escribe, llama a una herramienta, sigue escribiendo. Por
eso el divisor saltaba **después de cada `bash` con exit 0**, con el agente a
medias de trabajo.

Los intentos sucesivos fueron escalando en sofisticación y todos fallaban por la
misma razón de fondo:

| Intento | Señal | Por qué no servía |
|---|---|---|
| `time.streamed` | fin de segmento | se cumple en cada pausa del modelo |
| `time.completed` | fin de mensaje | se cumple tras cada herramienta |
| "sin herramientas `running`" | heurística | un turno puede pausarse sin herramientas |
| "el siguiente mensaje es del usuario" | posición | correcto, pero solo para turnos ya cerrados |

Ninguna de estas puede contestar la pregunta "¿el agente va a hacer algo más?", porque
esa información **no está en el mensaje**: está en el estado del turno.

## La decisión

El Hub se suscribe al stream de eventos de OpenCode (`GET /api/event`, SSE) y sigue
la ejecución real:

```
session.execution.started   ->  {"sessionID": "ses_..."}
session.execution.succeeded ->  {"sessionID": "ses_..."}
session.inbox.enqueued      ->  {"inboxID", "sessionID", "item"}
session.inbox.delivered     ->  {"sessionID", "inboxID"}
```

`session.execution.succeeded` es la única señal que significa literalmente "no va a
hacer nada más hasta que le hables". Se expone en `GET /api/sessions/inflight`.

### Por qué el stream y no un registro propio

La alternativa obvia es que el Hub registre el turno al aceptar el prompt. **Se
descartó**: los turnos lanzados desde el **CLI** entran a OpenCode **sin pasar por el
Hub**, y son la mitad de los casos. Un registro propio solo habría visto los de la
app, y el síntoma habría reaparecido solo cuando se trabaja desde el CLI.

El stream de eventos ve los dos por igual, y además cubre la entrega del mensaje
(`session.inbox.delivered`), que es lo que permite confirmar en la UI que el
mensaje llegó de verdad.

### Consecuencias

- El divisor depende de una señal externa, no de una inferencia sobre el historial.
  Cuesta un watcher (~80 líneas) y un endpoint.
- `turnOver` es efímero: si el stream se pierde, el reaper de seguridad marca la
  sesión como libre a los 15 minutos. Un turno perdido en el aviso es preferible a
  un divisor colgado para siempre.
- El mismo registro da el **círculo de "ejecutando"** en la lista de chats, así que
  la decisión resolvió dos features con una sola pieza.

## Alternativas descartadas

1. **Preguntar al modelo.** Un turno "final" es indistinguible de una pausa pensando.
   Añade latencia y coste, y falla justo en los casos que importa (turnos largos con
   herramientas).
2. **Esperar inactividad (debounce).** Un `bash sleep 30` es inactividad y turno
   vivo a la vez. Impossible de distinguir sin heurísticas frágiles.
3. **Preguntar a la base de datos de OpenCode.** `outcome` es un estado terminal
   (`succeeded`/`failed`/`interrupted`), no un estado de ocupación: sirve para el
   pasado, no para el ahora.

## Verificación

En caliente, con una sesión real y un `bash sleep 25`:

```
t+5s   -> ocupada
t+10s  -> ocupada
t+15s  -> ocupada
t+20s  -> ocupada
t+25s  -> ocupada
final  -> liberada, turnOver=true
```

Sin la suscripción, un turno largo se habría reportado como terminado a los
segundos.
