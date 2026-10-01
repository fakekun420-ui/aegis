package com.aegis.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano. SOLO mantiene vivo el proceso en segundo plano.
 *
 * Antes eran DOS responsabilidades en una clase: mantener vivo el proceso, y servir un
 * puente HTTP en 127.0.0.1:8766 (`/a11y`, `/shell`, `/launch`, `/dump`, `/status`) que
 * delegaba en `OpencodeAccessibilityService`. Eso eran 251 lineas: 83 de servidor HTTP,
 * 56 de handlers y dos sockets acceptability. Se ha ido la parte de accesibilidad y se
 * queda unicamente la que hace falta.
 *
 * POR QUE EL SERVICIO SIGUE SIENDO OBLIGATORIO, y no es negociable: sin el, Android pasa
 * el proceso a CACHED en cuanto el usuario sale de la app y lo mata. Con el proceso
 * muerto se pierden DOS cosas que el usuario pide explicitamente: la notificacion de fin
 * de turno y el poll que pone el circulo de "trabajando" en la lista de chats.
 *
 * Lo que no queda: ningun socket abierto, ninguna llamada IPC de accesibilidad, y por
 * tanto nada que contour con el gasto de bateria de un servidor escuchando.
 *
 * MEDIDO 2026-09-30: el consumo de un foreground service sin sockets es memoria del heap
 * en reposo, sin trabajo periodico. El poll (3-8 s) lo hace la app contra el Hub, no este
 * servicio.
 */
class CompanionService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // START_STICKY: si Android lo mata por falta de memoria, el sistema lo vuelve a levantar.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundNotif() {
        // Canal "aegis-hub": el id heredado, renombrado (no sigue el patron nombre-clase).
        val chId = "aegis-hub"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "Aegis", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("Aegis activo")
            // El texto ya no anuncia el bridge: ese ya no existe. IMPORTANCE_LOW y sin
            // sonido, para que la notificacion permanente no moleste.
            .setContentText("Notificaciones de respuesta final activas")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }
}
