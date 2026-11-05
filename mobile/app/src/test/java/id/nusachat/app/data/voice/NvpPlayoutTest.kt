package id.nusachat.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Jadwal tick main penerima NVP/1: konsumen WAJIB pop ~1 frame per 20 ms
 * (kontrak [NvpJitter]: frame ditahan targetDelayMs lalu basi >400 ms).
 * Konsumen yang pop jarang (mis. 1 frame per 200 ms+) membuat antrean
 * basi semua = sunyi total. Tes ini mengunci cadence yang benar.
 */
class NvpPlayoutTest {
    @Test
    fun tickMatchesCodecClock() {
        // 320 sampel @16 kHz = 20 ms per frame.
        assertEquals(
            NvpCodecs.TICK_SAMPLES * 1000L / NvpCodecs.SAMPLE_RATE,
            NvpPlayout.TICK_MS,
        )
    }

    @Test
    fun noFrameBeforeOneTick() {
        assertEquals(0, NvpPlayout.framesDue(0))
        assertEquals(0, NvpPlayout.framesDue(19))
        assertEquals(0, NvpPlayout.framesDue(-5))
    }

    @Test
    fun oneFramePerTick() {
        assertEquals(1, NvpPlayout.framesDue(20))
        assertEquals(1, NvpPlayout.framesDue(39))
        assertEquals(2, NvpPlayout.framesDue(40))
    }

    @Test
    fun catchesUpOneBundledPacket() {
        // Profil ultra: 1 paket 60 ms = 3 frame; 60 ms tanpa tick = 3 frame.
        assertEquals(3, NvpPlayout.framesDue(60))
    }

    @Test
    fun catchUpIsCapped() {
        // Macet 200 ms+ bukan alasan sembur 10 frame basi sekaligus.
        assertEquals(NvpPlayout.MAX_CATCH_UP, NvpPlayout.framesDue(200))
        assertEquals(NvpPlayout.MAX_CATCH_UP, NvpPlayout.framesDue(10_000))
    }
}
