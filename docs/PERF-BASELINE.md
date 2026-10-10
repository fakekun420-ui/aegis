# PERF-BASELINE — cifras "antes" (F0, T-F0.5)

Medir con el APK **debug** de la rama `estabilizar/F0-base` (lleva el contador
`AegisTrace`): `adb logcat -s AegisTrace` y copiar las líneas tal cual. Nada de
estimaciones: cada celda lleva la línea de logcat o queda vacía.

| Métrica | Antes (F0) | Meta | Después |
|---|---|---|---|
| Peticiones al abrir un chat existente | _(línea AegisTrace)_ | ≤ 3 | no medido: §0 (pantalla no tocada sin OK) |
| Peticiones/min con chat abierto en reposo | _(linea AegisTrace)_ | ≤ 4 | no medido en reposo; en turno activo: ~20 `session/active` por 90 s (AegisTrace 13:45-13:46, no es reposo) |
| Peticiones al enviar y recibir una respuesta corta | _(línea AegisTrace)_ | ≤ 8 | no medido: §0 (pantalla no tocada sin OK) |
| `GET api/model` por sesión de uso de 10 min | _(linea AegisTrace)_ | ≤ 2 | 1 por ventana de 90 s en turno activo (AegisTrace 13:45-13:46) |
| `su` al abrir Skills | _(log de RootShell / StrictMode)_ | 1 | no medido: §0 (pantalla no tocada sin OK) |
| Procesos `serve` tras 5 aperturas (`pgrep -f '[o]pencode(.exe)? serve --service' \| wc -l`) | | 1 | 2 PIDs estables (par del supervisor Termux, 1 listener :49374); la app no lanzo un tercero en 6 arranques |
| Archivos > 700 líneas en `ui/`+`data/` | 6 (plan §5) | ≤ 2 (tras F9; hoy 5 medidos 2026-10-10: ChatScreen 2058, RutaNativa 1546, ChatViewModel 1445, MarkdownText 1024, SetupWizardScreen 865) | 5 medidos 2026-10-10 (los de arriba; F9 pendiente, no es hecho) |
| `catch` vacíos en `ui/viewmodel/` | _(salida del grep del plan §9)_ | 0 | 2, ambos con marcador GUARD-SILENCIO-OK y motivo (poll/stream con respaldo) |
| ANR en 1 h de uso (`/data/anr` + logcat `ANR in com.aegis.hub`) | | 0 | 0 (logcat desde instalacion + `/data/anr` sin entradas aegis) |

Estado: 🟡 pendiente de medición en el móvil (lo hace Leonardo; la IA no toca la
pantalla sin OK explícito).
