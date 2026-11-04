package id.nusachat.app.data.voice

/**
 * Kurir signaling NVP/1 (lihat docs/VOICE-PROTOCOL.md §4). Murni JVM-testable:
 * base64url + JSON ditulis tangan untuk skema tetap (tanpa org.json yang
 * stub di unit test). Alphabet base64url + codec set tetap = tanpa escaping.
 */
object NvpBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(input: ByteArray): String {
        val out = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else 0
            val n = ((b0 shl 16) or (b1 shl 8) or b2)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            if (i + 1 < input.size) out.append(ALPHABET[(n shr 6) and 0x3F])
            if (i + 2 < input.size) out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        return out.toString()
    }

    /** Null bila karakter ilegal/panjang sisa 1 (tak valid). */
    fun decode(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        val values = IntArray(input.length) {
            ALPHABET.indexOf(input[it]).also { v -> if (v < 0) return null }
        }
        if (values.size % 4 == 1) return null
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < values.size) {
            val remaining = values.size - i
            val n = (values[i] shl 18) or
                ((if (remaining > 1) values[i + 1] else 0) shl 12) or
                ((if (remaining > 2) values[i + 2] else 0) shl 6) or
                (if (remaining > 3) values[i + 3] else 0)
            out.add(((n shr 16) and 0xFF).toByte())
            if (remaining > 2) out.add(((n shr 8) and 0xFF).toByte())
            if (remaining > 3) out.add((n and 0xFF).toByte())
            i += 4
        }
        return out.toByteArray()
    }
}

/** Offer NVP genggaman: dari WS langsung atau kurir REST `nvp1:`. */
data class NvpOffer(
    val codecs: List<String>,
    val keyB64: String,
    val saltB64: String,
)

object NvpMarkers {
    const val PREFIX = "nvp1:"

    private val allowedCodecs = setOf(Nvp.CODEC_OPUS, Nvp.CODEC_AMR_WB)

    /**
     * Bungkus offer jadi string `sdp` REST: `nvp1:` + b64url(JSON tetap).
     * JSON: {"codecs":["opus"],"key_ct":"..","salt":".."} (key/salt b64url).
     */
    fun offerToSdp(codecs: List<String>, keyB64: String, saltB64: String): String {
        require(codecs.isNotEmpty() && codecs.all { it in allowedCodecs }) { "codec offer ilegal" }
        require(keyB64.isNotEmpty() && saltB64.isNotEmpty()) { "kunci offer kosong" }
        val json = """{"codecs":[${codecs.joinToString(",") { "\"$it\"" }}],"key_ct":"$keyB64","salt":"$saltB64"}"""
        return PREFIX + NvpBase64.encode(json.toByteArray(Charsets.UTF_8))
    }

    /** Urai kurir offer; null bila format/codec tak valid. */
    fun parseOfferSdp(sdp: String): NvpOffer? {
        if (!sdp.startsWith(PREFIX)) return null
        val json = NvpBase64.decode(sdp.removePrefix(PREFIX))?.toString(Charsets.UTF_8) ?: return null
        // Skema tetap → parser ketat: "codecs":[...], "key_ct":"..", "salt":"..".
        val codecsMatch = Regex(""""codecs":\[([^\]]*)]""").find(json) ?: return null
        val codecs = codecsMatch.groupValues[1].split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
        if (codecs.isEmpty() || codecs.any { it !in allowedCodecs }) return null
        val key = Regex(""""key_ct":"([A-Za-z0-9\-_]*)"""").find(json)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val salt = Regex(""""salt":"([A-Za-z0-9\-_]*)"""").find(json)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() } ?: return null
        return NvpOffer(codecs, key, salt)
    }

    /** Kurir answer REST: `nvp1:<codec>`. */
    fun answerToSdp(codec: String): String {
        require(codec in allowedCodecs) { "codec answer ilegal" }
        return "$PREFIX$codec"
    }

    /** Urai kurir answer; null bila bukan marker/codec ilegal. */
    fun parseAnswerSdp(sdp: String): String? {
        if (!sdp.startsWith(PREFIX)) return null
        val codec = sdp.removePrefix(PREFIX)
        // Offer marker (b64 panjang) bukan answer.
        if (codec.length > 16 || codec !in allowedCodecs) return null
        return codec
    }
}
