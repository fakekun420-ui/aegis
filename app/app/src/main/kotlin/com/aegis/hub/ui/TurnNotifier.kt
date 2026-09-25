package com.aegis.hub.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
    @Volatile
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

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
        if (MainActivity.isForeground) return   // en primer plano manda el divisor del chat
        val ctx = appCtx ?: return              // MainActivity aún no ha inicializado
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
        // no lo concedió, notify() lanza SecurityException. Se come a propósito: la
        // notificación es un extra, nunca debe tumbar el refresco del chat.
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // sin permiso: el aviso dentro del chat sigue funcionando
        }
    }
}
