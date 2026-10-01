package com.aegis.hub.ui

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cola de sintesis de voz. UN solo motor para las dos pantallas que hablan.
 *
 * MEDIDO 2026-10-01, y por que esto existe: el codigo anterior, DUPLICADO en ChatScreen y en
 * VoiceConversationScreen, hacia tres cosas mal a la vez.
 *
 * 1) `QUEUE_FLUSH`. Flush CANCELA lo que este sonando. En una respuesta de varias frases, la
 *    siguiente cortaba a la anterior a media palabra. Se escuchaban fragmentos sueltos.
 *
 * 2) `setSpeaking(false)` en la linea siguiente a `speak()`. `speak()` es asincrono: vuelve
 *    antes de que el motor pronuncie nada. O sea que la UI daba por terminada una locucion
 *    que aun no habia empezado. Consecuencia mas grave que la visible: `speaking` es lo que
 *    la logica DUPLEX consulta para decidir cuando reabrir el microfono
 *    (`if (duplex && !listening && !speaking) startListeningInternal(...)`), asi que con
 *    `speaking` siempre a false el micro se abria MIENTRAS la app hablaba. Se oia a si misma.
 *
 * 3) La cola nunca se vaciaba. Se encolaban N frases y se hablaba UNA (`queue.first()`), con
 *    `speaking` ya a false el `if (!speaking)` de la siguiente llamada era siempre cierto, de
 *    modo que cada mensaje nuevo hablaba el primer elemento de la cola —el sobrante del
 *    mensaje ANTERIOR— y dejaba el resto. De una respuesta de tres frases solo se oia la
 *    primera, y las otras dos se oian en el mensaje siguiente, en orden equivocado.
 *
 * Ahora manda el motor: un contador de locuciones EN VOLO que solo baja cuando el propio
 * sintetizador avisa de que termino. Si el motor nunca avisa, `speaking` se queda a true, que
 * es la lectura honesta: no sabemos si hablo, no inventamos que ya calle.
 */
class TtsQueue(private val onSpeakingChanged: (Boolean) -> Unit) {

    /** Locuciones en vuelo. Lo baja `onDone`/`onError` del sintetizador, no una estimación. */
    private val enVuelo = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    private var motor: TextToSpeech? = null

    /** Se llama UNA vez, al crear el motor. Un motor nuevo empieza con la cuenta a cero. */
    fun attach(tts: TextToSpeech?) {
        motor = tts
        if (tts == null) return
        enVuelo.set(0)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { termina() }
            override fun onError(utteranceId: String?) { termina() }
        })
    }

    /**
     * Encola un texto. Todas sus frases se pasan al motor de golpe con `QUEUE_ADD`: el
     * sintetizador las encadena solo, sin cronometro ni estimacion de duracion.
     *
     * @return cuantas frases han podido encolarse (0 si el motor no estaba listo).
     */
    fun enqueue(texto: String): Int {
        val t = motor ?: return 0
        if (texto.isBlank()) return 0
        val frases = texto.split(REGLA).filter { it.isNotBlank() }
        if (frases.isEmpty()) return 0
        val base = System.nanoTime()
        var hablados = 0
        frases.forEachIndexed { i, frase ->
            try {
                // QUEUE_ADD y NUNCA QUEUE_FLUSH: flush cancela lo que este sonando.
                t.speak(frase, TextToSpeech.QUEUE_ADD, null, "aegis-$base-$i")
                hablados++
            } catch (_: Exception) { /* el motor no lo ha sostenido: la frase se pierde, no el proceso */ }
        }
        if (hablados == 0) return 0
        // El motor no puede terminar antes de esto: los callbacks llegan por binder y se
        // publican al hilo principal, que ahora mismo somos nosotros.
        enVuelo.addAndGet(hablados)
        onSpeakingChanged(true)
        return hablados
    }

    private fun termina() {
        val quedan = enVuelo.decrementAndGet()
        if (quedan <= 0) main.post { onSpeakingChanged(false) }
    }

    private companion object {
        /** Punto de corte de frase: lo mismo que usaba el codigo anterior, aqui una sola vez. */
        val REGLA = Regex("(?<=[.!?¿¡\\n])\\s+")
    }
}
