package id.nusachat.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferensi aplikasi: tema, ukuran font, notifikasi, draf chat, dan
 * penanda riwayat panggilan. SharedPreferences privat, tanpa server.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _fontScale = MutableStateFlow(prefs.getFloat(KEY_FONT, 1f).coerceIn(0.85f, 1.3f))
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private val _notifMessages = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_MSG, true))
    val notifMessages: StateFlow<Boolean> = _notifMessages.asStateFlow()

    private val _notifMoments = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_MOMENT, true))
    val notifMoments: StateFlow<Boolean> = _notifMoments.asStateFlow()

    private val _notifSound = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_SOUND, true))
    val notifSound: StateFlow<Boolean> = _notifSound.asStateFlow()

    private val _notifVibrate = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_VIBRATE, true))
    val notifVibrate: StateFlow<Boolean> = _notifVibrate.asStateFlow()

    private val _language = MutableStateFlow(prefs.getString(KEY_LANG, LANG_ID) ?: LANG_ID)
    val language: StateFlow<String> = _language.asStateFlow()

    fun setThemeMode(mode: String) {
        val value = if (mode in listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)) mode else THEME_SYSTEM
        prefs.edit().putString(KEY_THEME, value).apply()
        _themeMode.value = value
    }

    fun setFontScale(scale: Float) {
        val value = scale.coerceIn(0.85f, 1.3f)
        prefs.edit().putFloat(KEY_FONT, value).apply()
        _fontScale.value = value
    }

    fun setNotifMessages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_MSG, enabled).apply()
        _notifMessages.value = enabled
    }

    fun setNotifMoments(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_MOMENT, enabled).apply()
        _notifMoments.value = enabled
    }

    fun setNotifSound(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_SOUND, enabled).apply()
        _notifSound.value = enabled
    }

    fun setNotifVibrate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_VIBRATE, enabled).apply()
        _notifVibrate.value = enabled
    }

    fun setLanguage(code: String) {
        val value = if (code == LANG_EN) LANG_EN else LANG_ID
        prefs.edit().putString(KEY_LANG, value).apply()
        _language.value = value
    }

    private val _recentEmojis = MutableStateFlow(
        prefs.getString(KEY_RECENT_EMOJI, "").orEmpty()
            .split(EMOJI_SEP).filter { it.isNotEmpty() }.take(MAX_RECENT_EMOJI)
    )
    val recentEmojis: StateFlow<List<String>> = _recentEmojis.asStateFlow()

    /** Emoji terakhir dipakai (panel emoji): paling baru di depan, maks 24. */
    fun pushRecentEmoji(emoji: String) {
        val next = (listOf(emoji) + _recentEmojis.value.filter { it != emoji })
            .take(MAX_RECENT_EMOJI)
        prefs.edit().putString(KEY_RECENT_EMOJI, next.joinToString(EMOJI_SEP)).apply()
        _recentEmojis.value = next
    }

    /** Draf per percakapan: tetap ada walau pindah chat. */
    fun draftFor(conversationId: Long): String =
        prefs.getString(KEY_DRAFT + conversationId, "").orEmpty()

    fun saveDraft(conversationId: Long, text: String) {
        val editor = prefs.edit()
        if (text.isBlank()) editor.remove(KEY_DRAFT + conversationId)
        else editor.putString(KEY_DRAFT + conversationId, text.take(4000))
        editor.apply()
    }

    fun clearDraft(conversationId: Long) {
        prefs.edit().remove(KEY_DRAFT + conversationId).apply()
    }

    /** Id panggilan terakhir yang sudah dilihat (lencana tak terjawab). */
    fun lastSeenCallId(): Long = prefs.getLong(KEY_CALL_SEEN, 0L)

    fun markCallsSeen(upToId: Long) {
        if (upToId > lastSeenCallId()) {
            prefs.edit().putLong(KEY_CALL_SEEN, upToId).apply()
        }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANG_ID = "id"
        const val LANG_EN = "en"

        /** Baca bahasa tersimpan tanpa instance (untuk Application.onCreate). */
        fun savedLanguage(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANG, LANG_ID) ?: LANG_ID
        private const val PREFS = "nusachat.settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "font_scale"
        private const val KEY_NOTIF_MSG = "notif_messages"
        private const val KEY_NOTIF_MOMENT = "notif_moments"
        private const val KEY_NOTIF_SOUND = "notif_sound"
        private const val KEY_NOTIF_VIBRATE = "notif_vibrate"
        private const val KEY_DRAFT = "draft:"
        private const val KEY_CALL_SEEN = "calls_seen_upto"
        private const val KEY_LANG = "language"
        private const val KEY_RECENT_EMOJI = "recent_emoji"
        private const val EMOJI_SEP = "\u001F"
        private const val MAX_RECENT_EMOJI = 24
    }
}
