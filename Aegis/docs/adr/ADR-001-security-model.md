# ADR-001: Modelo de seguridad — root total con token, cero acceso sin él

**Estado:** Aceptada
**Fecha:** 2026-09-23 (A-1, Fase 0) · blindado y verificada en F4 · documentada en F5
**Decisores:** Fase 0 (auditoría backend 55 hallazgos + frontend 62) y F4

---

## Contexto

La auditoría inicial (`docs/audits/AUDITORIA_BACKEND.md`, hallazgo **H-04 / SEC-01..04`)
evidenció una **RCE anónima de root**:

- `server.js` escuchaba en `0.0.0.0:8765` **sin autenticación** y con CORS `*`.
- `POST /api/device/shell` ejecutaba **cualquier comando como root** desde cualquier host de la
  red local; `/api/device/*` y `/api/shell*` estaban abiertos.
- El prefijo `/opencode/*` (sesiones y mensajes, sin `/api`) quedaba fuera de cualquier guarda.
- `RootShell.kt` concatenaba la entrada en `sh -c` sin escapar `;`, `|`, `` ` `` ni `\n`.
- `SkillManager` aceptaba `npm install -g <skillId>` desde un endpoint anónimo (H-14).
- `DELETE /api/sessions/:id` no validaba contra `basePath` (path traversal, SEC-02).

Un dispositivo con Magisk y un puerto abierto en la red equivalía a entregar root a cualquiera.

## Decisión

**Root total *con* token, cero acceso sin él.** Cuatro capas, todas verificadas por tests:

1. **Bind de loopback.** El hub escucha únicamente en `127.0.0.1:8765` (A-1.2) y el
   `CompanionService` de la app en `127.0.0.1:8766` (A-1.3). No hay superficie en la IP LAN.
2. **Token obligatorio, comparación *timing-safe*.** Todo `/api/*` y **todo** `/opencode/*`
   exigen el header `X-Aegis-Token` (generado en el primer arranque con
   `crypto.randomBytes(32)`, guardado en `backend/.aegis_token` con modo `0600`, nunca impreso
   ni devuelto por HTTP). La comparación usa `crypto.timingSafeEqual` sobre buffers de igual
   longitud (server.js: `tokenMatches`). **Única exención: `GET /api/health`**, una sonda
   ligera sin secretos que consume `keepalive.sh` cada 10 s (si exigiera token, el bucle de
   salud recibiría 403 eternos y mataría/relanzaría el hub).
3. **Rate limiting.** 120 peticiones/min por IP → `429` + `Retry-After`
   (`{ok:false, error:{code:"RATE_LIMITED"}}`), aplicado **antes** del token para que un flood
   sin cabecera tampoco consuma CPU. Exentas `GET /api/health` y `GET /api/bootstrap/state`
   (polling del wizard a 1/s) para que la cuota nunca se gaste en salud ni en el asistente.
   Desactivable sólo vía `AEGIS_RATE_LIMIT=0` (tests/diagnóstico); techo configurable con
   `AEGIS_RATE_LIMIT_N`.
4. **CORS con allowlist.** Sólo se refleja el origin si está en la lista
   `["http://localhost:8765", "app://aegis"]` — jamás `*`.

**Complementos del mismo modelo:** meta-caracteres de shell rechazados con `400 BAD_REQUEST`
(`SHELL_META_RE = /[;|`]|&&|\n|\$\(/`), validación anti-traversal y de longitud (≤256) de
`projectId`/`sessionId`/`skillId` → `400 *_INVALID` *antes* de tocar store/fs, escaping en
`RootShell.kt`, y allowlist de skills en `SkillManager.install`.

## Consecuencias

**Positivas**
- La RCE anónima queda cerrada por construcción: sin token no hay ni router ni shell.
- El tráfico sensible jamás abandona el dispositivo salvo por un canal explícito.

**Negativas / obligaciones**
- **Acceso externo sólo vía ADB forward + token:**
  `adb forward tcp:8765 tcp:8765` desde el PC y luego
  `curl -H "X-Aegis-Token: <token>" http://127.0.0.1:8765/api/...`. No hay URL “de red”.
  (`start-hub.sh` lo explica en su salida: el aviso anterior de “red” era engañoso.)
- **`GET /api/health` es público** (exento) — por diseño es una sonda sin secretos, sin
  `df`/`su`/shells hijo; todo lo demás (`/api/system/health`, logs, setup, manifest) exige token.
- Toda petición de la app debe adjuntar el token: `ApiClient.authInterceptor` (OkHttp) y
  `TokenProvider` (HttpURLConnection) lo hacen; ante un 403 la app reintenta una vez tras releer
  el fichero (soporta rotación futura).
- El rate limit puede castigar a un cliente muy puntero; el wizard está exento para que la
  verificación y la sonda nunca reciban `429`.

## Plan de rotación del keystore (A-6.2)

- **Estado actual verificado:** `app/app/companion-release.keystore` **NO está trackeado**
  (`git ls-files app/app/*.keystore` → 0 entradas); sólo lo ignora `*.keystore` en
  `.gitignore`. *A-6 documenta la discrepancia: no se rota ni se borra en esta fase.*
- **Cuándo rotar:** al filtrarse, al salir de la organización, o preventivamente al menos una
  vez por ciclo de release mayor.
- **Procedimiento (fuera del árbol del repo):**
  1. Generar el nuevo keystore **en una ruta fuera del repo**:
     `keytool -genkeypair -v -keystore /fuera/del/arbol/aegis-release.keystore -alias aegis -keyalg RSA -keysize 2048 -validity 10000`
  2. Guardarlo como **secreto de GitHub Actions `KEYSTORE_BASE64`**
     (`base64 -w0 aegis-release.keystore`) junto a `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
     `KEY_PASSWORD`. El job `build-release` lo decodifica sólo si existen los secrets.
  3. **Invalidar el keystore viejo:** borrar copias locales y de CI, y — si se conoce uso
     comprometido — considerar revocar/reemitir la app en el canal de distribución usado.
     La app instalada con la clave vieja sólo podrá actualizarse con la nueva si se conserva
     la firma; si la firma cambia, toca desinstalar/reinstalar (documentarlo en el release).
  4. Registrar el evento en `CHANGELOG.md` (sección Security) y en este ADR.

## Referencias

- `docs/audits/AUDITORIA_BACKEND.md` (SEC-01..04, H-14) · `docs/audits/PLAN_MEJORA_FASE0.md` (A-1, A-6)
- `backend/server.js` (middleware de auth, rate limit, CORS, `tokenMatches`)
- `backend/tests/security.test.js`, `ratelimit.test.js`, `contract.test.js`
- `backend/docs/FRONTEND_CONTRACT.md` §7, §9
