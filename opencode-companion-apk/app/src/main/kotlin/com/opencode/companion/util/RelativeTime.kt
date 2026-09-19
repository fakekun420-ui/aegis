package com.opencode.companion.util

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Returns "Hace X ..." relative string in Spanish.
 * Input is epoch millis, or ISO-8601 string parsed to epoch millis.
 */
fun relativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    if (diff < 0) return "Ahora"
    val d = Duration.ofMillis(diff)
    val secs = d.seconds
    return when {
        secs < 45 -> "Hace unos segundos"
        secs < 90 -> "Hace un minuto"
        secs < 45 * 60 -> "Hace ${secs / 60} minutos"
        secs < 90 * 60 -> "Hace una hora"
        secs < 22 * 3600 -> "Hace ${secs / 3600} horas"
        secs < 36 * 3600 -> "Hace un día"
        secs < 25 * 86400 -> "Hace ${secs / 86400} días"
        secs < 45 * 86400 -> "Hace un mes"
        secs < 345 * 86400 -> "Hace ${secs / (30 * 86400)} meses"
        secs < 545 * 86400 -> "Hace un año"
        else -> "Hace ${secs / (365 * 86400)} años"
    }
}

fun relativeTime(isoOrMillis: String?): String {
    if (isoOrMillis.isNullOrBlank()) return "—"
    // Try millis as long
    isoOrMillis.toLongOrNull()?.let { return relativeTime(it) }
    // Try ISO-8601
    return try {
        val instant = Instant.parse(isoOrMillis)
        relativeTime(instant.toEpochMilli())
    } catch (_: DateTimeParseException) {
        // Try without Z suffix
        try {
            val instant = Instant.parse(isoOrMillis + "Z")
            relativeTime(instant.toEpochMilli())
        } catch (_: Exception) {
            isoOrMillis.take(16)
        }
    }
}

/** Picks most recent timestamp string from a model: updatedAt/updated_at/createdAt etc. */
fun pickEpochMillis(vararg candidates: String?): Long? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        c.toLongOrNull()?.let { return it }
        try { return Instant.parse(c).toEpochMilli() } catch (_: Exception) {}
        try { return Instant.parse(c + "Z").toEpochMilli() } catch (_: Exception) {}
    }
    return null
}
