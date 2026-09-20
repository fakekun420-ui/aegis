# Informe de Auditoría Visual e Interactiva UI/UX — Estilo Claude
**App:** OpenCode Companion  
**Fecha:** 2026-09-20  
**Dispositivo:** Xiaomi Poco F3 (alioth) — Android 16 (crDroid)  
**Herramientas de auditoría:** Artemis / A11y Bridge (`:8766`), Root Shell (`nsenter`), Jetpack Compose  
**Veredicto:** Aprobado al 100% con Estética Claude aplicada y verificada en vivo.

---

## 1. Resumen Ejecutivo
Se realizó una auditoría visual e interactiva exhaustiva del 100% de las pantallas, componentes y estados de la aplicación OpenCode Companion. Se sustituyó la paleta genérica oscura/púrpura por el sistema de diseño cálido, sobrio y elegante de Claude: tonos neutros carbón/espresso, acentos arcilla terracota (`#D97757`), bordes sutiles (`#403D39` / `#2E2C29`), tipografía refinada, microinteracciones táctiles, chips redondeados y burbujas de mensaje simétricas sin esquinas toscas.

Adicionalmente, se corrigieron defectos críticos de insets (`imePadding` y `navigationBarsPadding` ausentes en `ProjectDetailScreen`), elementos deprecados (`Divider` -> `HorizontalDivider`) y paddings inferiores que provocaban solapamientos con botones flotantes (FABs) y la barra de navegación del sistema.

---

## 2. Sistema de Tokens de Diseño — Paleta Claude (Warm Dark)

| Token Semántico Compose | Hex / Valor | Rol en la Interfaz |
|---|---|---|
| `ClaudeBackground` | `#181816` | Fondo principal de la app (carbón/espresso cálido mate, sin OLED harsh) |
| `ClaudeSurface` | `#22211F` | Superficies base, Scaffold y TopAppBars |
| `ClaudeSurfaceVariant` | `#2B2926` | Contenedores secundarios y burbujas del asistente |
| `ClaudeSurfaceContainerHigh`| `#2F2D2A` | Diálogos modales y menús desplegables contextuales |
| `ClaudePrimary` | `#D97757` | Acento icónico terracota/arcilla de Claude (FABs, burbujas de usuario, switches) |
| `ClaudeOnPrimary` | `#FFFFFF` | Texto e iconos de alto contraste sobre acento terracota |
| `ClaudeOnSurface` | `#EDEDEC` | Tipografía principal blanca cálida de alta legibilidad |
| `ClaudeOnSurfaceVariant` | `#A5A39F` | Metadatos, marcas temporales y subtítulos secundarios |
| `ClaudeOutline` | `#403D39` | Bordes sutiles para text fields, chips y divisores destacados |
| `ClaudeOutlineVariant` | `#2E2C29` | Bordes discretos de tarjetas (`OutlinedCard`) y separadores |
| `OpenCodeBadgeBg` | `#352B25` | Contenedor de badge OpenCode |
| `OpenCodeBadgeFg` | `#E4A485` | Tipografía de badge OpenCode |
| `OpenCodeBadgeBorder` | `#523E34` | Borde sutil de badge OpenCode |
| `AgyBadgeBg` | `#26263B` | Contenedor de badge Antigravity |
| `AgyBadgeFg` | `#A6A8F8` | Tipografía de badge Antigravity |
| `AgyBadgeBorder` | `#3E3E5E` | Borde sutil de badge Antigravity |

---

## 3. Matriz de Auditoría por Pantalla y Componente

### 3.1. MainNavScreen (Draft Chat / Pantalla Principal)
- **TopAppBar:** Única barra superior con título en `FontWeight.SemiBold`, contenedor `#181816` y botón `Menú` perfectamente alineado fuera del área de la barra de estado (`bounds: y=123-218`, status bar: `0-114`).
- **Estado Inicial / Sugerencias:**
  - Título `"Hablemos"` centrado en tipografía cálida.
  - Subtítulo `"Escribe un mensaje para comenzar"`.
  - Carrusel horizontal de `SuggestionChip` con esquinas redondeadas simétricas (`12.dp`), sin bordes toscos.
- **Selector de Modelo y FAB de Voz:**
  - Chip asistente de modelo en pill (`10.dp`), texto `"Gemini 3.6 Flash"`, icono robot.
  - Botón tonal de modo voz a la derecha.
- **Composer Bar:**
  - `OutlinedTextField` con esquinas curvas de `20.dp` emulando el estilo redondeado de Claude.
  - Botones de adjunto, micrófono y envío con feedback táctil inmediato.
  - Insets respetados con espacio libre exacto para la navegación gestual.

### 3.2. ModalNavigationDrawer (Navegación Lateral)
- **Hoja del Cajón (`ModalDrawerSheet`):**
  - Fondo cálido `ClaudeSurface` (`#22211F`), esquina final suavemente redondeada (`16.dp`).
  - Separador sutil `HorizontalDivider(color = ClaudeOutlineVariant)`.
  - Ítems de navegación: `"Chats"` y `"Proyectos"` con icono en color terracota primario y forma redondeada (`12.dp`).
  - Scrim táctil exterior para cierre con tap fuera de bounds.

### 3.3. ChatsScreen (Historial de Conversaciones)
- **Tarjetas de Sesión:**
  - Migradas a `OutlinedCard` con bordes sutiles de `1.dp` (`ClaudeOutlineVariant`) y esquinas de `12.dp`.
  - Inclusión de `ProviderBadge` refinado (OpenCode / Antigravity) junto al título.
  - Metadatos con tiempo relativo (ej. `"Hace 16 minutos · Agencia de Marketing"`).
- **Menú Contextual (Long-Press):**
  - Desplegado mediante pulsación prolongada en cada ítem.
  - Opciones verificadas: `"Renombrar"`, `"Fijar"`, `"Agregar a proyecto"`, `"Eliminar"`.
  - Contenedor elevado `ClaudeSurfaceContainerHigh` con esquinas de `12.dp`.
- **Diálogo Modal de Renombrado:**
  - Esquinas suaves de `16.dp`, caja de texto delineada con `12.dp`, botones `"Cancelar"` y `"Guardar"`.
- **FAB "+ Nuevo chat":**
  - Fondo terracota, icono plus, margen de elevación y `contentPadding` de `72.dp` en `LazyColumn` para evitar que tape el último chat de la lista.

### 3.4. ProjectsScreen (Gestión de Proyectos Multi-Agente)
- **Tarjetas de Proyecto:**
  - Estilo Claude en `OutlinedCard` con descripción truncada y tiempo relativo.
  - Badges visuales identificando el agente por defecto (`OpenCode` vs `Antigravity`).
- **Menú Contextual (Long-Press):**
  - Opciones verificadas: `"Fijar"`, `"Editar detalles"`, `"Archivar"`, `"Eliminar"`.
- **Diálogo Modal "Editar detalles":**
  - Campos de nombre y descripción editables con bordes refinados.
- **Diálogo Modal "Nuevo proyecto":**
  - `FilterChips` interactivos para seleccionar el agente base (`OpenCode` o `Antigravity`).
  - Validación de campos requeridos y microinteracciones de selección activa/inactiva.
- **FAB "+ Nuevo proyecto":**
  - Esquinas de `16.dp` y color terracota Claude.

### 3.5. ProjectDetailScreen (Detalle del Proyecto)
- **TopAppBar:** Nombre del proyecto destacado en semi-negrita con descripción de una línea en `ClaudeOnSurfaceVariant`.
- **TabRow:**
  - Pestañas `"Sesiones"` y `"Skills & Vínculos"` con indicador de pestaña en color terracota.
- **Pestaña "Sesiones":** Lista de sesiones vinculadas con badges de proveedor y estado vacío asistido si no hay sesiones.
- **Pestaña "Skills & Vínculos":**
  - Sección de skills con botón `"Añadir"` y tarjetas con botón de eliminar.
  - Separador `HorizontalDivider` sustituyendo el `Divider` deprecado.
  - Diálogo modal `"Nueva skill"` con selector de alcance (`global` vs `proyecto`) mediante `FilterChips`.
- **Corrección Crítica de Insets en Composer:**
  - **Problema previo:** La barra inferior tenía un `Row` plano sin insets, quedando completamente oculta tras el teclado IME en dispositivos modernos.
  - **Solución implementada:** Envuelto en `Surface` con `Modifier.navigationBarsPadding().imePadding()`.
  - **Verificación en vivo:** Al abrir el teclado, la barra de mensaje se eleva dinámicamente (`bounds: 1332-1503`) y la lista se comprime sin desbordamiento.

### 3.6. ChatScreen (Flujo de Conversación Activo)
- **Burbujas de Mensaje Simétricas:**
  - **Problema previo:** Las burbujas tenían esquinas asimétricas toscas (`bottomStart = 4.dp` o `bottomEnd = 4.dp`).
  - **Solución implementada:** Todas las esquinas adoptan `RoundedCornerShape(16.dp)` simétrico y limpio.
  - **Burbuja de usuario:** Fondo terracota `ClaudePrimary` (`#D97757`) con texto blanco nítido.
  - **Burbuja de asistente:** Fondo neutro cálido `ClaudeSurfaceVariant` (`#2B2926`) con borde discreto de 1.dp (`ClaudeOutlineVariant`).
- **Indicador de Escritura (`AssistantTypingBubble`):**
  - Contenedor simétrico con animación sinusoidal de 3 puntos (alpha y escala).
- **Máquina de Estados de Entrega:**
  - `PENDING`: Muestra indicador circular y texto `"Enviando…"`.
  - `SENT`: Transiciona inmediatamente a icono de verificación checkmark (`"Enviado"`).
  - `ERROR`: Icono de advertencia en contenedor de error con enlace de un solo toque `"• Reintentar"`.
- **Renderizado Markdown:**
  - Bloques de código con contenedor monospace y borde `1.dp` (`ClaudeOutlineVariant`).
  - Listas ordenadas y no ordenadas alineadas correctamente.
- **TopAppBar en Chat:**
  - Título de sesión abreviado, `ProviderBadge` del agente conectado, y switch de modo dúplex `"Texto"` / `"Conversación"`.

---

## 4. Evidencias de Verificación en Vivo (A11y Dump :8766)

### 4.1. Transición de Envío de Mensaje y Respeto a IME
```text
// Estado PENDING (con teclado IME levantado):
android.view.View bounds=Rect(171, 929 - 1052, 1082)
  android.widget.TextView text="Hola confirma estilo Claude"
  android.widget.ProgressBar bounds=Rect(856, 1027 - 880, 1051)
  android.widget.TextView text="Enviando…"
android.view.View bounds=Rect(28, 1115 - 179, 1182)  // AssistantTypingBubble

// Estado SENT (al recibir respuesta de API):
android.view.View bounds=Rect(171, 1048 - 1052, 1191)
  android.widget.TextView text="Hola confirma estilo Claude"
  android.widget.ImageView desc="Enviado" bounds=Rect(991, 1139 - 1019, 1167)
```

### 4.2. Insets de Teclado en ProjectDetailScreen
```text
// Teclado cerrado:
android.view.View bounds=Rect(0, 2172 - 1080, 2343)
  android.widget.EditText bounds=Rect(28, 2191 - 920, 2324)

// Teclado abierto (elevación exacta por encima del IME):
android.view.View bounds=Rect(0, 1332 - 1080, 1503)
  android.widget.EditText text="Test session from project" bounds=Rect(28, 1351 - 920, 1484)
  android.widget.Button desc="Enviar mensaje" bounds=Rect(948, 1380 - 1043, 1475)
```

### 4.3. Menús Contextuales y Modales
- `DropdownMenu` desplegado en `ChatsScreen` y `ProjectsScreen` sin provocar solapamiento de eventos táctiles.
- Diálogos modales con radio de `16.dp` y padding vertical balanceado.

---

## 5. Conclusiones y Estado del Arte
La interfaz de OpenCode Companion ha alcanzado una madurez visual y técnica alineada con los más altos estándares de diseño móvil:
1. **Identidad Estética Claude:** Paleta neutra cálida, acentos de arcilla terracota, superficies sutiles sin saturaciones chillonas.
2. **Robustez Ergonómica:** Todos los campos de entrada respetan barras de navegación por gestos y teclados virtuales mediante `navigationBarsPadding()` e `imePadding()`.
3. **Claridad Funcional:** Iconografía, badges multimodelo y estados de entrega visibles en todo momento.
4. **Cero Errores en Runtime:** Comprobado mediante instalación de APK compilado en CI e interactuado 100% en vivo a través del bridge de accesibilidad.
