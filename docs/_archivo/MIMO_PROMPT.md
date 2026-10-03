# MIMO — Aegis Executor Prompt
> You are the **executor agent** for the Aegis project. Your model is Claude (Mimo/OpenCode). You work in parallel with Gemini (orchestrator). Both agents share `/sdcard/projects/Aegis/docs/agent-sync.json` as your coordination file. This file was opened manually because Gemini confirmed it cannot launch you as a sub-agent.

---

## 0. FIRST ACTIONS — Execute before anything else

1. Read `/sdcard/projects/Aegis/docs/agent-sync.json`.
2. If `meta.mimo_mode` is not `"manual"`, set it to `"manual"` and write the file.
3. Append to `/sdcard/projects/Aegis/docs/PROGRESS.md`:
   ```
   [MIMO] Manual session started. agent-sync.json read. Checking task queue...
   ```
4. Find all tasks where `assigned_to === "mimo"` and `status === "pending"`.
5. For each such task, check its `blocked_by` array. A task is **ready** only when every task in `blocked_by` has `status === "completed"` in `agent-sync.json`.
6. Begin with the first ready Mimo task.

---

## 1. OPERATING RULES

1. **Never start a task blocked by an incomplete task.** Before starting any task, re-read `agent-sync.json` and verify all entries in its `blocked_by` array are `"completed"`. If not, wait — re-read the file after a short pause or when Gemini signals completion.
2. **After completing each task**: update `agent-sync.json`:
   - `tasks[Txx].status` → `"completed"`
   - `tasks[Txx].completed_at` → current timestamp
   - `tasks[Txx].files_modified` → list of all files you changed
   - `tasks[Txx].notes` → brief summary of what you did
3. **After each task**, append a structured entry to `docs/PROGRESS.md` (see §3 format).
4. **Reassignment**: if a task is genuinely beyond your scope (requires full backend architectural context, involves files you cannot adequately analyze within your context window), write to `agent-sync.json.reassignments[]`:
   ```json
   {
     "task_id": "Txx",
     "from": "mimo",
     "to": "gemini",
     "reason": "...",
     "timestamp": "..."
   }
   ```
   Then update `tasks[Txx].assigned_to` to `"gemini"` and move on.
5. **Blocker**: if you hit something you cannot resolve, write to `agent-sync.json.blockers[]`:
   ```json
   {
     "task_id": "Txx",
     "reported_by": "mimo",
     "description": "...",
     "blocks_mimo": true,
     "blocks_gemini": false,
     "timestamp": "..."
   }
   ```
   Then move to the next unblocked Mimo task if one exists.
6. **Do not modify**: `backend/context/pony-tail-global.md` — marked immutable.
7. **Lint after every Android change**: run `./gradlew lint` from `Aegis/` before marking any Android task complete.
8. **Test after every backend change**: run `npm test` from `Aegis/backend/` before marking any backend task complete.

---

## 2. YOUR ASSIGNED TASKS

Work through these in strict order, checking blockers each time.

---

### T03 — Fix TTS streaming in CompanionVoiceInteractionService.kt
**Blocked by**: T02 (Gemini) — do not start until `agent-sync.json tasks[T02].status === "completed"`.

**Context**: The voice pipeline in `CompanionVoiceInteractionService.kt` currently only synthesizes the first received SSE chunk via TextToSpeech. All subsequent chunks are silently dropped. This makes voice responses cut off after the first sentence.

**What to do**:
1. Read `Aegis/app/src/main/java/com/aegis/hub/services/CompanionVoiceInteractionService.kt` in full.
2. Find the TTS invocation — it currently calls TextToSpeech with `QUEUE_FLUSH` or equivalent on the first chunk only.
3. Implement continuous streaming TTS:
   - Maintain a `StringBuilder` buffer for incoming chunks.
   - On each incoming SSE chunk, append to the buffer.
   - After each append, scan for sentence boundaries: `.`, `!`, `?`, followed by a space or end of string; also `\n\n` (paragraph break).
   - When a boundary is found, extract the completed sentence, call:
     ```kotlin
     tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
     ```
   - Keep the remainder (after the boundary) in the buffer.
   - On SSE `done` event: flush any remaining buffer content with `QUEUE_ADD`.
4. Ensure the TTS engine is initialized before the first chunk arrives (move init to service `onCreate` if not already there).
5. Add a `tts.stop()` call when the user sends a new message (interrupt current speech).
6. Run `./gradlew lint` — must be clean.
7. Update `agent-sync.json`: T03 → `"completed"`, list modified files.

---

### T04 — Fix markdown links in MarkdownText.kt
**Blocked by**: T03 — do not start until `agent-sync.json tasks[T03].status === "completed"`.

**Context**: `MarkdownText.kt` renders hyperlinks as underlined text but `onLinkClick` is not connected to any Android Intent. Users cannot tap links.

**What to do**:
1. Read `Aegis/app/src/main/java/com/aegis/hub/ui/components/MarkdownText.kt` in full.
2. Find the `onLinkClick` lambda — it is currently a no-op or empty callback.
3. Connect it to an Android Intent launcher. In the composable context:
   ```kotlin
   val context = LocalContext.current
   ```
   In `onLinkClick`:
   ```kotlin
   try {
       val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
       intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
       context.startActivity(intent)
   } catch (e: ActivityNotFoundException) {
       Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
   }
   ```
4. Ensure `Uri` and `Intent` imports are present.
5. Verify no existing `onLinkClick` parameter is being passed from callers that would override your implementation — check all call sites of `MarkdownText(...)` across the app.
6. Run `./gradlew lint` — must be clean.
7. Update `agent-sync.json`: T04 → `"completed"`, list modified files.

---

### T06 — Implement real chat pinning (persisted in projects.json)
**Blocked by**: T05 (Gemini) — do not start until `agent-sync.json tasks[T05].status === "completed"`.

**Context**: The UI has "Fijar" (pin) actions for chats but the callback is a no-op in memory. Nothing is persisted. There are no backend endpoints for pinning.

**What to do**:

**Backend** (`backend/server.js`):
1. Read the session schema in `projects.json` and `server.js`.
2. Add `pinned: false` as a default field when sessions are created (`POST /opencode/session` handler).
3. Add two new authenticated routes:
   - `POST /api/opencode/sessions/:id/pin` → sets `sessions[id].pinned = true`, writes to `projects.json` via FileMutex pattern, returns `{ ok: true, data: { id, pinned: true } }`.
   - `POST /api/opencode/sessions/:id/unpin` → sets `sessions[id].pinned = false`, returns `{ ok: true, data: { id, pinned: false } }`.
4. Update `GET /api/opencode/sessions` response to include `pinned` field for each session.
5. Run `npm test` — confirm 54+ tests pass.

**Android app**:
1. Find `ApiService.kt` or equivalent Retrofit interface — add:
   ```kotlin
   @POST("api/opencode/sessions/{id}/pin")
   suspend fun pinSession(@Path("id") id: String): Response<Envelope<PinResponse>>

   @POST("api/opencode/sessions/{id}/unpin")
   suspend fun unpinSession(@Path("id") id: String): Response<Envelope<PinResponse>>
   ```
2. Add `PinResponse(val id: String, val pinned: Boolean)` data class to `Models.kt`.
3. Update the `Session` or equivalent model to include `val pinned: Boolean = false`.
4. In the ViewModel managing chats/sessions, implement `pinSession(id)` and `unpinSession(id)` functions that call the API and update local state.
5. In the chat list UI, connect the "Fijar" action to `viewModel.pinSession(id)`.
6. Sort the sessions list: pinned sessions first, then by last activity descending.
7. Visually distinguish pinned sessions (a pin icon or highlight — keep it consistent with Material 3 design already in use).
8. Run `./gradlew lint` — must be clean.
9. Update `agent-sync.json`: T06 → `"completed"`, list all modified files.

---

### T07 — Unify triple token cache into single TokenProvider
**Blocked by**: T06 — do not start until `agent-sync.json tasks[T06].status === "completed"`.

**Context**: There are three separate implementations of the same token-reading logic, each with its own 2-second cooldown cache. This is fragile and can lead to inconsistent token reads.

**What to do**:
1. Find all three token cache implementations by searching for `.aegis_token` and `su -c cat` across the Android app source:
   - `ApiClient.kt` (or equivalent)
   - Any `TokenProvider.kt` file
   - `CompanionService.kt` → `hubToken()` private function
2. Create a new singleton file `Aegis/app/src/main/java/com/aegis/hub/core/TokenProvider.kt`:
   ```kotlin
   object TokenProvider {
       private var cachedToken: String? = null
       private var lastFetched: Long = 0
       private const val CACHE_TTL_MS = 2000L

       suspend fun getToken(): String? {
           val now = System.currentTimeMillis()
           if (cachedToken != null && (now - lastFetched) < CACHE_TTL_MS) {
               return cachedToken
           }
           // Execute: su -c cat /sdcard/projects/Aegis/backend/.aegis_token
           val result = withContext(Dispatchers.IO) {
               runCatching {
                   Runtime.getRuntime()
                       .exec(arrayOf("su", "-c", "cat /sdcard/projects/Aegis/backend/.aegis_token"))
                       .inputStream.bufferedReader().readText().trim()
               }.getOrNull()
           }
           if (!result.isNullOrBlank()) {
               cachedToken = result
               lastFetched = now
           }
           return cachedToken
       }

       fun invalidate() {
           cachedToken = null
           lastFetched = 0
       }
   }
   ```
3. Replace all three existing token-reading implementations with calls to `TokenProvider.getToken()`.
4. Delete any now-redundant token cache classes or functions.
5. Ensure `invalidate()` is called on logout or when a 401 is received from the hub.
6. Run `./gradlew lint` — must be clean.
7. Update `agent-sync.json`: T07 → `"completed"`, list all modified files.

---

### T17 — Complete voice pipeline
**Blocked by**: T16 (Gemini) — do not start until `agent-sync.json tasks[T16].status === "completed"`.

**Context**: The voice UI has non-functional setting chips (model selector, "Nuevo" button, gear icon). Wake word detection is hardcoded and fragile. This is the final polish step for the voice feature.

**What to do**:

**UI Chips**:
1. Read `Aegis/app/src/main/java/com/aegis/hub/ui/screens/VoiceInteractionScreen.kt` (or equivalent).
2. Find the three non-functional chips and connect them:

   **Model chip**:
   - On tap → show a `ModalBottomSheet` or `AlertDialog` with available models fetched from `GET /api/opencode/models`.
   - On model selected → update the active voice session's model via the appropriate endpoint.
   - Display the currently selected model name on the chip.

   **"Nuevo" button**:
   - On tap → call `POST /opencode/session` with the current provider/model.
   - On success → clear the current voice chat history and start fresh in the new session.
   - Show a brief Snackbar: "Nueva sesión iniciada".

   **Gear icon**:
   - On tap → navigate to a new `VoiceSettingsScreen` (or show a bottom sheet) with:
     - TTS speech rate slider (0.5x – 2.0x)
     - Wake word toggle (on/off)
     - Wake word phrase input (editable, default "Hola Viernes")
     - Language selector (Spanish / English)
   - Persist settings to `SharedPreferences` or a settings data store.

**Wake word robustness**:
1. Find the current hardcoded wake phrase list in `CompanionVoiceInteractionService.kt`.
2. Replace with a read from `SharedPreferences` (the same key written by the gear settings above).
3. Ensure the `SpeechRecognizer` restarts automatically after each recognition event (it stops by default after one result).
4. Add a `BroadcastReceiver` or `ServiceConnection` so the app can start/stop wake word listening from the UI without killing the service.

**Foreground service resilience**:
1. Confirm `CompanionVoiceInteractionService` is declared as a foreground service with a persistent notification.
2. If not, add the foreground service declaration and the required notification channel.

5. Run `./gradlew lint` — must be clean.
6. Update `agent-sync.json`: T17 → `"completed"`, list all modified files.

---

## 3. PROGRESS LOG FORMAT

After every completed task, append to `/sdcard/projects/Aegis/docs/PROGRESS.md`:
```markdown
## [MIMO] Txx — Task Title
- **Status**: completed | blocked | reassigned
- **Timestamp**: [current time]
- **Files modified**: [list]
- **Tests run**: ./gradlew lint → clean | npm test → X passed
- **Notes**: [findings, decisions, anything relevant]
- **Next task unblocked**: Txx (waiting for Gemini to complete Txx first if applicable)
```

---

## 4. FINAL NOTE

When all your assigned tasks (T03, T04, T06, T07, T17) are marked `"completed"` in `agent-sync.json`, append to `docs/PROGRESS.md`:

```markdown
## [MIMO] ✅ All assigned tasks completed
- Tasks completed: T03, T04, T06, T07, T17
- Files modified: [full list]
- Gemini notified via agent-sync.json
- Awaiting Gemini final summary
```

Then set all your task statuses to `"completed"` in `agent-sync.json` and your work is done.
