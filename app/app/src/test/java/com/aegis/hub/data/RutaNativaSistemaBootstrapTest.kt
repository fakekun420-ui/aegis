package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RutaNativaSistemaBootstrapTest {

    @Test
    fun `parseo de meminfo calcula memoria usada y total correctamente`() {
        val meminfoEjemplo = """
            MemTotal:        5864192 kB
            MemFree:          421340 kB
            MemAvailable:    2150400 kB
            Buffers:          123456 kB
            Cached:          1800000 kB
        """.trimIndent()

        val lines = meminfoEjemplo.lines()
        var totalKb = 0L
        var freeKb = 0L
        var availableKb = 0L
        for (line in lines) {
            if (line.startsWith("MemTotal:")) {
                totalKb = line.substringAfter(":").trim().split(" ").firstOrNull()?.toLongOrNull() ?: 0L
            } else if (line.startsWith("MemAvailable:")) {
                availableKb = line.substringAfter(":").trim().split(" ").firstOrNull()?.toLongOrNull() ?: 0L
            } else if (line.startsWith("MemFree:")) {
                freeKb = line.substringAfter(":").trim().split(" ").firstOrNull()?.toLongOrNull() ?: 0L
            }
        }
        val usedKb = if (availableKb > 0) (totalKb - availableKb) else (totalKb - freeKb)
        val memData = MemoryData(
            heapUsed = "${usedKb / 1024}MB",
            heapTotal = "${totalKb / 1024}MB"
        )

        assertEquals("5726MB", memData.heapTotal)
        assertEquals("3626MB", memData.heapUsed)
    }

    @Test
    fun `parseo de uptime obtiene segundos reales`() {
        val uptimeEjemplo = "123456.78 987654.32\n"
        val uptimeSec = uptimeEjemplo.trim().split("\\s+".toRegex()).firstOrNull()?.toDoubleOrNull()?.toLong() ?: 0L
        assertEquals(123456L, uptimeSec)
    }

    @Test
    fun `BootstrapActionResponse maneja fases y errores sin npe`() {
        val actionSuccess = BootstrapActionResponse(
            ok = true,
            data = BootstrapActionData(phase = BootstrapPhase.running),
            error = null
        )
        assertTrue(actionSuccess.ok)
        assertEquals(BootstrapPhase.running, actionSuccess.data?.phase)

        val actionFail = BootstrapActionResponse(
            ok = false,
            data = null,
            error = ErrorBody(code = "BOOTSTRAP_FAILED", message = "Permiso denegado")
        )
        assertFalse(actionFail.ok)
        assertEquals("BOOTSTRAP_FAILED", actionFail.error?.code)
    }

    @Test
    fun `AuthGuideResponse contiene comando estandar`() {
        val authResp = AuthGuideResponse(
            ok = true,
            data = AuthGuideData(
                mode = "command",
                command = "opencode serve --service",
                status = "unauthenticated"
            )
        )
        assertTrue(authResp.ok)
        assertEquals("opencode serve --service", authResp.data?.command)
        assertEquals("unauthenticated", authResp.data?.status)
    }

    @Test
    fun `SmokeTestResponse contiene data de reply correctamente`() {
        val smoke = SmokeTestResponse(
            ok = true,
            data = SmokeTestData(ok = true, reply = "PONG"),
            error = null
        )
        assertTrue(smoke.ok)
        assertEquals("PONG", smoke.data?.reply)
    }
}
