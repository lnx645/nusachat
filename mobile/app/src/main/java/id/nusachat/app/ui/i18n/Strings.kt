package id.nusachat.app.ui.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.core.os.LocaleListCompat

/**
 * String UI bilingual ID/EN. Bahasa default Indonesia; pilihan user tersimpan
 * di SettingsStore dan diterapkan via AppCompatDelegate + recreate activity.
 * Layar yang belum dimigrasi tetap memakai string Indonesia hardcoded.
 */
data class NusaStrings(
    val tabChats: String,
    val tabContacts: String,
    val tabDiscover: String,
    val tabMe: String,
    val settings: String,
    val back: String,
    val sectionAppearance: String,
    val sectionLanguage: String,
    val themeAuto: String,
    val themeLight: String,
    val themeDark: String,
    val fontSize: String,
    val sectionNotifications: String,
    val notifChatWallet: String,
    val notifMoments: String,
    val notifSound: String,
    val notifVibrate: String,
    val sectionDevices: String,
    val sessionsLoading: String,
    val sessionsEmpty: String,
    val deviceUnknown: String,
    val activeSince: String,
    val revoke: String,
    val revokeTitle: String,
    val revokeText: String,
    val sectionStorage: String,
    val appCache: String,
    val calculating: String,
    val clear: String,
    val clearCacheTitle: String,
    val clearCacheText: String,
    val sectionMyReports: String,
    val reportsEmpty: String,
    val sectionServer: String,
    val realtimeStatus: String,
    val connected: String,
    val disconnected: String,
    val cancel: String,
    val close: String,
    val save: String,
)

val IdStrings = NusaStrings(
    tabChats = "Obrolan",
    tabContacts = "Kontak",
    tabDiscover = "Jelajah",
    tabMe = "Saya",
    settings = "Pengaturan",
    back = "Kembali",
    sectionAppearance = "Tampilan",
    sectionLanguage = "Bahasa",
    themeAuto = "Otomatis",
    themeLight = "Terang",
    themeDark = "Gelap",
    fontSize = "Ukuran huruf",
    sectionNotifications = "Notifikasi",
    notifChatWallet = "Notifikasi chat & dompet",
    notifMoments = "Notifikasi momen",
    notifSound = "Suara",
    notifVibrate = "Getar",
    sectionDevices = "Perangkat saya",
    sessionsLoading = "Memuat sesi...",
    sessionsEmpty = "Tidak ada sesi aktif lain.",
    deviceUnknown = "Perangkat tak dikenal",
    activeSince = "Aktif",
    revoke = "Cabut",
    revokeTitle = "Cabut sesi?",
    revokeText = "Perangkat itu harus masuk ulang. Bila ini perangkatmu, kamu ikut keluar.",
    sectionStorage = "Penyimpanan",
    appCache = "Cache aplikasi",
    calculating = "Menghitung...",
    clear = "Bersihkan",
    clearCacheTitle = "Bersihkan cache?",
    clearCacheText = "File sementara dihapus. Chat dan momen tidak hilang.",
    sectionMyReports = "Laporanku",
    reportsEmpty = "Belum ada laporan.",
    sectionServer = "Server",
    realtimeStatus = "Status realtime",
    connected = "Terhubung",
    disconnected = "Terputus",
    cancel = "Batal",
    close = "Tutup",
    save = "Simpan",
)

val EnStrings = NusaStrings(
    tabChats = "Chats",
    tabContacts = "Contacts",
    tabDiscover = "Discover",
    tabMe = "Me",
    settings = "Settings",
    back = "Back",
    sectionAppearance = "Appearance",
    sectionLanguage = "Language",
    themeAuto = "Auto",
    themeLight = "Light",
    themeDark = "Dark",
    fontSize = "Font size",
    sectionNotifications = "Notifications",
    notifChatWallet = "Chat & wallet notifications",
    notifMoments = "Moments notifications",
    notifSound = "Sound",
    notifVibrate = "Vibrate",
    sectionDevices = "My devices",
    sessionsLoading = "Loading sessions...",
    sessionsEmpty = "No other active sessions.",
    deviceUnknown = "Unknown device",
    activeSince = "Active",
    revoke = "Revoke",
    revokeTitle = "Revoke session?",
    revokeText = "That device will have to sign in again. If it is yours, you will be signed out too.",
    sectionStorage = "Storage",
    appCache = "App cache",
    calculating = "Calculating...",
    clear = "Clear",
    clearCacheTitle = "Clear cache?",
    clearCacheText = "Temporary files are deleted. Chats and moments stay.",
    sectionMyReports = "My reports",
    reportsEmpty = "No reports yet.",
    sectionServer = "Server",
    realtimeStatus = "Realtime status",
    connected = "Connected",
    disconnected = "Disconnected",
    cancel = "Cancel",
    close = "Close",
    save = "Save",
)

val LocalStrings: ProvidableCompositionLocal<NusaStrings> = compositionLocalOf { IdStrings }

/** String UI aktif sesuai bahasa pilihan. */
@Composable
fun t(): NusaStrings = LocalStrings.current

fun stringsFor(code: String): NusaStrings = if (code == "en") EnStrings else IdStrings

/** Terapkan locale aplikasi (ID/EN). Panggil sebelum recreate activity. */
fun applyAppLocale(code: String) {
    val tag = if (code == "en") "en" else "id"
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
