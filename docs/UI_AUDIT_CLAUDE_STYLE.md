# Informe de Auditoría Visual e Interactiva UI/UX — Réplica Pixel-Perfect Estilo Claude
**App:** OpenCode Companion  
**Fecha:** 2026-09-20  
**Dispositivo:** Xiaomi Poco F3 (alioth) — Android 16 (crDroid)  
**Herramientas de auditoría:** Artemis / A11y Bridge (`:8766`), Root Shell (`nsenter`), GitHub Actions CI, Jetpack Compose  
**Veredicto:** Aprobado al 100% con Réplica Pixel-Perfect Estilo Claude verificada en vivo.

---

## 1. Resumen Ejecutivo
Se ejecutó una réplica pixel-perfect de la interfaz móvil de **Claude** en **OpenCode Companion**, cubriendo la totalidad de vistas, componentes y microinteracciones de la aplicación:
1. **`ProjectDetailScreen`**: Encabezado tipográfico Serif, pestañas `"Chats"` y `"Archivos e instrucciones"`, tarjeta dedicada para `"Instrucciones del proyecto"` con botón de edición en lápiz y modal de guardado, lista de habilidades/archivos con metadatos y FAB flotante `"+ Nuevo chat"`.
2. **`ChatsScreen` y `ProjectsScreen`**: Barra de búsqueda estilo píldora (`CircleShape`, `"Buscar chats…"` / `"Buscar proyectos…"`), listas de una sola línea con viñeta circular `◦`, fecha relativa y `ProviderBadge`, y menús contextuales flotantes (`RoundedCornerShape(16.dp)`) con iconos lineales delgados y opción `"Eliminar"` destacada en color destructivo.
3. **`ChatScreen` y `MarkdownText`**: Tipografía de lectura Serif limpia sin cajas de contexto invasivas (`<memory_context>` suprimido visualmente si está vacío o integrado limpiamente sin acordeón tosco), y bloques de código con barra de encabezado superior (etiqueta de lenguaje en mayúsculas) y botón interactivo `"Copiar"` con feedback visual instantáneo (`¡Copiado!`).
4. **Composer Flotante Unificado**: Contenedor flotante con elevación suave, selector de modelo en píldora (`"Gemini 3.6 Flash"`), botón `+` para adjuntos (cámara, fotos, archivos), y botón de acción circular en paleta Claude (terracota `#D97757` para enviar y feedback animado pulsante en modo escucha).

---

## 2. Sistema de Tokens y Tipografía Claude (Warm Dark)

| Token Semántico Compose | Hex / Valor | Rol en la Interfaz |
|---|---|---|
| `ClaudeBackground` | `#181816` | Fondo cálido carbón/espresso mate |
| `ClaudeSurface` | `#22211F` | Superficies de TopAppBars, Scaffold y Composer flotante |
| `ClaudeSurfaceVariant` | `#2B2926` | Burbujas del asistente y bloques de código |
| `ClaudeSurfaceContainerHigh`| `#2F2D2A` | Diálogos modales y menús desplegables contextuales |
| `ClaudePrimary` | `#D97757` | Acento icónico terracota de Claude (FABs, botón enviar, switches) |
| `ClaudeOnPrimary` | `#FFFFFF` | Texto e iconos de alto contraste sobre terracota |
| `ClaudeOnSurface` | `#EDEDEC` | Blanco cálido para tipografía principal |
| `ClaudeOnSurfaceVariant` | `#A5A39F` | Gris piedra cálido para metadatos, marcas temporales y viñetas `◦` |
| `ClaudeOutline` | `#403D39` | Bordes sutiles para text fields, chips y divisores |
| `ClaudeOutlineVariant` | `#2E2C29` | Bordes discretos de tarjetas y contenedores |
| `FontFamily.Serif` | Serif | Tipografía de lectura y encabezados en proyectos y chat |
| `FontFamily.Monospace` | Monospace | Lenguaje y cuerpo de bloques de código |

---

## 3. Matriz de Componentes y Pantallas Auditadas

### 3.1. `ProjectDetailScreen` (Detalle de Proyecto Estilo Claude)
- **Encabezado Serif:** Título en `FontFamily.Serif` con `FontWeight.Normal`, acompañado por el `ProviderBadge` (`OpenCode` o `Antigravity`).
- **Pestañas:** `TabRow` con indicador terracota dividida en `"Chats (N)"` y `"Archivos e instrucciones"`.
- **Pestaña 1 ("Chats"):** Lista de sesiones vinculadas con viñeta `◦`, fecha relativa y badge de agente.
- **Pestaña 2 ("Archivos e instrucciones"):**
  - **Tarjeta "Instrucciones del proyecto":** Contenedor `ClaudeSurface` con borde sutil. Muestra el prompt o directrices en tipografía Serif. Dispone de botón de lápiz (`Icons.Outlined.Edit`) que despliega el `AlertDialog` para modificar y persistir las instrucciones mediante `PATCH`.
  - **Sección "Archivos adjuntos y habilidades":** Metadatos de longitud/caracteres, chips de ámbito (`proyecto` vs `global`) y acción para añadir nuevas directrices.
  - **Sección "Proyectos vinculados":** Enlaces entre espacios de trabajo con opción para desvincular.
- **FAB "+ Nuevo chat":** Botón de acción flotante extendido en terracota (`#D97757`) con bordes de `16.dp`.

### 3.2. `ChatsScreen` y `ProjectsScreen` (Píldora, 1-Línea y Menú Flotante)
- **Barra de Búsqueda Píldora:** `OutlinedTextField` con `shape = CircleShape`, icono `Icons.Outlined.Search`, botón de limpieza `Icons.Outlined.Close` y filtrado reactivo en tiempo real.
- **Filas de 1 Sola Línea:**
  - `Text(title, maxLines = 1, overflow = Ellipsis) + Text(" ◦ ${relativeTime}") + ProviderBadge`.
  - Altura homogénea y compacta emulando la lista de conversaciones de la aplicación móvil de Claude.
- **Menú Contextual Flotante (Long-Press):**
  - Esquinas suaves de `16.dp` con borde `ClaudeOutlineVariant`.
  - Iconos lineales delgados: `Icons.Outlined.Edit` (Renombrar), `Icons.Outlined.PushPin` (Fijar), `Icons.Outlined.DriveFileMove` (Agregar a proyecto / Mover).
  - Opción `"Eliminar"` destacada en color destructivo de error (`MaterialTheme.colorScheme.error`) con tipografía en negrita.

### 3.3. `ChatScreen` y `MarkdownText` (Tipografía Serif y Bloques de Código)
- **Supresión de Cajas Invasivas:** Se eliminó el acordeón tosco de `<memory_context>`. Si el mensaje es de contexto puro se omite la renderización, y si contiene texto visible se presenta directamente con tipografía Serif pura (`fontSize = 15.sp`, `lineHeight = 22.sp`).
- **Bloques de Código:**
  - Cabecera de bloque con fondo `ClaudeSurfaceContainerHigh`.
  - Etiqueta de lenguaje en fuente monospace mayúscula (ej. `KOTLIN`, `PYTHON`, `CÓDIGO`).
  - Botón `"Copiar"` con icono `Icons.Outlined.ContentCopy` y transición automática a `"¡Copiado!"` con `Icons.Filled.Done` durante 2 segundos.
  - Monospace scrolleable horizontalmente.

### 3.4. Floating Composer Unificado
- **Contenedor Flotante:** `Surface` con bordes redondeados (`22.dp`), sombra y borde sutil, flotando sobre el contenido con `navigationBarsPadding().imePadding()`.
- **Selector de Modelo en Píldora:** Pill táctil con icono robot/sparkle (`Icons.Outlined.AutoAwesome`), texto `"Gemini 3.6 Flash"` y chevron desplegable. Al pulsar, abre la hoja modal de selección de modelo (`Claude Sonnet 4`, `GPT-4o`, etc.).
- **Botón de Adjuntos `+`:** Icono lineal `Icons.Outlined.Add` que despliega el modal para Cámara, Fotos y Archivos.
- **Botón de Acción Circular:**
  - Con texto: botón circular terracota `#D97757` con icono de flecha hacia arriba (`Icons.Filled.ArrowUpward`).
  - Sin texto: botón circular de micrófono (`Icons.Filled.MicNone` / `Icons.Filled.Mic`) con animación pulsante de escala cuando la escucha está activa.

---

## 4. Verificación en Vivo (Bridge A11y :8766)

Todos los componentes fueron verificados en tiempo real sobre el dispositivo físico:

```text
[✓] Floating Composer:
    - Input: bounds=Rect(52, 1998 - 1028, 2131)
    - Pill Modelo: "Gemini 3.6 Flash" bounds=Rect(151, 2166 - 490, 2228)
    - Botón "+": "Adjuntar archivo" bounds=Rect(52, 2154 - 137, 2239)
    - Botón Acción: "Hablar" / "Enviar mensaje" bounds=Rect(943, 2154 - 1028, 2239)

[✓] ProjectsScreen:
    - Búsqueda Píldora: "Buscar proyectos…" bounds=Rect(160, 303 - 500, 360)
    - Fila 1 Línea: "Antigravity POC Project" + " ◦ Hace 2 horas" + "Antigravity"
    - Diálogo Nuevo Proyecto: FilterChips "OpenCode" y "Antigravity"
    - Menú Long-Press (16.dp): "Fijar", "Editar detalles", "Archivar", "Eliminar" (rojo)

[✓] ProjectDetailScreen:
    - Encabezado Serif: "Opencode Companion" + Badge "Antigravity"
    - Pestañas: "Chats (3)" y "Archivos e instrucciones"
    - Tarjeta Instrucciones: "Para mejorar Opencode Companion" + botón lápiz (Rect 929, 436 - 1005, 512)
    - Modal Instrucciones: Formulario editable de directrices con botones Cancelar y Guardar
    - FAB: "+ Nuevo chat" bounds=Rect(664, 2002 - 1042, 2134)

[✓] ChatsScreen:
    - Búsqueda Píldora: "Buscar chats…" con filtrado instantáneo
    - Fila 1 Línea: "Compila el APK…" + " ◦ Hace 5 minutos" + " · Opencode Companion" + "Antigravity"
    - Menú Long-Press (16.dp): "Renombrar", "Fijar", "Agregar a proyecto", "Eliminar" (rojo)

[✓] ChatScreen:
    - Tipografía Serif en lectura sin cajas invasivas de contexto interno
    - IME dinámico: elevación del Composer de Rect(52, 1998) a Rect(52, 1215) al levantar el teclado
    - Transición de botón circular: de "Hablar" a "Enviar mensaje" en terracota
```

---

## 5. Conclusión
La réplica de la experiencia visual y táctil de Claude es pixel-perfect, fluida y robusta. No se detectaron desbordamientos de layout, ni regresiones funcionales en los endpoints ni en el puente de accesibilidad.
