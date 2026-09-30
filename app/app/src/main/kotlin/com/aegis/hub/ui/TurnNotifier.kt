package com.aegis.hub.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aegis.hub.MainActivity
import com.aegis.hub.R

/**
 * Aviso de "la IA terminó de responder".
 *
 * Solo se lanza cuando la app está en SEGUNDO PLANO. En primer plano el aviso va
 * dentro del chat (divisor de respuesta final), porque una notificación con la app
 * abierta delante solo molesta.
 */
object TurnNotifier {

    private const val CHANNEL_ID = "aegis_turns"
    private const val CHANNEL_NAME = "Respuestas de Aegis"
    private const val NOTIF_ID = 4201

    // El ViewModel no tiene Context (es un ViewModel pelado, no un AndroidViewModel), así
    // que MainActivity lo inyecta una vez con el applicationContext. Guardar el
    // applicationContext y no la Activity evita retenerla y filtrarla al rotar.
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private const val TAG = "AegisNotif"

    /**
     * Quantas Activities hay ahora mismo a la vista.
     *
     * Antes era un `@Volatile var isForeground: Boolean` que MainActivity ponia a true en
     * `onStart` y a false en `onStop`. Un booleano escrito a mano tiene un modo de fallo
     * pegajoso: si un `onStop` no llega, el aviso queda muerto PARA SIEMPRE y en
     * silencio, hasta que el proceso muera. Con un contador no: Android siempre llama a
     * los dos callbacks, y si alguna vez se descuadra el numero se ve en el log en vez de
     * manifestarse como "no llega ninguna notificacion" sin explicacion.
     *
     * El numero se imprime en CADA decision de aviso, que es lo que hace falta para
     * diagnosticar: un `0` donde deberia haber un `1` explica el fallo entero.
     */
    private val startedActivities = AtomicInteger(0)

    fun onActivityStarted() {
        val n = startedActivities.incrementAndGet()
        Log.i(TAG, "Activity visible -> $n a la vista")
    }

    fun onActivityStopped() {
        val n = startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        Log.i(TAG, "Activity ya no visible -> $n a la vista")
    }

    /** Hay alguna Activity a la vista? Un contador negativo se satura a 0, no se cuela. */
    val isAppVisible: Boolean get() = startedActivities.get() > 0

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT: visible en la barra pero sin sonido, para no interrumpir.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * @param preview texto corto de la respuesta, para que se vea de un vistazo sin abrir.
     * @param sessionId sesión a la que al volver; null si no se conoce.
     */
    fun notifyTurnFinished(title: String, preview: String, sessionId: String?) {
        // CADA puerta dice cual era. Antes eran tres `return` mudos, asi que un aviso que
        // no llegaba no se distinguia de uno que llegaba: habia que adivinar. Con el log,
        // una linea dice "no se avisa: hay 1 Activity a la vista" y el fallo queda
        // explicado sin instrumentar nada mas.
        val visibles = startedActivities.get()
        if (visibles > 0) {
            Log.i(TAG, "NO se avisa: $visibles Activity a la vista (en primer plano manda el divisor del chat)")
            return
        }
        val ctx = appCtx
        if (ctx == null) {
            Log.w(TAG, "NO se avisa: MainActivity todavia no ha inyectado el applicationContext")
            return
        }
        ensureChannel(ctx)

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra("sessionId", sessionId)
        }
        // FLAG_IMMUTABLE es obligatorio desde Android 12 (S) para PendingIntent.
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = preview.trim().let { if (it.isEmpty()) "La respuesta ha terminado" else it }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        // En Android 13+ hace falta el permiso runtime POST_NOTIFICATIONS; si el usuario
        // no lo concedio, notify() lanza SecurityException. Se come a proposito: la
        // notificacion es un extra y nunca debe tumbar el refresco del chat.
        //
        // MEDIDO 2026-09-30 en este dispositivo: `granted=true`, y el canal `aegis_turns`
        // existe con importance=3 y SIN `blocked`. O sea que el permiso y el canal estan
        // bien, y sin este log un `SecurityException` seria indistinguible de un
        // `notify()` que si se lanzo y el sistema no ense~no. Antes no decia NADA.
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
            Log.i(TAG, "aviso POSTEADO: id=$NOTIF_ID  «$title»  ${notif.extras.getCharSequence("android.text") ?: ""}".take(160))
        } catch (e: SecurityException) {
            Log.w(TAG, "NO se avisa: sin permiso POST_NOTIFICATIONS ($e). El aviso dentro del chat sigue funcionando")
        }
    }
}
