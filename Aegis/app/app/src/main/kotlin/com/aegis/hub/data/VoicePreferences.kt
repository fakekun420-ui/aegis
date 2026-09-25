package com.aegis.hub.data

import android.content.Context
import android.content.Context.MODE_PRIVATE
import org.json.JSONArray
import java.util.Locale

/**
 * Centralized preferences for voice interaction, TTS speech rate,
 * wake word gating, wake phrases, and language.
 * Stored in "voice_prefs" SharedPreferences so CompanionVoiceInteractionService,
 * MainActivity, and Compose UI share the exact same configuration.
 */
object VoicePreferences {
    const val PREFS_NAME = "voice_prefs"

    const val KEY_WAKE_ENABLED = "wake_word_enabled"
    const val KEY_WAKE_PHRASES = "wake_phrases_json"
    const val KEY_SPEECH_RATE = "speech_rate"
    const val KEY_LANGUAGE = "voice_language"

    val DEFAULT_WAKE_PHRASES = arrayOf("viernes escucha", "hola viernes", "viernes atenta")
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val DEFAULT_WAKE_ENABLED = true
    const val DEFAULT_LANGUAGE = "es-ES"

    fun isWakeWordEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return sp.getBoolean(KEY_WAKE_ENABLED, DEFAULT_WAKE_ENABLED)
    }

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAKE_ENABLED, enabled)
            .apply()
    }

    fun getWakePhrases(context: Context): Array<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val raw = sp.getString(KEY_WAKE_PHRASES, null) ?: return DEFAULT_WAKE_PHRASES
        return try {
            val json = JSONArray(raw)
            Array(json.length()) { json.getString(it) }
        } catch (_: Exception) {
            DEFAULT_WAKE_PHRASES
        }
    }

    fun saveWakePhrases(context: Context, phrases: Array<String>) {
        val jsonArray = JSONArray()
        phrases.filter { it.isNotBlank() }.forEach { jsonArray.put(it.trim()) }
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_WAKE_PHRASES, jsonArray.toString())
            .apply()
    }

    fun getSpeechRate(context: Context): Float {
        val sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return sp.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE).coerceIn(0.5f, 2.0f)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SPEECH_RATE, rate.coerceIn(0.5f, 2.0f))
            .apply()
    }

    fun getLanguage(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, lang)
            .apply()
    }

    fun containsWakeWord(context: Context, text: String): Boolean {
        if (!isWakeWordEnabled(context)) return false
        val lower = text.lowercase(Locale.ROOT)
        return getWakePhrases(context).any { phrase ->
            phrase.isNotBlank() && lower.contains(phrase.lowercase(Locale.ROOT))
        }
    }
}
