#!/bin/bash
# ==============================================================================
# OpenCode Companion - Publicación de Facebook (Copiado automático al portapapeles)
# Objetivo: Hub unificado para agentes (OpenCode, Antigravity, Claude Code, etc.)
# Dispositivo: Xiaomi POCO F3 | Android 15 | Ubuntu Chroot aarch64 | Root
# ==============================================================================

POST_TEXT=$(cat << 'EOF'
¿Es posible convertir un smartphone en un centro de comando autónomo de desarrollo e inteligencia artificial? 🚀📱🔥

Les comparto el proyecto en el que he estado trabajando: OpenCode Companion.

Siempre me ha llamado la atención cómo dependemos de una laptop o servidores remotos para programar con IA. Además, hoy en día existen herramientas increíbles como OpenCode, Google Antigravity o Claude Code, pero utilizarlas suele ser caótico: terminales separadas, configuraciones dispersas y una experiencia móvil prácticamente nula o muy incómoda.

Por eso decidí llevar la ingeniería al límite y crear una solución definitiva.

🎯 ¿CUÁL ES EL OBJETIVO DE OPENCODE COMPANION?
El objetivo principal es crear una interfaz amigable, cómoda y visualmente atractiva para juntar en un mismo lugar distintos agentes de IA como OpenCode, Google Antigravity, Claude Code, etc., unificando todo el flujo de desarrollo en el móvil:

1. 🤝 Hub Unificado Multi-Agente: Terminar con la fragmentación. En lugar de lidiar con múltiples consolas o ventanas sueltas, tienes un centro de control unificado donde puedes seleccionar y alternar el agente ideal según la tarea (planificación profunda, refactorización de código, depuración o ejecución de tareas del sistema).
2. 📱 Experiencia Móvil Cómoda y Ergonómica: Reemplazar el dolor de escribir comandos en teclados virtuales de terminales diminutas por una interfaz nativa pensada para desarrolladores: bloques de código resaltados, copiado al portapapeles con un solo toque, streaming visual fluido y gestión clara de proyectos.
3. 🚀 Emancipación Real de la PC: Permitir que los agentes no sean simples "chatbots que sugieren texto", sino agentes autónomos activos capaces de interactuar con el entorno Linux local, auditar archivos, compilar aplicaciones e interactuar con la pantalla de Android, todo corriendo 100% en el bolsillo.

📱 EL APK QUE CREAMOS (FRONTEND NATIVO)
Para lograr esta comodidad, construimos una app Android desde cero:
• 🛠️ 100% Kotlin 2.0 y Jetpack Compose (BOM 2024).
• 🎨 Estética "Warm Dark" inspirada en Claude (#181816 con acentos terracota #D97757).
• ⚡ Streaming en tiempo real estilo terminal wizard con cursor interactivo parpadeante (▋).
• 💻 Bloques de código con resaltado sintáctico multilingüe y botón de copiado rápido con feedback visual (¡Copiado!).
• 🔀 Selector dinámico en caliente para alternar entre agentes y modelos: Google Antigravity (Gemini 3.8 Flash High) y OpenCode Zen.
• 🔒 Organización por proyectos, títulos automáticos inteligentes y modo "Plan" silencioso sin ensuciar los prompts.

⚙️ ¿DÓNDE ESTÁ MONTADO? (EL STACK BAJO EL CAPÓ)
Todo se ejecuta localmente en el propio teléfono:
• 📱 Dispositivo: Xiaomi POCO F3 (alioth).
• 🤖 Sistema Operativo: Android 15 (crDroid).
• 🐧 Espacio de trabajo Linux: Ubuntu Chroot (aarch64) orquestado desde Termux.
• 🔓 Acceso Superusuario: ROOT completo con namespace global de montaje (nsenter), permitiendo que la IA acceda a herramientas nativas de Android (am, pm, input, settings, dumpsys).

🔌 ARQUITECTURA DE MICROSERVICIOS LOCALES:
1. Hub Gateway (:8765): Servidor Node.js 24 que centraliza las sesiones, abstrae los distintos adaptadores de agentes y multiplexa las llamadas.
2. OpenCode Serve (:4096): Motor local de inferencia y herramientas de codificación.
3. Google Antigravity CLI (agy): Motor de agentes autónomos para refactorización profunda, razonamiento y ejecución de código.
4. Artemis Accessibility Bridge (:8766): Puente HTTP conectado al servicio de accesibilidad de Android. ¡La IA puede leer la pantalla del teléfono, hacer taps, escribir texto y navegar por aplicaciones de Android de forma autónoma!
5. Supervisor keepalive: Daemon vigilante que garantiza tolerancia a fallos y reinicios automáticos ante cualquier interrupción.

📸 EN LAS CAPTURAS ADJUNTAS PUEDEN VER:
1. La app generando código en tiempo real con streaming y bloques formateados.
2. El selector de agentes y modelos en la interfaz Warm Dark.
3. La terminal corriendo el chroot de Ubuntu con los puertos y servicios activos (:8765, :4096, :8766).
4. El sistema en acción interactuando con el entorno Android.

¿Qué opinan de centralizar agentes como OpenCode, Antigravity y Claude Code en una sola app móvil conectada a Linux? ¿Han intentado algo similar en Android o Termux? Los leo en los comentarios. 👇💬

#AndroidDev #JetpackCompose #Termux #Linux #Ubuntu #OpenCode #Antigravity #ClaudeCode #Gemini #CodingAgent #AI #Kotlin #Root #XiaomiPocoF3 #MobileDev #OpenSource #TechShowcase
EOF
)

CLIP_BIN="/data/data/com.termux/files/usr/bin/termux-clipboard-set"

if [ -x "$CLIP_BIN" ]; then
    printf "%s" "$POST_TEXT" | "$CLIP_BIN"
    echo "✅ ¡Texto copiado exitosamente al portapapeles de Android vía Termux!"
elif command -v termux-clipboard-set >/dev/null 2>&1; then
    printf "%s" "$POST_TEXT" | termux-clipboard-set
    echo "✅ ¡Texto copiado exitosamente al portapapeles de Android vía termux-clipboard-set!"
else
    echo "ℹ️ Mostrando texto completo en pantalla:"
    echo "------------------------------------------------------------"
    printf "%s\n" "$POST_TEXT"
    echo "------------------------------------------------------------"
fi
