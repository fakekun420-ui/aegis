# CONTRATO OPENCODE — lo que la app usa del servidor

Generado desde `app/app/src/main/kotlin/com/aegis/hub/data/OpenCodeApi.kt`
(F1, T-F1.5; sustituye a `app/docs/FRONTEND_CONTRACT.md`, que describía el Hub
retirado). Base: `http://127.0.0.1:49374` con Basic (`Credentials`, contraseña de
`service.json`). Formas verificadas con `tools/chk_forma.py` (0 incompatibles).

| Método | Ruta | Request | Response | Quién lo usa |
|---|---|---|---|---|
| `getInfo` | `GET api/info` | — | `OpenCodeServerInfo` | `SetupNative` (sonda de vivo) |
| `listSessions` | `GET api/session` | `cursor?, limit?=50` | `OpenCodeSessionListResponse` | `RutaNativa` |
| `getSession` | `GET api/session/{id}` | — | `OpenCodeSessionResponse` | `RutaNativa` |
| `createSession` | `POST api/session` | `CreateOpenCodeSessionRequest(title, location?)` | `OpenCodeSessionResponse` | `RutaNativa`, `SetupNative` (smoke), `ProjectDetailViewModel` |
| `updateSession` | `PATCH api/session/{id}` | `UpdateOpenCodeSessionRequest` | `Response<Unit>` | `RutaNativa`, `MainViewModel` (renombrar) |
| `deleteSession` | `DELETE api/session/{id}` | — | `Response<Unit>` | `RutaNativa`, `MainViewModel`, `ProjectDetailViewModel` (borrar) |
| `getActiveSessions` | `GET api/session/active` | — | `OpenCodeActiveSessionsResponse` (`Map`, envuelto en `data`) | `RutaNativa.getInflight`, `MainViewModel` |
| `getMessages` | `GET api/session/{id}/message` | `limit?, order?, cursor?` | `OpenCodeMessageListResponse` | `RutaNativa`, `ChatViewModel` |
| `getMessage` | `GET api/session/{id}/message/{messageID}` | — | `OpenCodeMessageResponse` | `RutaNativa` |
| `sendPrompt` | `POST api/session/{id}/prompt` | `OpenCodePromptRequest(text, files)` | `OpenCodePromptAck` | `RutaNativa.sendMessage`, `SetupNative` |
| `interruptSession` | `POST api/session/{id}/interrupt` | — | `Response<Unit>` | (sin uso actual en `main`) |
| `setSessionModel` | `POST api/session/{id}/model` | `SetSessionModelRequest(model: OpenCodeModelRef{id, providerID, variant?})` | `Response<Unit>` | `RutaNativa.fijarModelo`, `ChatViewModel.selectModel` |
| `setSessionAgent` | `POST api/session/{id}/agent` | `SetSessionAgentRequest(agent)` | `Response<Unit>` | `RutaNativa` |
| `listAgents` | `GET api/agent` | — | `OpenCodeNativeAgentListResponse` | `RutaNativa.getOpencodeAgents` |
| `listModels` | `GET api/model` | — | `OpenCodeNativeModelListResponse` | `RutaNativa.getModels` |
| `getSessionForms` | `GET api/session/{id}/form` | — | `OpenCodeFormsResponse` | `RutaNativa` |
| `replyForm` | `POST api/session/{id}/form/{formID}/reply` | `OpenCodeFormReplyRequest` | `Response<Unit>` | `RutaNativa`, `ChatViewModel` |
| `getSessionPermissions` | `GET api/session/{id}/permission` | — | `OpenCodePermissionsResponse` | `RutaNativa` |
| `replyPermission` | `POST api/session/{id}/permission/{requestID}/reply` | decisión + mensaje | `Response<Unit>` | `RutaNativa`, `ChatViewModel` |
| `openEventStream` | `GET api/event` (SSE) | — | `ResponseBody` (líneas `data:`) | `EventStream` |

Notas medidas:
- El prompt **no acepta modelo**: se fija antes con `setSessionModel` (F3 lo hace
  explícito en `SesionConfigRepo`).
- Fijar un modelo inexistente devuelve **204** (el servidor no valida); el fallo
  sale después, en el turno.
- `GET session/{id}` inexistente = 404 + `{"_tag","message"}`; `POST message` a
  inexistente = 404 vacío; sin credenciales = 401 + `{"_tag","message"}` (ver
  `data/ErroresRed.kt` y `test/resources/errores/`).
