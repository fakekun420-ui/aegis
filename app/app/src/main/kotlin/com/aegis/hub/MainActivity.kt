package com.aegis.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.aegis.hub.ui.AppNavHost
import com.aegis.hub.ui.NavRoutes
import com.aegis.hub.data.AppContext

/**
 * Actividad principal de Aegis.
 *
 * NOTA DE ARQUITECTURA (2026-10-01 - Paquete F / Decisión del usuario):
 * Se retiró el servicio en primer plano (CompanionService) para eliminar la notificación
 * permanente solicitada por el usuario. Al no existir un Foreground Service, Android
 * puede matar el proceso de la aplicación cuando ésta pasa a segundo plano o se cierra.
 * Por diseño, el sondeo/polling de fin de turno y las notificaciones dejan de funcionar
 * con la app cerrada (solo operan mientras la app permanece viva o en primer plano).
 * Esto no es un bug, es el comportamiento aceptado y esperado.
 */
class MainActivity : ComponentActivity() {


    private var systemReady by mutableStateOf(false)
    private var systemOwnership by mutableStateOf("unknown")
    private var isStartingSystem by mutableStateOf(false)
    // F1: ruta de arranque decidida al arrancar (null = pendiente de decidir).
    // null → AppNavHost aún no se compone; el overlay "Sistema desconectado" lo tapa.
    private var initialRoute by mutableStateOf<String?>(null)

    private val reqMic = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Micrófono concedido") else toast("Micrófono denegado — STT no funcionará")
    }
    private val reqContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Contactos concedidos") else toast("Contactos denegados — WhatsApp por nombre no funcionará")
    }
    private val reqCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Cámara concedida") else toast("Cámara denegada — no se podrán adjuntar fotos")
    }

    // registerForActivityResult DEBE registrarse antes de que la actividad llegue a
    // STARTED, si no lanza IllegalStateException. Antes se llamaba aquí dentro, desde
    // ensurePermissions() (que corre en onCreate), y en el mejor de los casos se perdía
    // el resultado: la app pedía notificaciones y nunca se enteraba de la respuesta.
    private val reqNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Notificaciones concedidas") else toast("Notificaciones denegadas — no avisaré al terminar cada turno")
    }

    /**
     * ¿Se ha concedido ya "Acceso a todos los archivos"? Se recuerda para poder avisar
     * SOLO en el instante en que el usuario vuelve de Ajustes y lo ha concedido, en
     * lugar de soltar un toast en cada onResume.
     */
    private var allFilesWarned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // F0 (T-F0.4): en debug, cualquier E/S, red o `su` en el hilo principal sale
        // en logcat. Solo penaltyLog, nunca penaltyDeath: esto es un radar, no un muro.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        // Los ViewModel necesitan leer preferencias (p. ej. el modelo por sesión) y no
        // llevan Context en el constructor a propósito. Se les da el de aplicación aquí.
        AppContext.init(applicationContext)
        // MEDIDO 2026-10-08 (StrictMode DiskReadViolation en arranque 1.2.0):
        // `ProjectsStore.default` es `by lazy` y sus argumentos por defecto tocan
        // `filesDir` (crea el dir tras instalar). Quien lo estrene en el hilo
        // principal paga ~1 s de E/S ahi. Se estrena aqui en IO; lo posterior
        // reutiliza el valor cacheado y no toca disco por este camino.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.aegis.hub.data.ProjectsStore.default }
                .onFailure { android.util.Log.w(TAG, "precalentado ProjectsStore: ${it.message}") }
        }
        // El ViewModel no tiene Context; se lo damos una vez para el aviso de
        // "respuesta final" en la barra de notificaciones.
        com.aegis.hub.ui.TurnNotifier.init(this)
        // La senal de "hay algo a la vista" la da Android, no un booleano. Se registra UNA
        // vez (el companion object lo cachea). Sin esto, `isAppVisible` se queda en 0 para
        // siempre y el aviso de fin de turno no sale nunca: el fallo seria silencioso.
        registerVisibleTracker()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.isNavigationBarContrastEnforced = true
        }


        setContent {
            com.aegis.hub.ui.theme.OpenCodeCompanionTheme {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // F1: sólo se monta cuando comprobarServidorAlArrancar ha decidido la ruta inicial
                    // (SETUP si el bootstrap no terminó; DRAFT_CHAT = comportamiento actual).
                    // F2 (ambigüedad #7): key(start) → si "Iniciar Sistema" levanta el servidor con
                    // bootstrap pendiente, initialRoute pasa DRAFT_CHAT→SETUP y el NavHost se
                    // recrea arrancando en el wizard (mismo efecto que un navigate de arranque).
                    initialRoute?.let { start ->
                        key(start) { AppNavHost(startDestination = start) }
                    }
                    if (!systemReady) {
                        NativeOfflineOverlay(
                            ownership = systemOwnership,
                            isStarting = isStartingSystem,
                            onStartSystem = { startRootSystemAndPoll() }
                        )
                    }
                }
            }
        }

        ensurePermissions()
        comprobarServidorAlArrancar()
    }

    private var lastBootError: String? by mutableStateOf<String?>(null)

    @Composable
    private fun NativeOfflineOverlay(ownership: String, isStarting: Boolean, onStartSystem: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("◉", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("OpenCode no responde", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "MEDIDO 2026-10-02: este texto decía \"Hub 8765 no responde\" y daba un botón para\n" +
                    "levantar el Hub con ROOT. El Hub ya NO EXISTE (decisión del usuario, 2026-10-01),\n" +
                    "así que el mensaje describía un servicio retirado y el botón no tenía a qué levantar.\n" +
                    "Ahora se pregunta a OpenCode directamente, en 127.0.0.1:49374 con HTTP Basic.\n" +
                    "\n" +
                    "Detalle del sondeo: $ownership",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (isStarting) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Comprobando OpenCode en 127.0.0.1:49374...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onStartSystem, modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
                }
                lastBootError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text("Error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "MEDIDO 2026-10-02: aquí decía \"Ejecuta su -c 'sh backend/keepalive.sh'\". " +
                    "Ese script se eliminó con el Hub, así que la instrucción era un camino a un " +
                    "fichero que ya no está. MEDIDO también: la entrada de arranque en " +
                    "/data/adb/service.d/ NO estaba instalada, así que hoy tampoco se arrancaba solo. " +
                    "Tras un reinicio, OpenCode hay que lanzarlo a mano o desde Termux.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private suspend fun checkSystemReady(): Pair<Boolean,String> = withContext(Dispatchers.IO) {
        try {
            // MEDIDO 2026-10-01: esto consultaba el HUB en :8765, que ya NO EXISTE (se elimino
            // por decision del usuario y nada lo levanta). Con el Hub caido, esta funcion
            // devolvia SIEMPRE false, luego el overlay "Sistema desconectado" salia en cada
            // arranque — y decia "Hub 8765 no responde", que es verdad pero sobre algo que ya
            // no deberia existir. Era un fallo ACTIVO: la app nunca entraba por su ruta normal.
            //
            // Ahora se pregunta a quien de verdad manda: OpenCode, en :49374, con HTTP Basic.
            // MEDIDO: sin cabecera responde 401, y con la contraseña buena 200. Un 401 aqui NO
            // es "caido": es "vivo y exigiendo autenticacion", que es el estado que importa.
            val resp = try {
                okhttp3.Request.Builder()
                    .url("http://127.0.0.1:49374/api/info")
                    .header("Authorization", com.aegis.hub.data.Credentials.default.getBasicAuthHeaderBlocking())
                    .build()
                    .let { okhttp3.OkHttpClient().newCall(it).execute() }
            } catch (e: Exception) {
                // Sin respuesta NINGUNA: no hay nadie escuchando.
                android.util.Log.w(TAG, "checkSystemReady sin respuesta: ${e.message}")
                return@withContext Pair(false, "sin-respuesta")
            }
            resp.use {
                val code = it.code
                when {
                    // MEDIDO: 401 significa que el servidor EXISTE y pide Basic. No es un fallo.
                    code == 401 || code == 403 -> {
                        android.util.Log.i(TAG, "OpenCode vivo (code=$code, pide auth)")
                        return@withContext Pair(true, "opencode-auth")
                    }
                    code == 200 -> return@withContext Pair(true, "opencode-ok")
                    else -> {
                        android.util.Log.w(TAG, "OpenCode code=$code")
                        return@withContext Pair(false, "http:$code")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "checkSystemReady fail: ${e.message}")
            return@withContext Pair(false, "error:${e.message?.take(60)}")
        }
    }

    private suspend fun isOpenCodeReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            // MEDIDO 2026-10-01: lo que decide es el codigo, no el texto. 200 es OK y
            // 401/403 tambien, porque significa "vivo y pidiendo Basic".
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:49374/api/info")
                .header("Authorization", com.aegis.hub.data.Credentials.default.getBasicAuthHeaderBlocking())
                .build()
            okhttp3.OkHttpClient().newCall(req).execute().use { r ->
                val c = r.code
                c == 200 || c == 401 || c == 403
            }
        } catch (_: Exception) { false }
    }


    // F6: la orquestacion (lanzar + sondear con backoff) vive en
    // `ServidorOpenCode` (un solo vuelo idempotente); aqui solo se observa el
    // estado y se pinta. El nombre se conserva para acotar el diff.
    private fun startRootSystemAndPoll() {
        if (isStartingSystem) return
        isStartingSystem = true
        lifecycleScope.launch(Dispatchers.IO) {
            val final = com.aegis.hub.data.ServidorOpenCode.asegurar(
                comprobarSiVivo = { isOpenCodeReady() },
                lanzar = { com.aegis.hub.data.OpenCodeLauncher.lanzarViaScript() }
            )
            android.util.Log.i(TAG, "ServidorOpenCode: $final")
            val ready = final is com.aegis.hub.data.EstadoServidor.Listo
            withContext(Dispatchers.Main) {
                isStartingSystem = false
                if (ready) {
                    // F2 (ambigüedad #7): hub recién levantado desde el overlay → una única
                    // consulta de bootstrap; phase != "done" → initialRoute = SETUP (wizard).
                    // Timeout/error → false: se conserva la ruta ya decidida (DRAFT_CHAT).
                    if (withTimeoutOrNull(2500L) { isBootstrapPending() } == true) {
                        initialRoute = NavRoutes.SETUP
                    }
                    systemReady = true
                    systemOwnership = "ready"
                    lastBootError = null
                    toast("OpenCode responde")
                } else {
                    // F6: el motivo viene de ServidorOpenCode (del script o del timeout).
                    val reason = (final as? com.aegis.hub.data.EstadoServidor.Error)?.motivo
                        ?: "OpenCode no responde en 127.0.0.1:49374. Arranca 'opencode serve --service' (Termux) o reinicia el servicio registrado."
                    lastBootError = reason
                    android.util.Log.e(TAG, "timeout 3min sin respuesta: $reason")
                    toast("OpenCode no responde")
                }
            }
        }
    }

    private fun comprobarServidorAlArrancar() {
        lifecycleScope.launch {
            val result = withTimeoutOrNull(3000L) { checkSystemReady() }
            val (ready, info) = result ?: Pair(false, "timeout:3s")
            systemOwnership = info
            if (ready) {
                // F1: hub arriba → consulta /api/bootstrap/state; sólo una respuesta 200 con
                // phase != "done" redirige al wizard. Timeout/error/ruta ausente → ruta actual.
                val pending = withTimeoutOrNull(2500L) { isBootstrapPending() } ?: false
                initialRoute = if (pending) NavRoutes.SETUP else NavRoutes.DRAFT_CHAT
                systemReady = true
                android.util.Log.i(TAG, "comprobarServidorAlArrancar ready=true ($info) — Compose ready setupPending=$pending")
            } else {
                // MEDIDO 2026-10-02: aquí solo se COMPROBABA. El lanzador existía, pero su único
                // llamador era el botón "Reintentar" del overlay, así que al abrir la app tras un
                // reinicio no se lanzaba nada: se esperaban 3 s, se pintaba el overlay y el usuario
                // tenía que pulsar. Y `startRootSystemAndPoll` es idempotente —primero pregunta si
                // ya responde— así que llamarla aquí no hace daño cuando todo va bien.
                //
                // MEDIDO del arranque real: la cadena de procesos tras un reinicio es
                //     init -> magiskd -> /bin/bash --login -> opencode -> opencode
                // o sea que el script de Magisk SÍ arranca el chroot y el servidor. Lo que no da
                // tiempo es a OpenCode para abrir su base de datos de 2 GB.
                initialRoute = NavRoutes.DRAFT_CHAT
                systemReady = false
                if (result == null) android.util.Log.w(TAG, "comprobarServidorAlArrancar timed out after 3s")
                android.util.Log.i(TAG, "comprobarServidorAlArrancar ready=false ($info) — se intenta arrancar")
                startRootSystemAndPoll()
            }
        }
    }

    /**
     * F1: true sólo si el Hub contesta 200 en /api/bootstrap/state con phase != "done".
     * Cualquier otra cosa (404, 403, 5xx, JSON ilegible, timeout) → false, para no bloquear.
     *
     * MEDIDO 2026-10-02: esto consultaba el Hub en :8765 y el Hub ya no existe, luego devolvía
     * SIEMPRE false. Consecuencia medida: la app **nunca** redirigía al instalador, y el
     * `SetupNativeViewModel` que se escribió para sustituirlo no se alcanzaba nunca por esta vía.
     *
     * Ahora lee el estado del instalador de la propia app, que es donde vive el dato: el store
     * atómico que escribió el Paquete G. Sin fichero de estado → `false`, es decir, no se
     * bloquea el arranque, que es justo lo que pedía el contrato original de esta función.
     */
    private suspend fun isBootstrapPending(): Boolean = withContext(Dispatchers.IO) {
        try {
            // MEDIDO 2026-10-02: `stateFile` sigue siendo privado, asi que se usa el constructor
            // por defecto y se pregunta por el fichero a mano. Lo que cambia es DONDE esta: ya no
            // es una ruta del arbol de compilacion, sino el directorio privado de la app. Con la
            // ruta vieja, un movil sin el repo devolvia `false` por fichero inexistente y el
            // usuario entraba al instalador de cero.
            val f = com.aegis.hub.data.AppPaths.estado(com.aegis.hub.data.BootstrapNative.NOMBRE_STATE)
            if (!f.exists()) return@withContext false
            val phase = com.aegis.hub.data.BootstrapNative()
                .readState().phaseOrIdle
            val pending = phase != com.aegis.hub.data.BootstrapPhase.done
            android.util.Log.i(TAG, "isBootstrapPending phase=$phase pending=$pending")
            pending
        } catch (e: Exception) {
            android.util.Log.w(TAG, "isBootstrapPending fail: ${e.message}")
            false
        }
    }

    /**
     * Registra, una sola vez, el rastreador de Activities visibles.
     *
     * `ActivityLifecycleCallbacks` es la via canonica de Android para "hay alguna pantalla
     * a la vista" y no anade ninguna dependencia. El registro se cachea en el companion
     * porque abrir dos Activities sin cache lo registraria dos veces: entonces el
     * `onStart` de la segunda sumaria 2 con la primera ya parada, y el contador miente.
     */
    private fun registerVisibleTracker() {
        if (visibleTrackerRegistered) return
        visibleTrackerRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                com.aegis.hub.ui.TurnNotifier.onActivityStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                com.aegis.hub.ui.TurnNotifier.onActivityStopped()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    // ---- minimal retained helpers from previous WebView version (wake word gating, TTS, etc.) ----
    companion object {
        /** MEDIDO 2026-10-03: 14 literales sueltos decian lo mismo. Uno solo. */
        private const val TAG = "OpenCodeBoot"
        /** El rastreador se registra UNA vez; dos registros harian mentir al contador. */
        @Volatile
        private var visibleTrackerRegistered = false


        // true solo mientras la Activity está visible. Lo consulta el ViewModel para
        // decidir entre avisar DENTRO del chat (primer plano) o lanzar notificación
        // a la barra (segundo plano). @Volatile porque lo escribe el hilo del UI y lo
        // lee el hilo de un coroutine del ViewModel.
        // Ancla en un val delegando. El comentario de arriba explica por que un contador
        // alimentado por ActivityLifecycleCallbacks es mas fiable que un booleano escrito
        // a mano: un onStop que no llegaba dejaba el aviso de fin de turno muerto para
        // siempre, y en silencio.
        val isForeground: Boolean
            get() = com.aegis.hub.ui.TurnNotifier.isAppVisible
    }

    // onStart/onStop, y NO onResume/onPause: `isForeground` decide entre el divisor
    // DENTRO del chat y una notificacion a la barra, asi que la pregunta correcta es
    // "el usuario puede ver la Activity?", y esa es onStart/onStop. Con onPause, un
    // dialogo, la sombra de notificaciones o simplemente apagar la pantalla ponian la
    // bandera a false mientras el chat seguia a la vista: el usuario estaba viendo el
    // turno terminar y recibia ademas una notificacion de lo que estaba viendo.
    override fun onStart() {
        super.onStart()
        // F5: la conexion SSE vive mientras la app esta en primer plano.
        com.aegis.hub.data.sync.EventosServidor.Compartida.servidor.iniciar()
    }

    override fun onStop() {
        com.aegis.hub.data.sync.EventosServidor.Compartida.servidor.detener()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // El usuario acaba de volver de Ajustes → "Acceso a todos los archivos". Si ya
        // está concedido, Credentials relee service.json sin root en la siguiente lectura
        // (60 s de caché como mucho). Avisamos solo en la transición.
        if (hasAllFilesAccess() && !allFilesWarned) {
            allFilesWarned = true
            toast("Acceso al almacenamiento concedido: ya no hace falta root")
        }
    }


    private fun ensurePermissions(){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            reqMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED){
            reqContacts.launch(Manifest.permission.READ_CONTACTS)
        }
        if(Build.VERSION.SDK_INT >= 33){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED){
                reqNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            reqCamera.launch(Manifest.permission.CAMERA)
        }
        // El almacenamiento es el permiso que de verdad importa: sin él la app no puede
        // leer el token del Hub y cae a `su -c cat` en cada petición.
        ensureAllFilesAccess()
    }

    /** ¿La app puede leer/escribir libremente en almacenamiento compartido? */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Pide "Acceso a todos los archivos" (MANAGE_EXTERNAL_STORAGE).
     *
     * A diferencia de los demás, este permiso NO se concede con un diálogo normal:
     * Android obliga a sending al usuario a Ajustes. Por eso se abre la pantalla
     * concreta de nuestra app con ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, y no
     * un intent genérico, que dejaría al usuario perdido en un listado.
     *
     * Si el usuario lo deniega, la app NO se rompe: las lecturas de ficheros caen a su
     * respaldo con root (RootShell). Solo se pierde la comodidad de no pedir root.
     */
    private fun ensureAllFilesAccess() {
        if (hasAllFilesAccess()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            allFilesWarned = true   // ya avisamos de lo que hace falta; no repetir en onResume
        } catch (e: Exception) {
            // Algunas ROMs (y las pruebas instrumentadas) no tienen esa pantalla.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                // Sin acceso a esa pantalla: se sigue con el respaldo por root.
            }
        }
    }

    private fun startListening(){ /* Phase 1: native STT handled via Compose voice FAB later */ }
    private fun toast(m:String)= Toast.makeText(this,m,Toast.LENGTH_SHORT).show()
    // MEDIDO 2026-10-01: antes apagaba aqui el motor de voz y el reconocedor de esta Activity,
    // y paraba el listener de la frase de activacion. Los tres se han ido con el modo
    // duplex: `speak()` era el unico consumidor del motor y el listener no tenia ningun otro
    // disparador. Sin ellos, onDestroy no tiene nada que liberar. La transcripcion vive en
    // ChatScreen, que gestiona su propio ciclo de vida.
    override fun onDestroy() { super.onDestroy() }
    private fun String.lowercase():String = this.lowercase(Locale.ROOT)
}
