# Arquitectura — Aegis

App Android (`com.aegis.hub`, Jetpack Compose) que habla directo con el servicio
registrado `opencode serve --service` (`127.0.0.1:49374`). Sin Hub intermedio
(retirado 2026-10-02), sin segundo servidor.

```
UI (Compose)            solo pinta estado y emite intenciones
   │
ViewModels              estado de pantalla (Chat, Main, ProjectDetail, …)
   │
Costura (RutaNativa)    traduce app ↔ OpenCode; fachada que F2+ adelgaza a repos
   │
OpenCodeApi (Retrofit) · EventStream (SSE) · ProjectsStore · RootShell (su)
   │
opencode serve --service :49374 (único; ver docs/CONTRATO-OPENCODE.md)
```

- **Servidor = única verdad** de modelo/agente/título/mensajes; `ProjectsStore`
  guarda el vínculo proyecto↔sesión (OpenCode no lo conoce); prefs solo caché.
- **Arranque:** `OpenCodeLauncher.asegurarAbierto` + hook Magisk `service.d`
  (F6 lo deja en un solo lanzador).
- **Sincronización del chat:** poll + SSE durante el envío (F5 lo deja en un canal).
- **Skills/salud del sistema:** lectura por `su` en lote (F7: 1 exec).

Diagrama objetivo y fases: [plan de estabilización](PLAN-ESTABILIZACION-AEGIS.md §3).
Contrato vigente: [CONTRATO-OPENCODE.md](CONTRATO-OPENCODE.md).
