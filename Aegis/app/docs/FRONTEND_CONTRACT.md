# FRONTEND CONTRACT: OpenCode & Antigravity Companion

**Version:** 1.2.0  
**Target Clients:** Android App (`com.opencode.companion`, Jetpack Compose Material 3), Web Clients  
**Target Backends:** Local Hub (`server.js`, port 8765), OpenCode Daemon (port 4096), Antigravity CLI (`agy`)  
**Status:** Canonical & Strictly Typed  

---

## 1. Overview & Communication Architecture

The Companion app interfaces with the backend hub at `http://127.0.0.1:8765` (or configured host), which routes requests dynamically to either **OpenCode** (native daemon on port 4096) or **Antigravity CLI** (`/root/.local/bin/agy`).

```
┌────────────────────────────┐
│      Android Frontend      │
│  (ChatViewModel + Compose) │
└─────────────┬──────────────┘
              │ HTTP / JSON (OkHttp Timeout: 90s)
              ▼
┌────────────────────────────┐
│         Hub API            │ (Port 8765)
│       (server.js)          │
└──────┬──────────────┬──────┘
       │              │
       ▼              ▼
┌──────────────┐ ┌───────────────────┐
│   OpenCode   │ │    Antigravity    │
│ (Port 4096)  │ │ (/root/.local/bin/│
│              │ │  agy -p ... )     │
└──────────────┘ └───────────────────┘
```

---

## 2. Global Headers Contract

Every request originating from the frontend MUST support or include the following headers when context is available:

| Header | Type | Values / Example | Description |
|---|---|---|---|
| `Content-Type` | String | `application/json` | Required for all POST/PUT/PATCH bodies |
| `Accept` | String | `application/json` | Expected response encoding |
| `X-Provider` | String | `"opencode"` \| `"antigravity"` | Specifies whether OpenCode or Antigravity executes the query |
| `X-Project-Id` | String | `"prj_12345"` \| UUID | Specifies working directory / project boundary |

---

## 3. Core Endpoints Specification

### 3.1. Send Message
- **Endpoint:** `POST /opencode/session/{sessionId}/message`
- **Headers:** `X-Provider: {provider}`, `X-Project-Id: {projectId}`
- **Timeout Requirement:** Backend MUST respond within **90 seconds**. If long-running LLM generation takes longer, backend should return 202 Accepted or stream chunks.
- **Request Body (`SendMessageRequest`):**
```json
{
  "text": "Escribe un componente Compose para mostrar gráficos",
  "model": "gemini-2.5-flash",
  "provider": "antigravity",
  "files": [
    {
      "name": "diagram.png",
      "mime": "image/png",
      "size": 1048576,
      "base64": "iVBORw0KGgoAAAANSUhEUgAA..."
    }
  ]
}
```
- **Response (200 OK):**
```json
{
  "role": "assistant",
  "text": "Aquí tienes el código del componente...",
  "info": {
    "id": "msg_98765",
    "timestamp": 1726856000000,
    "deliveryStatus": "SENT"
  },
  "parts": [
    {
      "type": "text",
      "text": "Aquí tienes el código del componente..."
    }
  ]
}
```
- **Response (202 Accepted):**
```json
{
  "status": "queued",
  "sessionId": "ses_123"
}
```
- **Response (4xx / 5xx Error):**
```json
{
  "error": "Timeout comunicando con el proveedor Antigravity CLI tras 90s"
}
```

---

### 3.2. Get Messages (Polling & Initial Load)
- **Endpoint:** `GET /api/opencode/sessions/{sessionId}/messages`
- **Response (200 OK):** Array of `Message` objects in chronological order.
```json
[
  {
    "role": "user",
    "text": "Hola, ¿cómo estás?",
    "info": {
      "id": "msg_001",
      "timestamp": 1726855900000,
      "deliveryStatus": "SENT"
    }
  },
  {
    "role": "assistant",
    "text": "¡Hola! Estoy listo para ayudarte a programar.",
    "info": {
      "id": "msg_002",
      "timestamp": 1726855905000,
      "deliveryStatus": "SENT"
    }
  }
]
```

---

### 3.3. Sessions Management
- **List Sessions:** `GET /api/opencode/sessions`
  - Response: `List<OpencodeSession>`
- **Create Session:** `POST /api/opencode/sessions`
  - Request: `{"title": "Nueva sesión", "project_id": "p1", "provider": "antigravity"}`
  - Response: `OpencodeSession`
- **Get Models:** `GET /api/opencode/models`
  - Response: `List<{"id": "...", "name": "...", "description": "..."}>`

---

## 4. Strict Type Definitions (Kotlin & TypeScript)

### 4.1. Kotlin Models (`com.opencode.companion.data.Models.kt`)

```kotlin
enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    ERROR
}

data class MessageInfo(
    val id: String? = null,
    val timestamp: Long? = null,
    val status: MessageDeliveryStatus? = null,
    val delivery_status: String? = null
) {
    val deliveryStatus: MessageDeliveryStatus
        get() = status ?: when (delivery_status?.uppercase()) {
            "PENDING" -> MessageDeliveryStatus.PENDING
            "SENT" -> MessageDeliveryStatus.SENT
            "ERROR" -> MessageDeliveryStatus.ERROR
            else -> MessageDeliveryStatus.SENT
        }
}

data class Message(
    val role: String? = null,
    val text: String = "",
    val info: MessageInfo? = null,
    val parts: List<Part>? = null
) {
    val isPending: Boolean
        get() = info?.deliveryStatus == MessageDeliveryStatus.PENDING

    val isError: Boolean
        get() = info?.deliveryStatus == MessageDeliveryStatus.ERROR

    fun withStatus(status: MessageDeliveryStatus): Message {
        val newInfo = (info ?: MessageInfo()).copy(status = status)
        return copy(info = newInfo)
    }
}

data class AttachedFile(
    val name: String,
    val mime: String,
    val size: Long = 0,
    val base64: String? = null,
    val text: String? = null
)

data class SendMessageRequest(
    val text: String,
    val files: List<AttachedFile> = emptyList(),
    val model: String? = null,
    val provider: String? = null
)
```

---

## 5. UI Lifecycle, Polling & State Machine

```
User Hits Send
      │
      ▼
┌────────────────────────────────────────┐
│ Optimistic Message Created             │
│ - role: "user"                         │
│ - text: input                          │
│ - deliveryStatus: PENDING              │
│ - Loading indicator: TRUE              │
└─────┬──────────────────────────────────┘
      │
      ├─── Concurrent 1: POST /opencode/session/{id}/message
      │     (Timeout: 90 seconds)
      │
      └─── Concurrent 2: Background Poller
            - Polls GET /api/opencode/sessions/{id}/messages every 1.5s
            - Max 50 iterations (~75s)
            - Terminates when new assistant message appears
      │
      ▼
┌────────────────────────────────────────┐
│ Response / Poll Succeeded              │
│ - Optimistic message -> SENT           │
│ - Assistant message rendered           │
│ - Loading indicator: FALSE             │
│ - Auto-scroll triggered                │
└────────────────────────────────────────┘
      │
      └─── (On Timeout or Network Failure)
            │
            ▼
┌────────────────────────────────────────┐
│ Error Handled                          │
│ - Optimistic message -> ERROR          │
│ - Loading indicator: FALSE             │
│ - ErrorBanner visible to user          │
│ - Inline "• Reintentar" button active  │
└────────────────────────────────────────┘
```

---

## 6. Frontend Visual & Design Specifications (Material 3)

1. **Insets:**
   - Bottom Bar uses `.navigationBarsPadding().imePadding()` to ensure smooth keyboard lifting with zero visual overlap.
2. **Auto-Scroll:**
   - Triggers on `(messages.size, loading)` changes with an 80ms layout pass debounce.
3. **Typing Indicator (`AssistantTypingBubble`):**
   - Renders 3 animated pulsing dots with staggered 180ms delay between dots while `loading == true`.
4. **Empty State:**
   - Includes centered branding icon, welcome headline, and horizontal suggestion chips:
     - *"¿Qué puedes hacer?"*
     - *"Explícame la arquitectura del proyecto"*
     - *"Comprueba el estado del sistema"*
5. **Double TopBar Prevention:**
   - `MainNavScreen` uses outer top bar; `ChatScreen` receives `showTopBar = false` when embedded in draft view.
6. **Internal Context Collapsibility:**
   - Any `<memory_context>` block received from backend is rendered as a clean collapsible dropdown so internal prompt injection context does not clutter the user conversation.
