package com.aegis.hub.data.sync

/**
 * Estado del turno de un chat, alimentado por eventos del servidor (F5).
 *
 * La unica senal de "termino de verdad" es `session.execution.succeeded`
 * (ADR-003). El fin de un SEGMENTO de texto (`session.text.ended`) o de un paso
 * NO termina el turno: tras un `bash` con exit 0 el agente sigue trabajando, y
 * pintar ahi el divisor fue el bug del "final del final" (H-10).
 *
 * Tipos `session.execution.*`: ADR-003. Tipos de actividad (`text.*`,
 * `reasoning.*`, `step.*`, `tool.*`): captura real `test/resources/eventos/`
 * (ventana 2026-10-07). Lo no listado se ignora a proposito.
 */
sealed interface EstadoTurno {
    data object Ocioso : EstadoTurno
    data object Ocupado : EstadoTurno
    data class Terminado(val seq: Long?) : EstadoTurno
}

private val TIPOS_OCUPADO = setOf(
    "session.execution.started",
    "session.text.started",
    "session.text.delta",
    "session.reasoning.started",
    "session.reasoning.delta",
    "session.step.started",
    "session.step.streamed",
    "session.tool.called",
    "session.tool.input.started",
    "session.tool.progress"
)

/** Transicion pura: mismo evento + mismo estado = mismo resultado (testeable). */
fun avanzarTurno(actual: EstadoTurno, tipo: String?, seq: Long?): EstadoTurno = when {
    tipo == "session.execution.succeeded" -> EstadoTurno.Terminado(seq)
    tipo in TIPOS_OCUPADO -> EstadoTurno.Ocupado
    else -> actual
}
