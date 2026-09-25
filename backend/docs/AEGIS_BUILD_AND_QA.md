# AEGIS — BUILD, INSTALL & QA MASTER PROMPT
## Compilación, Instalación y Auditoría Visual Completa

---

## CONTEXTO

**Proyecto:** Aegis — Mobile Development Hub  
**Package:** `com.aegis.hub`  
**Repo GitHub:** El remote está configurado en `/sdcard/projects/Aegis/.git/config` — lee la URL de ahí.  
**APK destino:** POCO F3 conectado via ADB (`emulator-5554 device`)  
**Backend:** `/sdcard/projects/Aegis/backend/` corriendo en puerto 8765  
**Artemis:** `/root/artemis-antigravity/` con MCP en puerto 8005  

---

## REGLAS CRÍTICAS

1. Ejecuta **UN paso a la vez** y verifica antes de continuar.
2. **NUNCA** modifiques código fuente de la app durante esta fase — solo build, install y QA.
3. Si cualquier paso falla, reporta el error completo y detente.
4. El modelo para Artemis QA es **Gemini 3.1 Pro via Antigravity** — no Flash.
5. Todos los resultados del QA deben guardarse en `/sdcard/projects/Aegis/docs/QA_REPORT.md`.

---

## PASO 1 — VERIFICAR ENTORNO

```bash
# Verificar ADB
adb devices

# Verificar que el dispositivo está online
adb shell echo "ADB OK"

# Verificar URL del repo remoto
cat /sdcard/projects/Aegis/.git/config | grep url

# Verificar estado del último push
cd /sdcard/projects/Aegis && git log --oneline -5
```

Reporta:
- Estado de ADB (`device` o `offline`)
- URL del repo remoto
- Hash del último commit pusheado

---

## PASO 2 — VERIFICAR GITHUB ACTIONS

Usando las herramientas disponibles (websearch o webfetch), accede a la URL del repo en GitHub y verifica:

```
https://github.com/<usuario>/<repo>/actions
```

Determina:
- ¿Está el workflow "Build Aegis APK" corriendo, completado o fallido?
- Si está **corriendo**: espera hasta que complete (poll cada 60s si tienes capacidad, o reporta y continúa con PASO 3 mientras espera)
- Si está **completado con éxito**: descarga el APK (PASO 4)
- Si está **fallido**: lee los logs del error y repórtalo

---

## PASO 3 — CONFIGURAR ARTEMIS PARA QA PROFUNDO

Mientras el build corre, configura Artemis para usar Gemini 3.1 Pro:

### 3.1 — Cambiar modelo default a Gemini 3.1 Pro

```bash
# Hacer backup del config original
cp /root/artemis-antigravity/config/artemis.jsonc /root/artemis-antigravity/config/artemis.jsonc.bak

# Cambiar modelo default de gemini-3.8-flash a gemini-3.1-pro
sed -i 's/"model": "gemini-3.8-flash"/"model": "gemini-3.1-pro"/g' /root/artemis-antigravity/config/artemis.jsonc

# Activar midway_checks para verificación profunda durante el QA
sed -i 's/"midway_checks": false/"midway_checks": true/' /root/artemis-antigravity/config/artemis.jsonc
```

### 3.2 — Verificar cambios

```bash
grep -E "gemini-3|midway_checks" /root/artemis-antigravity/config/artemis.jsonc | head -20
```

Debe mostrar `gemini-3.1-pro` como modelo principal y `midway_checks: true`.

### 3.3 — Montar rules.md de Artemis en el contexto

```bash
# Verificar que existe el archivo de reglas de testing
cat /root/artemis-antigravity/mcp_server/rules.md | head -30
```

Lee el archivo completo de rules.md. Estas reglas definen el comportamiento de QA — debes tenerlas en contexto para el PASO 6.

### 3.4 — Verificar que Artemis arranca correctamente con el nuevo modelo

```bash
cd /root/artemis-antigravity && python -m artemis doctor 2>&1 | head -30
```

Si hay errores de autenticación:
```bash
cd /root/artemis-antigravity && python -m artemis auth login
```

---

## PASO 4 — DESCARGAR APK DE GITHUB ACTIONS

Una vez que el workflow de GitHub Actions haya completado exitosamente:

### 4.1 — Obtener el APK via GitHub CLI o API

```bash
# Verificar si gh CLI está disponible
which gh || echo "gh not available"

# Si gh está disponible:
gh run download --repo <usuario>/<repo> --name aegis-debug-apk --dir /tmp/aegis-build/

# Si no está disponible, busca la URL de descarga del artifact en la página de Actions
# y usa wget o curl:
# wget -O /tmp/aegis-debug.apk "<URL_DEL_ARTIFACT>"
```

Si `gh` no está disponible, instálalo:
```bash
# Instalar GitHub CLI en Ubuntu
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list > /dev/null
apt update && apt install gh -y

# Autenticar con el token de GitHub ya configurado
gh auth status
```

### 4.2 — Verificar el APK descargado

```bash
ls -la /tmp/aegis-build/*.apk 2>/dev/null || ls -la /tmp/aegis-debug.apk 2>/dev/null

# Verificar que el APK es válido
file /tmp/aegis-build/*.apk 2>/dev/null || file /tmp/aegis-debug.apk
```

---

## PASO 5 — INSTALAR APK EN EL DISPOSITIVO

### 5.1 — Verificar dispositivo antes de instalar

```bash
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

### 5.2 — Desinstalar versión anterior si existe

```bash
# Desinstalar versión anterior de Aegis si existe
# (los builds históricos usaban un applicationId legado distinto; si persistiera
#  una copia antigua, desinstálala a mano desde Ajustes > Apps)
adb uninstall com.aegis.hub 2>/dev/null || echo "com.aegis.hub not installed - first install"
```

### 5.3 — Instalar APK

```bash
APK_PATH=$(find /tmp -name "*.apk" | head -1)
echo "Installing: $APK_PATH"
adb install -r "$APK_PATH"
```

Debe mostrar: `Success`

### 5.4 — Verificar instalación

```bash
adb shell pm list packages | grep aegis
adb shell pm path com.aegis.hub
```

### 5.5 — Lanzar la app

```bash
adb shell monkey -p com.aegis.hub -c android.intent.category.LAUNCHER 1
sleep 3
adb shell screencap -p /sdcard/aegis-launch.png
adb pull /sdcard/aegis-launch.png /tmp/aegis-launch.png
```

Analiza visualmente el screenshot: ¿La app arrancó correctamente? ¿Muestra la pantalla principal?

---

## PASO 6 — LEVANTAR EL BACKEND

Antes del QA, asegúrate de que el backend está corriendo:

```bash
# Verificar si ya está corriendo
curl -s http://127.0.0.1:8765/api/system/health | head -100

# Si no está corriendo, iniciarlo en background
cd /sdcard/projects/Aegis/backend && node server.js &
sleep 3

# Verificar que levantó
curl -s http://127.0.0.1:8765/api/system/health
```

El health check debe retornar `{"ok":true,"data":{...}}`.

---

## PASO 7 — QA CON ARTEMIS (PERFIL PRO + GEMINI 3.1 PRO)

### IMPORTANTE ANTES DE EJECUTAR:
- Usa **SIEMPRE** `--profile pro` — nunca Flash para este análisis
- El modelo debe ser `gemini-3.1-pro` (configurado en PASO 3)
- Cada test genera su propio reporte — concaténalos al final en QA_REPORT.md
- Si un test falla con error de Artemis (no de la app), reintenta una vez antes de reportar

### 7.1 — Verificar conexión de Artemis con el dispositivo

```bash
cd /root/artemis-antigravity
python -m artemis doctor
python -m artemis helper install 2>/dev/null || echo "Helper already installed"
```

### 7.2 — TEST-01: Navegación General y Nombre de la App

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "Abre la aplicación Aegis en el dispositivo. Verifica: (1) que el nombre mostrado en la pantalla o en el launcher es 'Aegis' y NO 'Open Code Companion', (2) que la pantalla principal carga sin errores, (3) que el menú de navegación (drawer o bottom nav) es visible y contiene al menos estas secciones: Control Center, Projects, Skills, Chat. Toma screenshot de cada estado. Reporta cualquier texto que diga 'Open Code Companion' como BUG CRITICAL." \
  --profile pro \
  --verification-level strict
```

### 7.3 — TEST-02: Control Center

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "En la app Aegis, navega a la pantalla 'Control Center'. Verifica detalladamente: (1) que la pantalla carga datos reales del servidor (no pantalla en blanco), (2) que el indicador de estado del servidor muestra ONLINE en verde (#3FB950) o OFFLINE en rojo (#F85149), (3) que las métricas (memoria, proyectos, agentes, jobs) tienen valores numéricos visibles, (4) que las fuentes son monospace y el fondo es oscuro (~#181816), (5) que ningún elemento está cortado o fuera de los límites de pantalla, (6) que el botón de refresh es visible y funciona al tocarlo. Reporta: PASSED si todo OK, o lista de bugs con severidad CRITICAL/HIGH/MEDIUM/LOW para cada problema encontrado." \
  --profile pro \
  --verification-level strict
```

### 7.4 — TEST-03: Skill Manager

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "En la app Aegis, navega a la pantalla 'Skills' o 'Skill Manager'. Verifica: (1) que la pantalla carga una lista de skills (instaladas y/o disponibles), (2) que cada skill card muestra nombre y descripción legibles, (3) que los botones INSTALAR y DESINSTALAR son visibles y tienen tamaño táctil adecuado (mínimo 48dp de altura), (4) que no hay overflow de texto en las cards, (5) que el scroll funciona si hay más de 3 skills. Intenta tocar el botón de instalar de cualquier skill disponible y verifica que aparece algún feedback visual (overlay, progress, o mensaje). Reporta bugs con severidad." \
  --profile pro \
  --verification-level strict
```

### 7.5 — TEST-04: Project Workspace

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "En la app Aegis, navega a la pantalla 'Projects' o 'Workspace'. Verifica: (1) que la pantalla lista los proyectos disponibles en el workspace, (2) que cada proyecto card muestra nombre y path, (3) que los badges HUB/NO HUB son visibles y distinguibles, (4) que los botones de acción (ABRIR, INIT HUB, INDEXAR, WORKFLOWS) son visibles y están alineados correctamente, (5) que el scroll funciona en la lista, (6) que el buscador/filtro si existe responde al input. Toca el botón ABRIR de cualquier proyecto y verifica que navega correctamente. Reporta bugs con severidad." \
  --profile pro \
  --verification-level strict
```

### 7.6 — TEST-05: Workflow Screen

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "En la app Aegis, navega a Projects, selecciona cualquier proyecto disponible, luego toca el botón WORKFLOWS. Verifica: (1) que la pantalla de Workflow carga mostrando los workflows disponibles, (2) que cada workflow card muestra nombre y número de steps, (3) que el botón EJECUTAR es visible, (4) que al tocar EJECUTAR aparece un panel de progreso o algún feedback visual, (5) que el pipeline visual (Step1 → Step2 → Step3) es legible si aparece. Reporta bugs con severidad." \
  --profile pro \
  --verification-level strict
```

### 7.7 — TEST-06: Chat Original (Regresión)

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "En la app Aegis, navega a la sección Chat (la pantalla original). Verifica que: (1) la pantalla de chat carga correctamente, (2) el campo de texto para escribir mensajes es visible y responde al teclado, (3) el cursor parpadeante está visible, (4) el estilo visual (fondo #181816, texto claro, fuente monospace) se mantiene igual que antes, (5) el selector de modelos/proveedor es accesible. Esta es una prueba de REGRESIÓN — la pantalla de chat NO debe haber sido alterada. Reporta cualquier cambio visual o funcional como BUG HIGH." \
  --profile pro \
  --verification-level strict
```

### 7.8 — TEST-07: Consistencia Visual Global

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "Navega por TODAS las pantallas de la app Aegis en este orden: Control Center → Skills → Projects → Workflows → Chat. En cada pantalla verifica y documenta: (1) color de fondo (debe ser ~#181816 oscuro en todas), (2) color de texto principal (debe ser claro ~#E6EDF3), (3) fuente monospace consistente, (4) que no hay elementos cortados en los bordes de pantalla, (5) que los botones tienen tamaño táctil adecuado, (6) que no hay textos superpuestos, (7) que la barra de navegación es consistente en todas las pantallas. Toma screenshot de cada pantalla. Genera una tabla de consistencia visual: pantalla vs criterio (PASS/FAIL)." \
  --profile pro \
  --verification-level strict
```

### 7.9 — TEST-08: Manejo de Errores de Red

```bash
# Primero detener el backend para simular error
kill $(lsof -t -i:8765) 2>/dev/null || true
sleep 2

cd /root/artemis-antigravity
python -m artemis run \
  "El backend de Aegis está temporalmente desconectado. Abre la app Aegis y navega a Control Center. Verifica: (1) que la app NO crashea (no hay force close), (2) que muestra algún mensaje de error o estado OFFLINE en lugar de pantalla en blanco, (3) que el indicador de estado muestra rojo o algún indicador de error, (4) que la app sigue siendo navegable a otras pantallas aunque no haya datos. Luego navega a Projects y verifica el mismo comportamiento. Reporta cualquier crash como BUG CRITICAL." \
  --profile pro \
  --verification-level strict

# Volver a levantar el backend después del test
cd /sdcard/projects/Aegis/backend && node server.js &
sleep 3
```

### 7.10 — TEST-09: Accesibilidad y Tamaños Táctiles

```bash
cd /root/artemis-antigravity
python -m artemis run \
  "Realiza un análisis de accesibilidad completo de la app Aegis. Usa el árbol de accesibilidad (XML hierarchy) para verificar en TODAS las pantallas: (1) que todos los botones tienen content description o texto visible, (2) que los elementos interactivos tienen tamaño mínimo de 48x48dp, (3) que no hay elementos superpuestos que bloqueen la interacción, (4) que el contraste de texto sobre fondo es suficiente para legibilidad, (5) identifica cualquier elemento que sea visualmente visible pero no aparezca en el árbol de accesibilidad (elementos Canvas o elementos con importanceForAccessibility=no). Reporta hallazgos con severidad." \
  --profile pro \
  --verification-level strict
```

---

## PASO 8 — GENERAR REPORTE FINAL

Después de completar todos los tests, genera el reporte consolidado:

```bash
cat > /sdcard/projects/Aegis/docs/QA_REPORT.md << 'REPORT'
---
version: 1.0.0
date: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
owned_by: AuditorAgent
state: FINAL
model_used: gemini-3.1-pro (Antigravity)
profile: pro
verification_level: strict
---

# AEGIS QA REPORT — v1.0.0

> 📄 **Documento histórico (snapshot).** Describe el estado del proyecto en el momento
> en que se escribió y **no se mantiene al día**. Para el estado actual ver
> `CHANGELOG.md`, `docs/ARCHITECTURE.md` y `backend/.ponytail.md`.

## Executive Summary
[COMPLETAR CON RESUMEN DE RESULTADOS]

## Test Results

| Test ID | Pantalla | Estado | Bugs Encontrados |
|---------|----------|--------|-----------------|
| TEST-01 | Navegación General | | |
| TEST-02 | Control Center | | |
| TEST-03 | Skill Manager | | |
| TEST-04 | Project Workspace | | |
| TEST-05 | Workflow Screen | | |
| TEST-06 | Chat (Regresión) | | |
| TEST-07 | Consistencia Visual | | |
| TEST-08 | Manejo de Errores | | |
| TEST-09 | Accesibilidad | | |

## Bug Inventory

### CRITICAL
[Lista de bugs críticos]

### HIGH
[Lista de bugs altos]

### MEDIUM
[Lista de bugs medios]

### LOW
[Lista de bugs bajos]

## Screenshots
[Referencias a screenshots capturados por Artemis]

## Recommendations
[Recomendaciones de fixes priorizados]

REPORT
```

Rellena el reporte con los resultados reales de cada test.

Luego haz commit del reporte:

```bash
cd /sdcard/projects/Aegis
git add docs/QA_REPORT.md
git commit -m "docs: add QA_REPORT.md from Artemis Pro audit v1.0.0"
git push origin main
```

---

## PASO 9 — RESTAURAR ARTEMIS CONFIG (OPCIONAL)

Si quieres restaurar Artemis a Flash para uso cotidiano después del QA:

```bash
cp /root/artemis-antigravity/config/artemis.jsonc.bak /root/artemis-antigravity/config/artemis.jsonc
echo "Artemis config restored to original"
```

---

## ENTREGA FINAL ESPERADA

Al terminar este prompt la IA debe haber:

1. ✅ Verificado que el APK compiló correctamente en GitHub Actions
2. ✅ Descargado e instalado `com.aegis.hub` en el POCO F3
3. ✅ Configurado Artemis con Gemini 3.1 Pro para análisis profundo
4. ✅ Ejecutado 9 tests de QA con perfil Pro y verificación strict
5. ✅ Generado `/sdcard/projects/Aegis/docs/QA_REPORT.md` con todos los resultados
6. ✅ Commiteado y pusheado el reporte a GitHub

---

## NOTAS PARA EL AGENTE

- Si GitHub Actions no ha terminado cuando llegues al PASO 2, ejecuta los PASOS 3 y parte del 6 mientras esperas, luego vuelve al PASO 4
- Si el APK no se puede descargar automáticamente, reporta la URL de descarga para que el usuario la descargue manualmente
- Si Artemis falla en algún test con error de conexión ADB, ejecuta `adb kill-server && adb start-server` y reintenta
- El backend DEBE estar corriendo antes de los tests del PASO 7 — verifica siempre con `curl http://127.0.0.1:8765/api/system/health` antes de cada test
- Si un test de Artemis tarda más de 5 minutos sin respuesta, cancela con Ctrl+C y reporta timeout
- **NO uses `--profile flash`** en ningún test — siempre `--profile pro`

---

*Aegis — Build, Install & QA Master Prompt*  
*Versión: 1.0*  
*Fecha: 2026-09-22*
