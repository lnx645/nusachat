package id.nusachat.app.data.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tes perangkat nyata NVP/1: codec vendor, mic, kripto, dan UDP HP→server.
 * Bukti risiko terbesar: encoder Opus per-vendor + izin mic + NAT/firewall.
 */
@RunWith(AndroidJUnit4::class)
class NvpDeviceTest {

    @get:Rule
    val grantMic: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun opusEncoderTersedia() {
        val codecs = NvpCodecs.probeEncoders()
        assertTrue("Opus tak ada, dapat: $codecs", Nvp.CODEC_OPUS in codecs)
    }

    @Test
    fun codecRoundtripPreferred() {
        val codec = NvpCodecs.probeEncoders().first()
        val enc = NvpEncoder(codec, NvpCodecs.bitrate(codec, 1))
        try {
            val sine = ShortArray(NvpCodecs.TICK_SAMPLES) { i ->
                (15000 * sin(2 * PI * 440 * i / NvpCodecs.SAMPLE_RATE)).toInt().toShort()
            }
            var blob: ByteArray? = null
            repeat(12) { n ->
                if (blob == null) blob = enc.encode(sine, n * 20_000L)
            }
            assertNotNull("encoder $codec tak keluarkan output", blob)
            assertTrue("blob ${blob!!.size}B aneh", blob!!.size in 1..600)
            // CSD wajib untuk decoder Opus di sebagian vendor (Infinix).
            var csd: ByteArray? = enc.codecConfig
            var guard = 0
            while (csd == null && guard++ < 20) {
                enc.encode(sine, (12 + guard) * 20_000L)
                csd = enc.codecConfig
            }
            if (codec == Nvp.CODEC_OPUS) assertNotNull("encoder tak keluarkan CSD", csd)
            val dec = NvpDecoder(codec, csd)
            try {
                // Decoder boleh priming 1-2 frame → feed hingga 4 blob.
                var pcm: ShortArray? = null
                repeat(4) { n ->
                    if (pcm == null) pcm = dec.decode(blob!!, n * 20_000L)
                }
                assertNotNull("decoder $codec gagal", pcm)
                assertTrue("pcm ${pcm!!.size} sampel", pcm!!.size >= NvpCodecs.TICK_SAMPLES)
            } finally {
                dec.release()
            }
        } finally {
            enc.release()
        }
    }

    @Test
    fun micBisaRekam() {
        val minBuf = AudioRecord.getMinBufferSize(
            NvpCodecs.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("minBuf=$minBuf", minBuf > 0)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, NvpCodecs.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, rec.state)
            rec.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, rec.recordingState)
            val buf = ShortArray(NvpCodecs.TICK_SAMPLES)
            var got = 0
            repeat(10) { if (rec.read(buf, 0, buf.size) > 0) got++ }
            assertTrue("mic tak keluarkan sampel", got > 0)
            rec.stop()
        } finally {
            rec.release()
        }
    }

    @Test
    fun nvpSealOpenDiHp() {
        val pkt = NvpPacket(Random.nextBytes(32), Random.nextBytes(4))
        val dgram = pkt.seal(99L, 0, 0, 1234L, 7, 960L, ByteArray(40) { it.toByte() })
        val opened = pkt.open(dgram)
        assertNotNull("open null", opened)
        val (hdr, plain) = opened!!
        assertEquals(99L, hdr.callId)
        assertEquals(40, plain.size)
    }

    @Test
    fun udpHpKeServer() {
        val host = InstrumentationRegistry.getArguments().getString("serverIp", "192.168.70.92")
        DatagramSocket().use { sock ->
            val msg = "nvp-device-udp".toByteArray()
            sock.send(DatagramPacket(msg, msg.size, InetSocketAddress(host, 9999)))
        }
    }
}
