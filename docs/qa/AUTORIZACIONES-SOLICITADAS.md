# Autorizaciones solicitadas (plan siguiente, §2) — 2026-10-10

Una sola petición agrupada. Sin respuesta, no se toca pantalla ni servidor.

| Id | Qué necesito | Para qué métrica | Duración estimada | Riesgo | Qué NO tocaré |
|---|---|---|---|---|---|
| A-1 | Pantalla ~15 min: abrir 3 chats distintos, abrir Skills, dejar un chat abierto 60–120 s en reposo, enviar 1 mensaje corto | E3 (chat.abrir), E4 (reposo:60s), E8 (skills su), `GET api/model`/10 min vía marcas G1 | 15 min | Bajo: solo abrir/mirar | Servidor, keystore, `app/state` |
| A-2 | Pantalla ~20 min + 1 mini-build: primero implemento el extra de intent debug `sync_eventos` (hoy no existe; 1 commit + CI + install), luego V-06 (turno largo `bash`) y V-08 (corte/vuelta) con el flag en `true`, más E4 comparativo | Decisión G3 (`SYNC_POR_EVENTOS` true/false con evidencia) | 20 min + CI | Medio: build debug + corte controlado con relanzamiento inmediato | Release (flag solo debug), keystore |
| A-3 | Reiniciar el servidor | Solo si A-2 exige V-08-con-SSE y el corte controlado no basta | 2 min | Corta turnos en vuelo: listo sesiones activas antes y pido ventana | Nada más; lo dejo con 1 listener en `:49374` |
