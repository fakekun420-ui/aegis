package com.aegis.hub.data.flujo

import com.aegis.hub.data.ProjectsStore
import com.aegis.hub.data.repo.ConfigCache
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.SesionesRepo
import com.aegis.hub.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Flujo "+ Nuevo chat" global, de punta a punta (V-04) — el camino que ejecuta el FAB de Chats.
 *
 * MEDIDO 2026-10-10 (V-04 en fallo): el título valía
 * `"Chat ${System.currentTimeMillis() % 10000}"`, y ese título es la mitad de la clave de
 * idempotencia (`"$title|$proyectoId"`). Cada pulsación generaba una clave distinta, la ventana de
 * 3 s de [SesionesRepo] nunca se cumplía y tres toques seguidos creaban TRES sesiones. Antes de
 * arreglar se reprodujo la lógica exacta (3钥匙 distintas -> 3 sesiones).
 *
 * Estos tests van contra el `MainViewModel` REAL (no una reimplementación) y contra un
 * MockWebServer, así que fijan el contrato entero: botón -> ViewModel -> repo -> HTTP.
 * El reloj se inyecta en [SesionesRepo], no se usa `System.currentTimeMillis`: el defecto estaba
 * justamente en el reloj, y un test con reloj real no lo distingue de un resultado correcto.
 *
 * MEDIDO 2026-10-10 (run 38088984890): con `runTest` + reloj virtual estos tests NO corren —
 * `UncompletedCoroutinesError` a los 60 s. Motivo: las llamadas HTTP son E/S real (MockWebServer
 * en hilos propios), que el reloj virtual de `runTest` no puede drenar. Se usa el mismo patrón
 * que el resto de los tests de flujo de este repo ([FlujoAbrirChatTest], `runBlocking`) y el de
 * `ConfigSesionVmTest` para los ViewModel (`UnconfinedTestDispatcher` como Main).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlujoNuevoChatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        // Unconfined, no Standard: `nuevoChat` lanza `refreshSessions()` en viewModelScope
        // (Main.immediate) y con el scheduler en cola el test se queda esperando.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake = FakeOpenCode()
    }

    @After
    fun parar() {
        Dispatchers.resetMain()
        fake.cerrar()
    }

    private class Cache : ConfigCache {
        override fun leerModelo(sid: String): String? = null
        override fun guardarModelo(sid: String, modelo: String) = Unit
        override fun leerAgente(sid: String): String? = null
        override fun guardarAgente(sid: String, agente: String) = Unit
        override fun ultimoModelo(): String? = null
        override fun guardarUltimoModelo(modelo: String) = Unit
        override fun ultimoAgente(): String? = null
        override fun guardarUltimoAgente(agente: String) = Unit
    }

    private var ahora = 1_700_000_000_000L

    /**
     * ViewModel real con la costura inyectada. `arrancarSondeo = false`: el poll infinito del
     * `init` es un poll INFINITO y dejaría el test sin jobs que drenar.
     */
    private fun vm(): MainViewModel {
        val oc = fake.api()
        val store = ProjectsStore(
            storeFile = tempFolder.newFile("store.json"),
            legacyFile = tempFolder.newFile("store.legacy"),
            clock = { ahora }
        )
        val repo = SesionesRepo(oc, store, SesionConfigRepo(oc, Cache()) { ahora }) { ahora }
        return MainViewModel(
            api = com.aegis.hub.data.RutaNativa(oc = oc, store = store),
            projectsStore = store,
            sesiones = repo,
            arrancarSondeo = false
        )
    }

    @Test
    fun `un solo POST nace en la carpeta global con agente y modelo`() = runBlocking {
        val vm = vm()

        val sid = vm.nuevoChat()

        assertNotNull("nuevoChat devuelve id o null con motivo", sid)
        assertEquals(1, fake.contarExacto("POST", "/api/session"))
        assertEquals(1, fake.contar("POST", "/api/session/$sid/agent"))
        assertEquals(1, fake.contar("POST", "/api/session/$sid/model"))
        // F11: sin `directory` el servidor usa su cwd (/root) y el agente arranca fuera del
        // workspace. El chat global tiene que nacer en /sdcard/projects.
        assertTrue(
            "location debe llevar /sdcard/projects: ${fake.cuerposCreacion}",
            fake.cuerposCreacion[0].contains("/sdcard/projects")
        )
        assertTrue(fake.agentesFijados.isNotEmpty())
        assertTrue(fake.modelosFijados.isNotEmpty())
        assertNull("sin errores: ${vm.error.value}", vm.error.value)
    }

    /** El fallo que se estaba midiendo: 3 toques en 400 ms deben dar 1 sesión, no 3. */
    @Test
    fun `tres toques seguidos crean una sola sesion`() = runBlocking {
        val vm = vm()

        val primero = vm.nuevoChat()
        ahora += 200
        val segundo = vm.nuevoChat()
        ahora += 200
        val tercero = vm.nuevoChat()

        assertEquals(
            "los tres toques deben devolver la MISMA sesión",
            listOf(primero, segundo, tercero).distinct().size, 1
        )
        assertEquals(1, fake.contarExacto("POST", "/api/session"))
        assertEquals(1, fake.crearSesiones.get())
        assertNull(vm.error.value)
    }

    /**
     * V-04-RESERVA-ATOMICA (2026-10-13): el doble toque CONCURRENTE, no secuencial.
     *
     * MEDIDO en dispositivo (APK 1.2.1/401): dos toques a 122 ms creaban 2 sesiones porque la
     * guarda de 3 s comprobaba/registraba DESPUES del await de la red (carrera check-then-act):
     * los dos toques pasaban la comprobacion antes de que ninguno registrara. El test de los
     * tres toques de arriba NO cubre esto (es secuencial: cada `nuevoChat()` termina antes del
     * siguiente, asi que la ventana lo contiene con o sin fix).
     *
     * `LENTO` (300 ms por peticion) deja al primer toque en vuelo cuando entra el segundo a
     * 50 ms (<=150 ms, como los 122 ms medidos). Sin el fix salen 2 POST; con el fix, 1.
     */
    @Test
    fun `doble toque concurrente crea una sola sesion`() = runBlocking {
        val vm = vm()
        fake.modo = FakeOpenCode.Modo.LENTO

        val primero = async { vm.nuevoChat() }
        delay(50)
        val segundo = async { vm.nuevoChat() }

        val ids = listOf(primero.await(), segundo.await())
        assertEquals(
            "el doble toque concurrente devuelve la MISMA sesión: $ids",
            1, ids.distinct().size
        )
        assertEquals(1, fake.contarExacto("POST", "/api/session"))
        assertEquals(1, fake.crearSesiones.get())
        assertNull(vm.error.value)
    }

    /**
     * El otro lado del mismo contrato: pasada la ventana, un "Nuevo chat" de verdad tiene que
     * crear otra sesión. Si esto fallara, el arreglo sería "no crear nunca", que no es lo que se
     * pidió — y sería tan invisible como el bug original.
     */
    @Test
    fun `pasada la ventana de 3 s un toque nuevo si crea otra sesion`() = runBlocking {
        val vm = vm()

        val primero = vm.nuevoChat()
        ahora += SesionesRepo.VENTANA_IDEMPOTENCIA_MS + 1
        val segundo = vm.nuevoChat()

        assertTrue("dos chats distintos: $primero vs $segundo", primero != segundo)
        assertEquals(2, fake.contarExacto("POST", "/api/session"))
        assertEquals(2, fake.crearSesiones.get())
    }

    /**
     * El título NO puede llevar reloj. Es el contrato que hace posible el test de los tres toques:
     * si vuelve un `System.currentTimeMillis()`, cada toque manda un título distinto, la clave
     * cambia y la deduplicación desaparece. Aquí se mira lo que LLEGA al servidor en dos toques
     * separados por tiempo — no una constante comparada consigo misma, que no probaría nada.
     */
    @Test
    fun `el titulo enviado al servidor es el mismo en dos toques`() = runBlocking {
        val vm = vm()

        vm.nuevoChat()
        // PASADA la ventana de 3 s: si no, el segundo toque se deduplicaría y no habría
        // segundo POST que comparar (la deduplicación es lo que fija el test de los 3 toques).
        ahora += SesionesRepo.VENTANA_IDEMPOTENCIA_MS + 1
        vm.nuevoChat()

        assertEquals(2, fake.cuerposCreacion.size)
        assertEquals(
            "los dos POST llevaron el mismo titulo",
            fake.cuerposCreacion[0], fake.cuerposCreacion[1]
        )
        assertTrue(
            "el titulo es el contrato, sin digitos de reloj: ${fake.cuerposCreacion[0]}",
            MainViewModel.TITULO_NUEVO_CHAT.none { it.isDigit() }
        )
    }

    /** Si el servidor cae, `nuevoChat` devuelve null CON motivo — no una navegación muda. */
    @Test
    fun `si el servidor falla hay motivo y no hay sesion a la que navegar`() = runBlocking {
        val vm = vm()
        fake.modo = FakeOpenCode.Modo.FALLO_500

        val sid = vm.nuevoChat()

        assertNull("sin sesion no se navega", sid)
        assertNotNull("el motivo tiene que ser visible", vm.error.value)
        assertTrue("motivo con texto: ${vm.error.value}", vm.error.value!!.isNotBlank())
    }
}
