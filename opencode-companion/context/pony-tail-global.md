# PONY-TAIL CONTEXTO GLOBAL (ESTÁTICO E INMUTABLE)
**Sistema:** OpenCode Companion Environment  
**Ubicación:** `/sdcard/projects/opencode-companion/context/pony-tail-global.md`  
**Nivel de Acceso:** Inmutable — Archivo base del sistema. La IA tiene estrictamente prohibido editar o sobreescribir este archivo automáticamente.

---

## 1. Entorno de Ejecución Base
- **Sistema Operativo Host:** Android 16 (crDroid) sobre dispositivo físico Xiaomi POCO F3 (`alioth`).
- **Contenedor / Runtime:** Ubuntu Chroot (Linux `aarch64`) ejecutado desde Termux en el espacio de usuario.
- **Acceso Privilegiado:** Capacidades completas de Superusuario (`ROOT` vía Magisk / KernelSU / APatch).
  - Comandos en namespace global: `nsenter -t 1 -m -- <comando>` o `su -c "<comando>"`.
  - Herramientas del sistema disponibles: `/system/bin/am`, `/system/bin/pm`, `/system/bin/input`, `settings`, `dumpsys`.
- **APIs de Termux:** Acceso a binarios de integración Android en `/data/data/com.termux/files/usr/bin/` (`termux-clipboard-*`, `termux-notification`, `termux-vibrate`, etc.).

---

## 2. Servicios del Ecosistema y Puertos
- **Hub Gateway (`:8765`):** Servidor HTTP / API REST y enrutador multiproveedor (`/sdcard/projects/opencode-companion/server.js`).
- **Artemis Bridge de Accesibilidad (`:8766`):**
  - Servicio HTTP local (`http://127.0.0.1:8766`) conectado a `OpencodeAccessibilityService`.
  - Endpoints disponibles:
    - `GET /status` — Verifica salud del servicio y estado del paquete en primer plano.
    - `GET /dump` — Vuelca el árbol jerárquico completo de nodos accesibles de la pantalla activa.
    - `POST /a11y` — Acciones táctiles automatizadas: `clickText`, `clickById`, `tap` (`{x, y}`), `setText`, `pressBack`, `pressHome`.
- **OpenCode Serve (`:4096`):** Runtime de inferencia y herramientas de codificación OpenCode.
- **Antigravity CLI:** Herramienta de orquestación de agentes Google Antigravity (`/root/.local/bin/agy`).

---

## 3. Herramientas de Análisis y Memoria
- **Herramientas AST (Graphify):**
  - Binario: `/root/.local/bin/graphify`.
  - Comandos de indexación:
    - `/root/.local/bin/graphify extract <directorio> --code-only` — Extrae AST sin llamadas LLM externas.
    - Salidas almacenadas en `<directorio>/graphify-out/graph.json`.
- **Gestión de Memoria y Contexto:**
  - Inyección de memoria persistente mediante Zen y archivos `.ponytail.md` locales en la raíz de cada proyecto.
  - Almacén de proyectos y títulos: `/sdcard/projects/opencode-companion/projects.json`.

---

## 4. Reglas Estrictas de Seguridad y Operación (CRÍTICO)
Cualquier asistente de IA (OpenCode o Antigravity) debe acatar obligatoriamente las siguientes restricciones:

1. ⛔ **PROHIBIDO REINICIAR EL DISPOSITIVO:**
   - Jamás ejecutar comandos de apagado o reinicio (`reboot`, `reboot recovery`, `svc power reboot`, `setprop ctl.restart`, etc.).
2. ⛔ **PROHIBIDO MATAR PROCESOS CRÍTICOS DEL SISTEMA:**
   - No finalizar procesos del sistema Android (`zygote`, `surfaceflinger`, `system_server`, `servicemanager`, procesos de Magisk/Zygisk ni demonios del sistema).
3. ⛔ **PROHIBIDO APAGAR O MATAR LA TERMINAL / DAEMON DE FONDO:**
   - No ejecutar comandos que interrumpan la sesión ssh, el proceso de Termux, el script `keepalive.sh` ni el proceso del servidor `node server.js` a menos que sea un reinicio controlado del hub ordenado por el usuario.
4. ⛔ **INMUTABILIDAD DE ESTE ARCHIVO:**
   - Este documento (`pony-tail-global.md`) es una directriz de arquitectura base estática y **no debe ser modificado ni sobreescrito por la IA**.

---

## 5. Herencia hacia Proyectos (.ponytail.md)
Cada proyecto individual cuenta con su propio archivo `.ponytail.md` en la raíz de su carpeta de trabajo, el cual hereda todas las directrices de este entorno global y detalla los avances específicos, arquitectura, estado y funcionalidades verificadas del proyecto.
