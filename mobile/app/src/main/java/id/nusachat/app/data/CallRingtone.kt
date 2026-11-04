package id.nusachat.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log

/**
 * Nada dering panggilan masuk ala WhatsApp: berputar selama status
 * "ringing", berhenti saat dijawab/ditolak/diakhiri. Memakai ringtone
 * sistem agar mengikuti pilihan suara pengguna.
 */
object CallRingtone {
    private var player: MediaPlayer? = null

    @Synchronized
    fun start(context: Context) {
        if (player != null) return
        Log.d("NusaCall", "ringtone start")
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return@runCatching
            player = MediaPlayer().apply {
                setDataSource(context.applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (player == null) return
        Log.d("NusaCall", "ringtone stop")
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
