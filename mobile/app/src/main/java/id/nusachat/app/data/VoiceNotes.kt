package id.nusachat.app.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Perekam pesan suara (AAC/M4A) + pemutar inline untuk pesan audio.
 * File rekaman disimpan di cache lalu diunggah sebagai lampiran audio.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var outputFile: File? = null
        private set
    var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = recorder != null
    val elapsedSeconds: Int get() = if (startedAt == 0L) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (recorder != null) return false
        return runCatching {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            outputFile = File(dir, "vn_${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(outputFile!!.absolutePath)
            rec.setMaxDuration(5 * 60 * 1000)
            rec.prepare()
            rec.start()
            recorder = rec
            startedAt = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }

    /** Berhenti dan kembalikan file, atau null bila gagal. */
    fun stop(): File? {
        val rec = recorder ?: return null
        recorder = null
        startedAt = 0L
        return runCatching {
            rec.stop()
            rec.release()
            outputFile?.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }

    fun cancel() {
        val rec = recorder
        recorder = null
        startedAt = 0L
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    fun outputUri(): Uri? = outputFile?.let {
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
    }
}

/**
 * Pemutar audio satu-sumber untuk bubble pesan suara. Satu instance per
 * layar chat; memutar URL baru otomatis menghentikan yang lama.
 */
class VoicePlayer {
    private var player: MediaPlayer? = null
    var playingUrl: String? = null
        private set
    var onDone: (() -> Unit)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun toggle(url: String, onStarted: () -> Unit = {}) {
        if (playingUrl == url && isPlaying) {
            stop()
            return
        }
        stop()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { it.start(); onStarted() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepareAsync()
            }
            player = mp
            playingUrl = url
        }
    }

    fun stop() {
        val wasPlaying = playingUrl
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingUrl = null
        if (wasPlaying != null) onDone?.invoke()
    }
}
