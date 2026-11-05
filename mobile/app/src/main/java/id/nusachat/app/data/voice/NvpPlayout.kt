package id.nusachat.app.data.voice

/**
 * Jadwal tick main penerima NVP/1.
 *
 * Kontrak [NvpJitter]: frame ditahan ~targetDelayMs (80–400 ms) lalu basi
 * dan dibuang. Kontrak ini hanya terpenuhi bila konsumen pop rutin ~1
 * frame per 20 ms. Konsumen yang pop jarang (mis. 1 frame per 200 ms+,
 * karena loop penerima terblokir di receive) membuat seluruh antrean
 * basi = sunyi total walau paket tiba normal.
 *
 * Murni JVM-testable ([NvpPlayoutTest]).
 */
object NvpPlayout {
    /** Satu frame audio = 20 ms (320 sampel @16 kHz, lihat [NvpCodecs]). */
    const val TICK_MS = 20L

    /**
     * Batas kejar: macet 80 ms (1 paket ultra 60 ms + jeda) = 4 frame.
     * Lebih dari ini berarti diskontinuitas; sisanya biar gugur alami
     * via MAX_LATE_MS, jangan disembur sekaligus.
     */
    const val MAX_CATCH_UP = 4

    /** Berapa frame yang wajib dimainkan tick ini (0 bila belum waktunya). */
    fun framesDue(elapsedMs: Long): Int {
        if (elapsedMs < TICK_MS) return 0
        return (elapsedMs / TICK_MS).toInt().coerceAtMost(MAX_CATCH_UP)
    }
}
