# PONY-TAIL CONTEXTO GLOBAL (ESTÁTICO E INMUTABLE)
**Sistema:** OpenCode Companion Environment  
**Ubicación:** `/sdcard/projects/opencode-companion/context/pony-tail-global.md`  
**Última Modificación y Cierre:** 2026-09-21  
**Nivel de Acceso:** 🔒 INMUTABLE Y PERMANENTE TRAS HOY. La IA tiene estrictamente prohibido editar, modificar o sobreescribir este archivo en el futuro.

---

## 1. Entorno de Ejecución Real y Host
- **Dispositivo Host Físico:** Xiaomi POCO F3 (`alioth`).
- **Sistema Operativo Base:** Android 15.
- **Contenedor / Espacio de Trabajo:** Ubuntu Chroot (`aarch64`) ejecutado desde Termux.
- **Acceso Privilegiado:** Capacidades completas de Superusuario (`ROOT` vía Magisk / KernelSU / APatch).
  - Ejecución en namespace global de montaje: `nsenter -t 1 -m -- <comando>` o `su -c "<comando>"`.
  - Herramientas del sistema Android: `/system/bin/am`, `/system/bin/pm`, `/system/bin/input`, `settings`, `dumpsys`.
- **Integración Termux:** Binarios y herramientas en `/data/data/com.termux/files/usr/bin/` (`termux-clipboard-*`, `termux-notification`, `termux-vibrate`, etc.).

---

## 2. Servicios del Ecosistema y Puertos de Red
- **Hub Gateway (`:8765`):** Servidor HTTP / API REST y enrutador multiproveedor (`/sdcard/projects/opencode-companion/server.js`).
- **OpenCode Serve (`:4096`):** Runtime de inferencia y herramientas de codificación OpenCode (`127.0.0.1:4096`).
- **Google Antigravity CLI:** Herramienta de agentes Antigravity (`/root/.local/bin/agy`).
- **Artemis Bridge de Accesibilidad (`:8766`):**
  - Servidor HTTP local (`http://127.0.0.1:8766`) conectado a `OpencodeAccessibilityService` / `ArtemisAccessibilityService`.
  - Endpoints disponibles: `GET /status`, `GET /dump`, `POST /a11y` (`clickText`, `clickById`, `tap`, `setText`, `pressBack`, `pressHome`).

---

## 3. Herramientas de Análisis de Código, AST y Memoria
- **Herramientas AST (Graphify):**
  - Binario: `/root/.local/bin/graphify`.
  - Extracción de dependencias y grafos sin llamadas externas:
    `/root/.local/bin/graphify extract <directorio> --code-only`.
  - Salidas generadas en `<directorio>/graphify-out/graph.json`.
- **Repositorio de Memoria y Skills:**
  - Inyección de memoria y contexto mediante Zen, skills en `.opencode/skills/` y `/root/.config/opencode/skills/`.
  - Almacén de proyectos y títulos inmutables: `/sdcard/projects/opencode-companion/projects.json`.
  - Contexto de proyecto local: `.ponytail.md` en la raíz de cada proyecto de trabajo.

---

## 4. Reglas Estrictas de Seguridad Operacional (CRÍTICO)
Cualquier asistente de IA (OpenCode o Antigravity) debe acatar obligatoriamente y sin excepciones las siguientes restricciones:

1. ⛔ **PROHIBIDO REINICIAR EL DISPOSITIVO:**
   - Jamás ejecutar comandos de apagado o reinicio (`reboot`, `reboot recovery`, `svc power reboot`, `setprop ctl.restart`, etc.).
2. ⛔ **PROHIBIDO MODIFICAR O BORRAR ARCHIVOS DE ANDROID / VENDOR:**
   - Estrictamente prohibido modificar particiones de sistema, archivos en `/system`, `/vendor`, `/product`, `/system_ext`, `/apex`, `/data/adb/modules` o bibliotecas del SO para prevenir fallos críticos, soft-brick o bootloops.
3. ⛔ **PROHIBIDO MATAR PROCESOS CRÍTICOS DEL SISTEMA O DAEMONS:**
   - No finalizar procesos de Android (`zygote`, `surfaceflinger`, `system_server`, servicios de accesibilidad).
   - No matar la terminal de Termux, la sesión SSH ni el proceso supervisor `keepalive.sh`.
4. 🔒 **INMUTABILIDAD ABSOLUTA:**
   - Este archivo ha sido verificado y auditado hoy (2026-09-21) sobre el entorno real. No debe ser modificado por la IA en ninguna sesión posterior.
