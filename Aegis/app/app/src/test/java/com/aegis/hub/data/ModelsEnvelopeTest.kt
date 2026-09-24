package com.aegis.hub.data

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ==== F4 — tests JVM del contrato JSON (shapes del hub) con Gson ====
//
// Verifican que los data classes de Models.kt parsean EXACTAMENTE los shapes
// que emite el backend (server.js normaliza el envelope a {ok, data, error} y
// setupRoutes.js/state.js generan data/checks/steps). Se usa el MISMO parser
// que ApiClient (GsonBuilder().setLenient()) para que el test reproduzca el
// comportamiento real de producción. Sin red, sin Android: fixtures inline.
class ModelsEnvelopeTest {

    // Mismo setup que ApiClient.gson (ApiClient.kt L77-79)
    private val gson = GsonBuilder().setLenient().create()

    // ------------------------------------------------------------------
    // GET /api/bootstrap/state → 6 pasos (STEP_DEFS reales de state.js)
    // ------------------------------------------------------------------
    @Test
    fun `bootstrap state con 6 pasos parsea los STEP_DEFS reales`() {
        val json = """
            {
              "ok": true,
              "data": {
                "phase": "running",
                "currentStepId": "ubuntu",
                "startedAt": "2026-09-24T10:00:00.000Z",
                "updatedAt": "2026-09-24T10:05:00.000Z",
                "lastError": null,
                "steps": [
                  {"id":"preflight","title":"Comprobación previa","status":"done","rollback":"none","progress":100,"detail":"ok","error":null},
                  {"id":"ubuntu","title":"Ubuntu (chroot/proot)","status":"running","rollback":"none","progress":40,"detail":"instalando","error":null},
                  {"id":"node","title":"Node.js","status":"pending","rollback":"none","progress":0,"detail":"pendiente","error":null},
                  {"id":"opencode","title":"OpenCode","status":"pending","rollback":"none","progress":0,"detail":"pendiente","error":null},
                  {"id":"antigravity","title":"Antigravity / Artemis","status":"pending","rollback":"none","progress":0,"detail":"pendiente","error":null},
                  {"id":"skills","title":"Skills y plugins","status":"pending","rollback":"none","progress":0,"detail":"pendiente","error":null}
                ]
              }
            }
        """.trimIndent()

        val res = gson.fromJson(json, BootstrapResponse::class.java)

        assertTrue(res.ok)
        val data = res.data
        assertNotNull(data)
        assertEquals(BootstrapPhase.running, data!!.phaseOrIdle)
        assertEquals("ubuntu", data.currentStepId)
        assertEquals(6, data.stepList.size)
        // Orden y títulos EXACTOS de STEP_DEFS (state.js L42-47)
        assertEquals("preflight", data.stepList[0].id)
        assertEquals("Comprobación previa", data.stepList[0].title)
        assertEquals("Ubuntu (chroot/proot)", data.stepList[1].title)
        assertEquals("Node.js", data.stepList[2].title)
        assertEquals("OpenCode", data.stepList[3].title)
        assertEquals("Antigravity / Artemis", data.stepList[4].title)
        assertEquals("Skills y plugins", data.stepList[5].title)
        assertEquals(BootstrapStepStatus.done, data.stepList[0].status)
        assertEquals(BootstrapStepStatus.running, data.stepList[1].status)
        assertEquals(BootstrapStepStatus.pending, data.stepList[2].status)
        assertEquals(40, data.stepList[1].progress)
        assertNull(res.error)
    }

    // ------------------------------------------------------------------
    // Campos null del contrato → defaults sin NPE (helpers de Models.kt)
    // ------------------------------------------------------------------
    @Test
    fun `bootstrap state con nulls aplica los defaults documentados`() {
        val json = """
            {"ok": true, "data": {"phase": null, "currentStepId": null, "startedAt": null,
             "updatedAt": null, "lastError": null, "steps": null}}
        """.trimIndent()

        val res = gson.fromJson(json, BootstrapResponse::class.java)
        val data = res.data

        assertNotNull(data)
        // phase null → idle; steps null → lista vacía (nunca NPE)
        assertEquals(BootstrapPhase.idle, data!!.phaseOrIdle)
        assertTrue(data.stepList.isEmpty())
        assertNull(data.currentStepId)
        assertNull(data.lastError)

        // status null → pending (BootstrapStep.statusOrPending)
        val step = BootstrapStep(id = "x", title = "t", status = null)
        assertEquals(BootstrapStepStatus.pending, step.statusOrPending)

        // status desconocido de versiones futuras → helpers tolerantes
        val check = SetupCheck(id = "x", label = "t", status = null)
        assertEquals(SetupCheckStatus.manual, check.statusOrManual)

        // Sin data en absoluto → data null (la UI trata null aparte)
        val empty = gson.fromJson("""{"ok": true}""", BootstrapResponse::class.java)
        assertNull(empty.data)
    }

    // ------------------------------------------------------------------
    // GET /api/setup/final-check → 4 checks (CHECK_DEFS) + ready
    // ------------------------------------------------------------------
    @Test
    fun `final check parsea los 4 CHECK_DEFS con estados mixtos`() {
        val json = """
            {
              "ok": true,
              "data": {
                "ready": true,
                "checks": [
                  {"id":"opencode","label":"OpenCode (proxy4096)","status":"ok","detail":"responde en :4096"},
                  {"id":"antigravity","label":"Antigravity/Artemis (agy + auth)","status":"ok","detail":"agy + auth ok"},
                  {"id":"a11y","label":"Servicio de accesibilidad (:8766)","status":"manual","detail":"revísalo a mano"},
                  {"id":"bootstrap","label":"Instalación inicial (wizard)","status":"ok","detail":"wizard done"}
                ]
              }
            }
        """.trimIndent()

        val res = gson.fromJson(json, FinalCheckResponse::class.java)

        assertTrue(res.ok)
        val data = res.data
        assertNotNull(data)
        assertTrue(data!!.ready)
        assertEquals(4, data.checkList.size)
        // Labels EXACTOS de CHECK_DEFS (setupRoutes.js L151-227)
        assertEquals("opencode", data.checkList[0].id)
        assertEquals("OpenCode (proxy4096)", data.checkList[0].label)
        assertEquals("Antigravity/Artemis (agy + auth)", data.checkList[1].label)
        assertEquals("Servicio de accesibilidad (:8766)", data.checkList[2].label)
        assertEquals("Instalación inicial (wizard)", data.checkList[3].label)
        assertEquals(SetupCheckStatus.ok, data.checkList[0].status)
        assertEquals(SetupCheckStatus.ok, data.checkList[1].status)
        assertEquals(SetupCheckStatus.manual, data.checkList[2].status)
        assertEquals(SetupCheckStatus.ok, data.checkList[3].status)
        assertEquals("revísalo a mano", data.checkList[2].detail)
    }

    @Test
    fun `final check con ready false y check fallido`() {
        val json = """
            {
              "ok": true,
              "data": {
                "ready": false,
                "checks": [
                  {"id":"opencode","label":"OpenCode (proxy4096)","status":"fail","detail":"sin respuesta"},
                  {"id":"antigravity","label":"Antigravity/Artemis (agy + auth)","status":"fail","detail":"falta auth"},
                  {"id":"a11y","label":"Servicio de accesibilidad (:8766)","status":"fail","detail":"puerto 8766 caído"},
                  {"id":"bootstrap","label":"Instalación inicial (wizard)","status":"ok","detail":"wizard done"}
                ]
              }
            }
        """.trimIndent()

        val res = gson.fromJson(json, FinalCheckResponse::class.java)
        val data = res.data

        assertNotNull(data)
        assertFalse(data!!.ready)
        assertEquals(SetupCheckStatus.fail, data.checkList[0].status)
        assertEquals(SetupCheckStatus.fail, data.checkList[1].status)
        assertEquals(SetupCheckStatus.fail, data.checkList[2].status)
        assertEquals(SetupCheckStatus.ok, data.checkList[3].status)
        // checks null → lista vacía
        val none = gson.fromJson(
            """{"ok": true, "data": {"ready": false, "checks": null}}""",
            FinalCheckResponse::class.java
        )
        assertTrue(none.data!!.checkList.isEmpty())
    }

    // ------------------------------------------------------------------
    // POST /api/setup/smoke-test → éxito {ok,data:{ok,reply}} y SMOKE_FAILED
    // ------------------------------------------------------------------
    @Test
    fun `smoke test de éxito parsea data ok y reply`() {
        val json = """
            {"ok": true, "data": {"ok": true, "reply": "¡Hola! Soy tu asistente Aegis."}}
        """.trimIndent()

        val res = gson.fromJson(json, SmokeTestResponse::class.java)

        assertTrue(res.ok)
        assertEquals(true, res.data!!.ok)
        assertEquals("¡Hola! Soy tu asistente Aegis.", res.data!!.reply)
        assertNull(res.error)
    }

    @Test
    fun `smoke test SMOKE_FAILED parsea el error del envelope`() {
        val json = """
            {"ok": false, "data": null,
             "error": {"code": "SMOKE_FAILED", "message": "El modelo no respondió en 30 s"}}
        """.trimIndent()

        val res = gson.fromJson(json, SmokeTestResponse::class.java)

        assertFalse(res.ok)
        assertNull(res.data)
        assertNotNull(res.error)
        assertEquals("SMOKE_FAILED", res.error!!.code)
        assertEquals("El modelo no respondió en 30 s", res.error!!.message)
    }

    // ------------------------------------------------------------------
    // Envelope de error normalizado (server.js) → códigos reales
    // ------------------------------------------------------------------
    @Test
    fun `error FORBIDDEN 403 se parsea con su code y message`() {
        val json = """
            {"ok": false, "data": null,
             "error": {"code": "FORBIDDEN", "message": "El hub rechazó la petición (403)"}}
        """.trimIndent()

        val res = gson.fromJson(json, BootstrapResponse::class.java)

        assertFalse(res.ok)
        assertNull(res.data)
        assertEquals("FORBIDDEN", res.error!!.code)
        assertEquals("El hub rechazó la petición (403)", res.error!!.message)
    }

    @Test
    fun `error NOT_FOUND 404 se parsea con su code y message`() {
        val json = """
            {"ok": false, "data": null,
             "error": {"code": "NOT_FOUND", "message": "Paso no encontrado"}}
        """.trimIndent()

        val res = gson.fromJson(json, BootstrapActionResponse::class.java)

        assertFalse(res.ok)
        assertNull(res.data)
        assertEquals("NOT_FOUND", res.error!!.code)
        assertEquals("Paso no encontrado", res.error!!.message)
    }

    // ------------------------------------------------------------------
    // POST /api/setup/auth/antigravity → guía de autenticación
    // ------------------------------------------------------------------
    @Test
    fun `auth guide parsea mode command y status`() {
        val json = """
            {
              "ok": true,
              "data": {
                "mode": "command",
                "command": "agy auth login --provider antigravity",
                "status": "unauthenticated"
              }
            }
        """.trimIndent()

        val res = gson.fromJson(json, AuthGuideResponse::class.java)

        assertTrue(res.ok)
        assertEquals("command", res.data!!.mode)
        assertEquals("agy auth login --provider antigravity", res.data!!.command)
        assertEquals("unauthenticated", res.data!!.status)
    }

    // ------------------------------------------------------------------
    // POST /api/bootstrap/run (202) → acción aceptada con fase
    // ------------------------------------------------------------------
    @Test
    fun `bootstrap action 202 parsea la fase devuelta`() {
        val json = """{"ok": true, "data": {"phase": "running"}}"""

        val res = gson.fromJson(json, BootstrapActionResponse::class.java)

        assertTrue(res.ok)
        assertEquals(BootstrapPhase.running, res.data!!.phase)
        assertNull(res.error)
    }

    @Test
    fun `bootstrap action con error ALREADY_RUNNING`() {
        val json = """
            {"ok": false, "data": null,
             "error": {"code": "ALREADY_RUNNING", "message": "Ya hay una instalación en curso"}}
        """.trimIndent()

        val res = gson.fromJson(json, BootstrapActionResponse::class.java)

        assertFalse(res.ok)
        assertEquals("ALREADY_RUNNING", res.error!!.code)
        assertEquals("Ya hay una instalación en curso", res.error!!.message)
    }
}
