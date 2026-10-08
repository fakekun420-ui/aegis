# Aegis App (Android)

App Android (`com.aegis.hub`) que habla directo con OpenCode (`127.0.0.1:49374`).
Sin Hub (`:8765` retirado 2026-10-02): todo lo que aquí diga `hub`, `8765`,
`X-Provider` o `X-Aegis-Token` es historia.

> Contrato vigente: [`../docs/CONTRATO-OPENCODE.md`](../docs/CONTRATO-OPENCODE.md) ·
> Lanzamiento: [`../docs/QUICKSTART.md`](../docs/QUICKSTART.md)

## Qué hace

- **Chat** con OpenCode: modelos, agentes, formularios y permisos respondibles,
  streaming parcial, divisor de fin de turno real.
- **Proyectos** vinculados a carpetas bajo `/sdcard/projects` (`ProjectsStore`).
- **Skills / Workspace / Workflow / Control Center**, TTS/STT, **Setup Wizard**
  de 6 pasos (bootstrap + verificación + smoke test).
- **Accesibilidad + voz**: `AccessibilityService` (click/tap/dump) y
  `SpeechRecognizer`/TTS nativos + shell por `su` (`:8766` loopback).

## Instalar en el dispositivo

```sh
# con root (instala + permisos + a11y + arranque del servicio)
su -c "sh /sdcard/projects/Aegis/app/install-su.sh"

# o a mano
adb install -r app-release.apk
```

El debug de CI se firma con clave desechable: **desinstalar el debug anterior
antes de instalar** (se pierden `SharedPreferences`; desde F3 ya no rompe nada
porque el servidor es la verdad). Instalar por `pm` copiando antes a
`/data/local/tmp`.

Tras instalar: abre **Aegis** → `Activar accesibilidad` → habilita el servicio.

## Compilar

Sin toolchain en el host: compila la CI (`Build Aegis APK` → artifacts
`aegis-debug` / `aegis-release`). Desde `app/` con SDK local:
`gradle assembleDebug` (`versionName 1.1.2`).

## Boot autostart

Hook Magisk (ya instalado en el dispositivo — no renombrar):
`/data/adb/service.d/99-opencode-hub.sh` → lanza `opencode serve --service`.

## Estructura

- `app/src/main/kotlin/com/aegis/hub/` — `MainActivity.kt`, `RootShell.kt`
- `app/src/main/kotlin/com/aegis/hub/{data,ui,util}/` — costura, pantallas, VMs
- `../scripts/` — `install-su.sh`, lanzador versionado (F6)
- `docs/` — este puntero sustituye al viejo `FRONTEND_CONTRACT.md`
