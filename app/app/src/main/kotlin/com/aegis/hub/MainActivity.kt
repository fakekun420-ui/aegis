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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.aegis.hub.ui.AppNavHost
import com.aegis.hub.ui.NavRoutes
import com.aegis.hub.data.AppContext

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
        // Los ViewModel necesitan leer preferencias (p. ej. el modelo por sesión) y no
        // llevan Context en el constructor a propósito. Se les da el de aplicación aquí.
        AppContext.init(applicationContext)
        // El ViewModel no tiene Context; se lo damos una vez para el aviso de
        // "respuesta final" en la barra de notificaciones.
        com.aegis.hub.ui.TurnNotifier.init(this)
        // La senal de "hay algo a la vista" la da Android, no un booleano. Se registra UNA
        // vez (el companion object lo cachea). Sin esto, `isAppVisible` se queda en 0 para
        // siempre y el aviso de fin de turno no sale nunca: el fallo seria silencioso.
        registerVisibleTracker()
        // MEDIDO 2026-09-30: sin esto el watchdog solo existia si el usuario pulsaba el
        // boton de la pantalla de arranque (el unico llamador de startRootSystemAndPoll).
        // Con el Hub arriba —el caso normal— ese boton ni siquiera aparece, asi que el Hub
        // se quedaba sin vigilante sin que nada lo indicara. Se asegura en cada arranque;
        // keepalive.sh se protege solo con su lock, asi que repetir el lanzamiento no
        // duplica nada (medido: con el lock apuntando a un pid MUERTO, el guard deja pasar).
        ensureWatchdog()
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
                    // F1: sólo se monta cuando checkHubOnStart ha decidido la ruta inicial
                    // (SETUP si el bootstrap no terminó; DRAFT_CHAT = comportamiento actual).
                    // F2 (ambigüedad #7): key(start) → si "Iniciar Sistema" levanta el hub con
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
        startCompanionService()
        checkHubOnStart()
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
                Text("Sistema desconectado", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hub 8765 no responde ($ownership). Pulsa Iniciar Sistema para levantar Ubuntu y OpenCode con ROOT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (isStarting) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Iniciando servicios...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onStartSystem, modifier = Modifier.fillMaxWidth()) { Text("Iniciar Sistema") }
                }
                lastBootError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text("Error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ejecuta su -c 'sh /sdcard/projects/Aegis/backend/keepalive.sh'",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private suspend fun checkSystemReady(): Pair<Boolean,String> = withContext(Dispatchers.IO) {
        try {
            // A-3: /api/system/status exige X-Aegis-Token desde A-1 — sin él respondía 403
            // y el overlay "Sistema desconectado" salía SIEMPRE. TokenProvider reintenta 1 vez.
            val resp = com.aegis.hub.data.TokenProvider.request(
                "http://127.0.0.1:8765/api/system/status", "GET",
                connectTimeoutMs = 3000, readTimeoutMs = 3000
            )
            if (resp.code != 200) return@withContext Pair(false, "http:${resp.code}")
            val body = resp.body
            val ready = body.contains("\"ready\":true")
            val ownership = when {
                body.contains("\"sessionOwnership\":\"termux-native\"") -> "termux-native"
                body.contains("\"sessionOwnership\":\"companion-owned\"") -> "companion-owned"
                body.contains("\"sessionOwnership\":\"none\"") -> "none"
                else -> "unknown"
            }
            android.util.Log.i("OpenCodeBoot", "checkSystemReady ready=$ready ownership=$ownership code=200")
            return@withContext Pair(ready, ownership)
        } catch (e: Exception) {
            android.util.Log.w("OpenCodeBoot", "checkSystemReady fail: ${e.message}")
            return@withContext Pair(false, "error:${e.message?.take(60)}")
        }
    }

    private suspend fun isHubReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            // A-3: con token (403 sin él) — reintento único incluido en TokenProvider
            com.aegis.hub.data.TokenProvider.request(
                "http://127.0.0.1:8765/api/system/status", "GET",
                connectTimeoutMs = 1500, readTimeoutMs = 1500
            ).code == 200
        } catch (_: Exception) { false }
    }

    /**
     * Lanza keepalive.sh dentro del chroot de Ubuntu y devuelve el codigo de salida.
     * SIN toast, SIN sondeo de 45 s: esto es "asegura que exista el watchdog", no "levanta
     * el Hub y avisa". De ahi las dos funciones que la usan:
     *   - [startRootSystemAndPoll], el boton del overlay (ademas espera y avisa), y
     *   - [ensureWatchdog], el arranque automatico de la app (silencioso).
     *
     * Idempotente por construccion: keepalive.sh se protege solo con su lock y su
     * `kill -0 $OLDPID`, asi que llamarla con el daemon ya vivo sale con "ya corre" y no
     * duplica nada. Lo que decide es el propio daemon.
     */
    private suspend fun launchKeepalive(): Int {
        return try {
            val script = "/sdcard/projects/Aegis/backend/keepalive.sh"
            val sysLog = "/sdcard/projects/Aegis/backend/hub-startup.log"
            // chroot anchor: ubuntu init pid changes across reboots; find it by its
            // unique root marker (/proc/PID/root/lib/ld-linux-aarch64.so.1 = ubuntu
            // chroot with node+loader). Launch keepalive INSIDE the chroot so node,
            // loader, server.js and ports all resolve in one namespace. No nsenter.
            val stageResult = RootShell.exec("sh /sdcard/projects/Aegis/backend/find-ubuntu.sh")
            android.util.Log.i("OpenCodeBoot", "find-ubuntu exit=${stageResult.code} out=${stageResult.stdout} err=${stageResult.stderr}")
            val ubuntuPid = stageResult.stdout.trim().lines().firstOrNull { it.isNotBlank() }?.trim()
            android.util.Log.i("OpenCodeBoot", "keepalive ubuntuPid=$ubuntuPid")
            if (ubuntuPid.isNullOrBlank()) {
                android.util.Log.e("OpenCodeBoot", "keepalive: no ubuntu chroot anchor found")
                97
            } else {
                val directCmd = "chroot /proc/$ubuntuPid/root /bin/sh -c '/usr/bin/nohup /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh \"$script\" >> \"$sysLog\" 2>&1 & echo launched'"
                val result = RootShell.exec(directCmd)
                android.util.Log.i("OpenCodeBoot", "keepalive exec exit=${result.code} out=${result.stdout.take(120)} err=${result.stderr.take(300)}")
                if (!result.stdout.contains("launched")) 98 else result.code
            }
        } catch (e: Exception) {
            android.util.Log.e("OpenCodeBoot", "keepalive exec exception", e)
            -1
        }
    }

    /**
     * MEDIDO 2026-09-30, y este es el arreglo de un fallo real: el watchdog NO se lanzaba
     * salvo que el usuario pulsara el boton de la pantalla de arranque.
     *
     * El unico llamador de [startRootSystemAndPoll] estaba dentro de
     * `if (!systemReady) { NativeOfflineOverlay(onStartSystem = ...) }`. Con el Hub ARRIBA —
     * el caso normal, y el unico en el que importa— el overlay no aparece y el watchdog no
     * se lanza nunca. El resultado medido: el Hub se queda sin quien lo vigile justo cuando
     * todo va bien, que es cuando nadie se entera.
     *
     * Se lanza en cada arranque, y no solo si el Hub esta caido, porque la ventana del
     * fallo era precisamente esa: Hub arriba + watchdog ausente.
     */
    private fun ensureWatchdog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val code = launchKeepalive()
            android.util.Log.i("OpenCodeBoot", "watchdog asegurado al arrancar (exit=$code)")
        }
    }

    private fun startRootSystemAndPoll() {
        if (isStartingSystem) return
        isStartingSystem = true
        lifecycleScope.launch(Dispatchers.IO) {
            val execExit = launchKeepalive()
            var attempts = 0
            var ready = false
            val maxAttempts = 90
            while (attempts < maxAttempts && !ready) {
                delay(500)
                ready = isHubReady()
                attempts++
            }
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
                    toast("Hub levantado")
                } else {
                    val reason = when {
                        execExit == 99 -> "no se encontró namespace host con /usr/bin/node"
                        execExit != 0 -> "keepalive exit=$execExit"
                        else -> "hub sin 200 tras 45s"
                    }
                    lastBootError = reason
                    android.util.Log.e("OpenCodeBoot", "timeout 45s sin 200 ($reason)")
                    toast("Timeout 45s: $reason")
                }
            }
        }
    }

    private fun checkHubOnStart() {
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
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=true ($info) — Compose ready setupPending=$pending")
            } else {
                // Sin hub: ruta actual intacta (no bloquear) — el overlay gestiona la recuperación
                initialRoute = NavRoutes.DRAFT_CHAT
                systemReady = false
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=false ($info) — overlay")
                if (result == null) android.util.Log.w("OpenCodeBoot", "checkHubOnStart timed out after 3s")
            }
        }
    }

    /**
     * F1: true sólo si el hub contesta 200 en /api/bootstrap/state con phase != "done".
     * Cualquier otra cosa (404 ruta ausente, 403, 5xx, JSON ilegible, timeout) → false,
     * para no bloquear el arranque actual.
     */
    private suspend fun isBootstrapPending(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Mismo patrón que checkSystemReady/isHubReady: TokenProvider adjunta
            // X-Aegis-Token y reintenta una vez ante 403.
            val resp = com.aegis.hub.data.TokenProvider.request(
                "http://127.0.0.1:8765/api/bootstrap/state", "GET",
                connectTimeoutMs = 1500, readTimeoutMs = 1500
            )
            if (resp.code != 200) return@withContext false
            val phase = org.json.JSONObject(resp.body)
                .optJSONObject("data")
                ?.optString("phase", "")
                ?: ""
            phase.isNotEmpty() && phase != "done"
        } catch (e: Exception) {
            android.util.Log.w("OpenCodeBoot", "isBootstrapPending fail: ${e.message}")
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
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // El usuario acaba de volver de Ajustes → "Acceso a todos los archivos". Si ya
        // está concedido, TokenProvider dejó de necesitar root en la siguiente lectura
        // del token (60 s de caché como mucho). Avisamos solo en la transición.
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
     * Si el usuario lo deniega, la app NO se rompe: TokenProvider cae a su respaldo con
     * root (ver data/TokenProvider.kt → fetchToken). Solo se pierde la comodidad de no
     * pedir root.
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

    private fun startCompanionService(){
        val i = Intent(this, CompanionService::class.java)
        if(Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
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
