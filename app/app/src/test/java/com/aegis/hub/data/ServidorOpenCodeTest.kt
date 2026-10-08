package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM de F6 (T-F6.2/T-F6.3): `ServidorOpenCode` (un solo vuelo, estados) y
 * `OpenCodeLauncher.lanzarViaScript` (despliegue + parseo de YA_HAY/LANZADO/ERROR).
 *
 * Todo con `shell` y sonda inyectados; el script real solo viaja como texto.
 */
class ServidorOpenCodeTest {

    private fun sinDemora(): suspend (Long) -> Unit = { }

    @Test
    fun `vivo no lanza`() = runBlocking {
        var lanzamientos = 0
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { true },
            lanzar = { lanzamientos++; Lanzamiento.Lanzado },
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Listo)
        assertEquals(0, lanzamientos)
    }

    @Test
    fun `ausente lanza una vez y queda Listo`() = runBlocking {
        var vivo = false
        var lanzamientos = 0
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { vivo },
            lanzar = { lanzamientos++; vivo = true; Lanzamiento.Lanzado },
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Listo)
        assertEquals(1, lanzamientos)
    }

    @Test
    fun `dos asegurar concurrentes hacen un solo lanzamiento`() = runBlocking {
        var vivo = false
        var lanzamientos = 0
        val resultados = awaitAll(
            async {
                ServidorOpenCode.asegurar(
                    comprobarSiVivo = { vivo },
                    lanzar = { lanzamientos++; vivo = true; Lanzamiento.Lanzado },
                    demora = sinDemora()
                )
            },
            async {
                ServidorOpenCode.asegurar(
                    comprobarSiVivo = { vivo },
                    lanzar = { lanzamientos++; vivo = true; Lanzamiento.Lanzado },
                    demora = sinDemora()
                )
            }
        )
        assertTrue(resultados.all { it is EstadoServidor.Listo })
        assertEquals(1, lanzamientos)
    }

    @Test
    fun `script devuelve ERROR y el estado lleva el motivo`() = runBlocking {
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { false },
            lanzar = { Lanzamiento.Error("sin-binario") },
            maxSondeos = 3,
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Error)
        assertTrue((final as EstadoServidor.Error).motivo.contains("sin-binario"))
    }

    @Test
    fun `timeout sin respuesta da Error con el motivo`() = runBlocking {
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { false },
            lanzar = { Lanzamiento.YaHay },
            maxSondeos = 3,
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Error)
        assertTrue((final as EstadoServidor.Error).motivo.contains("3 sondeos"))
    }

    @Test
    fun `YA_HAY espera al puerto sin lanzar`() = runBlocking {
        var sondeos = 0
        var lanzamientos = 0
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { ++sondeos >= 2 },
            lanzar = { lanzamientos++; Lanzamiento.YaHay },
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Listo)
        assertEquals(1, lanzamientos)
    }

    @Test
    fun `sin lanzar al principio reintenta una vez a mitad de camino`() = runBlocking {
        // OJO: `asegurar` comprueba una vez ANTES del bucle, asi que este contador
        // va uno por delante del contador interno (el reintento salta en el 10 interno).
        var sondeos = 0
        val lanzamientos = mutableListOf<String>()
        val final = ServidorOpenCode.asegurar(
            comprobarSiVivo = { ++sondeos >= 12 },
            lanzar = {
                lanzamientos.add("vez-${lanzamientos.size + 1}")
                if (lanzamientos.size == 1) Lanzamiento.YaHay else Lanzamiento.Lanzado
            },
            maxSondeos = 12,
            demora = sinDemora()
        )
        assertTrue(final is EstadoServidor.Listo)
        assertEquals(listOf("vez-1", "vez-2"), lanzamientos)
    }

    // ---- lanzarViaScript: despliegue + parseo ----

    private fun shellFijo(salida: String, codigo: Int = 0): (String, Long) -> RootShell.Result =
        { _, _ -> RootShell.Result(codigo, salida, "") }

    @Test
    fun `LANZADO se parsea y el comando despliega el script`() = runBlocking {
        var visto = ""
        val shell: (String, Long) -> RootShell.Result = { cmd, _ ->
            visto = cmd
            RootShell.Result(0, "LANZADO\n", "")
        }
        val r = OpenCodeLauncher.lanzarViaScript(shell) { "#!/system/bin/sh\necho hola\n" }
        assertTrue(r is Lanzamiento.Lanzado)
        assertTrue("despliega a /data/local/tmp: $visto", visto.contains("/data/local/tmp/aegis-serve.sh"))
        assertTrue("viaja en base64: $visto", visto.contains("base64 -d"))
        assertTrue("ejecuta el script: $visto", visto.contains("/system/bin/sh /data/local/tmp/aegis-serve.sh"))
    }

    @Test
    fun `YA_HAY y ERROR del script se mapean`() = runBlocking {
        val hay = OpenCodeLauncher.lanzarViaScript(shellFijo("YA_HAY:1\n")) { "x" }
        assertTrue(hay is Lanzamiento.YaHay)
        val err = OpenCodeLauncher.lanzarViaScript(shellFijo("ERROR:sin-binario\n")) { "x" }
        assertTrue(err is Lanzamiento.Error)
        assertEquals("sin-binario", (err as Lanzamiento.Error).motivo)
    }

    @Test
    fun `sin script o sin salida da Error con motivo`() = runBlocking {
        val sinScript = OpenCodeLauncher.lanzarViaScript(shellFijo("LANZADO")) { null }
        assertTrue(sinScript is Lanzamiento.Error)
        val sinSalida = OpenCodeLauncher.lanzarViaScript(shellFijo("")) { "x" }
        assertTrue(sinSalida is Lanzamiento.Error)
        assertTrue((sinSalida as Lanzamiento.Error).motivo.contains("sin-salida"))
    }
}
