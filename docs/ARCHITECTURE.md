# Arquitectura — Aegis

App Android (`com.aegis.hub`, Jetpack Compose) que habla directo con el servicio
registrado `opencode serve --service` (`127.0.0.1:49374`). Sin Hub intermedio
(retirado 2026-10-02), sin segundo servidor.

```
UI (Compose)            solo pinta estado y emite intenciones
   │
ViewModels              estado de pantalla (Chat, Main, ProjectDetail, …)
   │
Repos (data/repo/)      Sesiones · SesionConfig · Catalogo (TTL) — un camino c/u
   │
Costura (RutaNativa)    fachada delgada; ChatSync tras SYNC_POR_EVENTOS (F5, off)
   │
OpenCodeApi (Retrofit) · EventosServidor (SSE) · ProjectsStore · Raiz (su x2)
   │
opencode serve --service :49374 (único; ver docs/CONTRATO-OPENCODE.md)
```

- **Servidor = única verdad** de modelo/agente/título/mensajes; `ProjectsStore`
  guarda el vínculo proyecto↔sesión (OpenCode no lo conoce); prefs solo caché.
- **Éxito parcial = avisos, nunca fallo** (`Resultado.Ok` con lista; `Fallo` con motivo).
- **Un canal de sincronización** (F5 tras flag); el fin de turno solo lo dice
  `session.execution.succeeded`, nunca el fin de un segmento de texto.
- **Lo caro se pide una vez**: catálogo TTL 5 min, `su` en lote con TTL, `Raiz`
  con semáforo de 2 (ver `tools/check_hilo_principal.py` en CI).
- **Arranque (G2, medido 2026-10-10 en el dispositivo): NO hay hook en
  `/data/adb/service.d/` (solo `.zn_cleanup.sh`), ningun modulo Magisk arranca
  opencode, el Manifest no trae receiver de boot y no hay scripts Termux:boot.
  El servidor sube cuando la app lo pide (`OpenCodeLauncher.asegurarAbierto` →
  `aegis-serve.sh` desplegado a `/data/local/tmp`) o a mano; tras un reboot no
  hay nada hasta la primera apertura (corregido el credito al "hook" de V-09:
  fue la primera apertura, ~1 min tras el boot).
  Entorno fijado por `aegis-serve.sh` (ver `tools/check_serve_asset.py` en CI):
  `HOME=/root PATH=<CHROOT_PATH> SHELL=/usr/bin/bash` en chroot, `oom -1000`.
  Anti-doble: guarda `pgrep` con corchete (contar listeners `:49374`, no PIDs)
  + cerrojo `mkdir` 60 s.

Diagrama objetivo y fases: [plan de estabilización](PLAN-ESTABILIZACION-AEGIS.md §3).
Contrato vigente: [CONTRATO-OPENCODE.md](CONTRATO-OPENCODE.md).
