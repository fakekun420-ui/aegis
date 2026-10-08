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
- **Arranque:** `OpenCodeLauncher.asegurarAbierto` + hook Magisk `service.d`
  (F6 lo deja en un solo lanzador).

Diagrama objetivo y fases: [plan de estabilización](PLAN-ESTABILIZACION-AEGIS.md §3).
Contrato vigente: [CONTRATO-OPENCODE.md](CONTRATO-OPENCODE.md).
