# PERF-BASELINE — cifras "antes" (F0, T-F0.5)

Medir con el APK **debug** de la rama `estabilizar/F0-base` (lleva el contador
`AegisTrace`): `adb logcat -s AegisTrace` y copiar las líneas tal cual. Nada de
estimaciones: cada celda lleva la línea de logcat o queda vacía.

| Métrica | Antes (F0) | Meta | Después |
|---|---|---|---|
| Peticiones al abrir un chat existente | _(línea AegisTrace)_ | ≤ 3 | |
| Peticiones/min con chat abierto en reposo | _(línea AegisTrace)_ | ≤ 4 | |
| Peticiones al enviar y recibir una respuesta corta | _(línea AegisTrace)_ | ≤ 8 | |
| `GET api/model` por sesión de uso de 10 min | _(línea AegisTrace)_ | ≤ 2 | |
| `su` al abrir Skills | _(log de RootShell / StrictMode)_ | 1 | |
| Procesos `serve` tras 5 aperturas (`pgrep -f '[o]pencode serve --service' \| wc -l`) | | 1 | |
| Archivos > 700 líneas en `ui/`+`data/` | 6 (plan §5) | ≤ 2 (tras F9) | |
| `catch` vacíos en `ui/viewmodel/` | _(salida del grep del plan §9)_ | 0 | |
| ANR en 1 h de uso (`/data/anr` + logcat `ANR in com.aegis.hub`) | | 0 | |

Estado: 🟡 pendiente de medición en el móvil (lo hace Leonardo; la IA no toca la
pantalla sin OK explícito).
