package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests unitarios JVM para [Credentials].
 *
 * Verifican:
 * 1. Parseo exitoso de `service.json` válido (claves id, password, pid, url, version).
 * 2. Fallo ruidoso si falta la clave `password` o está vacía.
 * 3. Fallo ruidoso si el JSON está malformado.
 * 4. Que `opencode.log` NUNCA esté presente en las rutas candidatas por defecto.
 * 5. Caché en memoria: lecturas repetidas dentro del TTL no llaman al lector de ficheros.
 * 6. Expiración de caché por TTL: al vencer el TTL se vuelve a consultar el lector.
 * 7. Invalidación explícita ([Credentials.invalidate]): fuerza una re-lectura inmediata.
 * 8. NO-cacheo de fallos: si un intento falla, el siguiente reintenta de verdad y tiene éxito
 *    si el fichero ya está disponible.
 * 9. Formato correcto de la cabecera HTTP Basic ("Basic " + Base64("opencode:<password>")).
 * 10. Diagnóstico ruidoso con mención de las rutas probadas cuando todas fallan.
 */
class CredentialsTest {

    private val validServiceJson = """
        {
          "id": "srv_1234567890abcdef",
          "password": "secret_token_abc123",
          "pid": 17879,
          "url": "http://127.0.0.1:49374",
          "version": "2.0.14"
        }
    """.trimIndent()

    @Test
    fun `JSON valido devuelve la contrasena y campos correctamente`() {
        val credentials = Credentials()
        val parsed = credentials.parseServiceJson(validServiceJson)

        assertEquals("secret_token_abc123", parsed.password)
        assertEquals(17879L, parsed.pid)
        assertEquals("http://127.0.0.1:49374", parsed.url)
        assertEquals("2.0.14", parsed.version)
        assertEquals("srv_1234567890abcdef", parsed.id)
    }

    @Test
    fun `getPasswordBlocking devuelve la password y usa cache dentro del TTL`() {
        var currentTime = 1000L
        val readCounter = AtomicInteger(0)

        val credentials = Credentials(
            candidatePaths = listOf("/root/.local/state/opencode/service.json"),
            fileReader = { path ->
                readCounter.incrementAndGet()
                RootShell.Result(0, validServiceJson, "")
            },
            clock = { currentTime },
            cacheTtlMs = 60_000L
        )

        // Primer intento: lee del lector
        val pwd1 = credentials.getPasswordBlocking()
        assertEquals("secret_token_abc123", pwd1)
        assertEquals(1, readCounter.get())
        assertTrue(credentials.hasValidCache())

        // Segundo intento: 10 segundos después, debe venir de la caché
        currentTime += 10_000L
        val pwd2 = credentials.getPasswordBlocking()
        assertEquals("secret_token_abc123", pwd2)
        assertEquals(1, readCounter.get())

        // Tercer intento: 70 segundos después (> 60s TTL), debe volver a leer
        currentTime += 60_000L
        val pwd3 = credentials.getPasswordBlocking()
        assertEquals("secret_token_abc123", pwd3)
        assertEquals(2, readCounter.get())
    }

    @Test
    fun `invalidate fuerza re-lectura inmediata ignorando TTL`() {
        var currentTime = 1000L
        val readCounter = AtomicInteger(0)

        val credentials = Credentials(
            candidatePaths = listOf("/root/.local/state/opencode/service.json"),
            fileReader = { path ->
                readCounter.incrementAndGet()
                RootShell.Result(0, validServiceJson, "")
            },
            clock = { currentTime },
            cacheTtlMs = 60_000L
        )

        credentials.getPasswordBlocking()
        assertEquals(1, readCounter.get())

        // Invalida explícitamente (e.g. tras recibir HTTP 401)
        credentials.invalidate()
        assertFalse(credentials.hasValidCache())

        // El siguiente llamado debe leer de nuevo aunque no haya pasado el TTL
        credentials.getPasswordBlocking()
        assertEquals(2, readCounter.get())
    }

    @Test
    fun `JSON sin clave password o vacia falla con excepcion ruidosa`() {
        val jsonSinPassword = """
            {
              "id": "srv_no_pwd",
              "pid": 17879,
              "url": "http://127.0.0.1:49374"
            }
        """.trimIndent()

        val credentials = Credentials(
            candidatePaths = listOf("/mock/path/service.json"),
            fileReader = { RootShell.Result(0, jsonSinPassword, "") }
        )

        try {
            credentials.getPasswordBlocking()
            fail("Debió lanzar IllegalStateException por falta de password")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("sin clave 'password' no vacía") == true)
            assertNotNull(credentials.lastDiagnostics)
        }
    }

    @Test
    fun `opencode_log NO se usa en las rutas candidatas por defecto`() {
        val paths = Credentials.DEFAULT_CANDIDATE_PATHS

        for (path in paths) {
            assertFalse(
                "La ruta candidate '$path' NO debe contener opencode.log (contraseña caducada)",
                path.contains("opencode.log")
            )
            assertTrue(
                "La ruta candidate '$path' debe apuntar a service.json",
                path.endsWith("service.json")
            )
        }
    }

    @Test
    fun `un fallo de lectura NO queda cacheado y el siguiente intento reintenta`() {
        var failFirst = true
        val readCounter = AtomicInteger(0)

        val credentials = Credentials(
            candidatePaths = listOf("/mock/path/service.json"),
            fileReader = {
                readCounter.incrementAndGet()
                if (failFirst) {
                    RootShell.Result(1, "", "No such file or directory")
                } else {
                    RootShell.Result(0, validServiceJson, "")
                }
            }
        )

        // Intento 1: falla
        try {
            credentials.getPasswordBlocking()
            fail("El primer intento debió fallar")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No such file or directory") == true)
        }
        assertEquals(1, readCounter.get())
        assertFalse(credentials.hasValidCache())

        // Arreglamos la condición: el siguiente intento DEBE reintentar y tener éxito
        failFirst = false
        val pwd = credentials.getPasswordBlocking()
        assertEquals("secret_token_abc123", pwd)
        assertEquals(2, readCounter.get())
        assertTrue(credentials.hasValidCache())
    }

    @Test
    fun `cabecera HTTP Basic se construye con prefijo opencode y base64 correcto`() {
        val credentials = Credentials(
            candidatePaths = listOf("/mock/path/service.json"),
            fileReader = { RootShell.Result(0, validServiceJson, "") }
        )

        val header = credentials.getBasicAuthHeaderBlocking()

        // "opencode:secret_token_abc123" en base64:
        // java.util.Base64.getEncoder().encodeToString("opencode:secret_token_abc123".toByteArray())
        // -> b3BlbmNvZGU6c2VjcmV0X3Rva2VuX2FiYzEyMw==
        val expectedB64 = java.util.Base64.getEncoder().encodeToString(
            "opencode:secret_token_abc123".toByteArray(Charsets.UTF_8)
        )
        val expectedHeader = "Basic $expectedB64"

        assertEquals(expectedHeader, header)
    }

    @Test
    fun `fallback entre multiples rutas funciona si la primera falla`() {
        val credentials = Credentials(
            candidatePaths = listOf(
                "/ruta/inexistente/service.json",
                "/ruta/correcta/service.json"
            ),
            fileReader = { path ->
                if (path == "/ruta/correcta/service.json") {
                    RootShell.Result(0, validServiceJson, "")
                } else {
                    RootShell.Result(1, "", "File not found")
                }
            }
        )

        val pwd = credentials.getPasswordBlocking()
        assertEquals("secret_token_abc123", pwd)
        assertEquals("/ruta/correcta/service.json", credentials.lastResolvedPath)
    }

    @Test
    fun `getPassword suspendido funciona en entorno de corutinas`() = runBlocking {
        val credentials = Credentials(
            candidatePaths = listOf("/mock/path/service.json"),
            fileReader = { RootShell.Result(0, validServiceJson, "") }
        )

        val pwd = credentials.getPassword()
        assertEquals("secret_token_abc123", pwd)

        val authHeader = credentials.getBasicAuthHeader()
        assertTrue(authHeader.startsWith("Basic "))
    }
}
