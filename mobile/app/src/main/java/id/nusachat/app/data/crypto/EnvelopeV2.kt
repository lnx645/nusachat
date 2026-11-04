package id.nusachat.app.data.crypto

import id.nusachat.app.data.crypto.CryptoPrimitives.b64d

/**
 * Amplop v2: ciphertext per-device di badan pesan relay (server buram).
 *
 * Bentuk kabel (JSON, field order tetap; server tidak memaknai isinya):
 * - pairwise: `{"v":2,"kind":"pairwise","from":"dev","parts":{"dev":{"x3dh"?,...}}}`
 * - senderkey: `{"v":2,"kind":"senderkey","from":"dev","idx":N,...}`
 * - skdm: `{"v":2,"kind":"skdm","from":"dev","to":"dev","conv":N,"part":{...}}`
 *
 * Part pairwise: `{"x3dh":{"idh","ek","spk","otk"}?,"dr":{"rp","pl","n"},
 * "nonce","ct"}`. Plaintext pairwise = UTF-8 mentah; plaintext SKDM =
 * `{"ck":"..","sp":".."}` (chainKey + signPub b64).
 *
 * Murni JVM (tanpa android.* maupun org.json) agar bisa di-unit-test di
 * JVM biasa — JSON ditulis/diurai manual karena alfabet aman
 * (b64 + id device tidak mengandung quote/backslash).
 */
object EnvelopeV2 {
    data class Part(
        val x3dh: X3dh.HandshakeHeader?,
        val dr: DoubleRatchet.Header,
        val nonceB64: String,
        val ctB64: String,
    )

    sealed interface Parsed {
        data class Pairwise(val fromDevice: String, val parts: Map<String, Part>) : Parsed
        data class Senderkey(
            val fromDevice: String,
            val index: Int,
            val nonceB64: String,
            val ctB64: String,
            val sigB64: String,
        ) : Parsed

        data class Skdm(
            val fromDevice: String,
            val toDevice: String,
            val conversationId: Long,
            val part: Part,
        ) : Parsed
    }

    // ---------------------------------------------------------------- emit

    fun pairwise(fromDevice: String, parts: Map<String, Part>): String {
        val body = parts.entries.joinToString(",") { (dev, p) -> "\"${esc(dev)}\":${emitPart(p)}" }
        return "{\"v\":2,\"kind\":\"pairwise\",\"from\":\"${esc(fromDevice)}\",\"parts\":{$body}}"
    }

    fun senderkey(fromDevice: String, index: Int, nonceB64: String, ctB64: String, sigB64: String): String =
        "{\"v\":2,\"kind\":\"senderkey\",\"from\":\"${esc(fromDevice)}\",\"idx\":$index," +
            "\"nonce\":\"$nonceB64\",\"ct\":\"$ctB64\",\"sig\":\"$sigB64\"}"

    fun skdm(fromDevice: String, toDevice: String, conversationId: Long, part: Part): String =
        "{\"v\":2,\"kind\":\"skdm\",\"from\":\"${esc(fromDevice)}\",\"to\":\"${esc(toDevice)}\"," +
            "\"conv\":$conversationId,\"part\":${emitPart(part)}}"

    fun skdmPlaintext(chainKeyB64: String, signPubB64: String): String =
        "{\"ck\":\"$chainKeyB64\",\"sp\":\"$signPubB64\"}"

    private fun emitPart(p: Part): String {
        val x = p.x3dh?.let {
            val otk = it.onetimeKeyId?.toString() ?: "null"
            "\"x3dh\":{\"idh\":\"${it.identityDhPubB64}\",\"ek\":\"${it.ephemeralPubB64}\"," +
                "\"spk\":${it.signedKeyId},\"otk\":$otk},"
        } ?: ""
        return "{$x\"dr\":{\"rp\":\"${p.dr.ratchetPubB64}\",\"pl\":${p.dr.prevLen}," +
            "\"n\":${p.dr.msgNo}},\"nonce\":\"${p.nonceB64}\",\"ct\":\"${p.ctB64}\"}"
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ---------------------------------------------------------------- parse

    /** Saring cepat sebelum parse penuh (jalur terima panas). */
    fun looksLikeEnvelope(body: String?): Boolean =
        body != null && body.startsWith("{\"v\":2,\"kind\":")

    fun parse(body: String): Parsed? = runCatching {
        val root = Reader(body).readObject() ?: return null
        if (root.num("v") != 2L) return null
        val from = root.str("from")?.takeIf { validDevice(it) } ?: return null
        when (root.str("kind")) {
            "pairwise" -> {
                val partsObj = root.obj("parts") ?: return null
                if (partsObj.keys().size > MAX_PARTS) return null
                val parts = mutableMapOf<String, Part>()
                for (dev in partsObj.keys()) {
                    if (!validDevice(dev)) return null
                    parts[dev] = parsePart(partsObj.obj(dev) ?: return null) ?: return null
                }
                if (parts.isEmpty()) return null
                Parsed.Pairwise(from, parts)
            }
            "senderkey" -> {
                val idx = root.num("idx") ?: return null
                if (idx < 0 || idx > Int.MAX_VALUE) return null
                Parsed.Senderkey(
                    from, idx.toInt(),
                    root.b64("nonce") ?: return null,
                    root.b64("ct") ?: return null,
                    root.b64("sig") ?: return null,
                )
            }
            "skdm" -> {
                val to = root.str("to")?.takeIf { validDevice(it) } ?: return null
                val conv = root.num("conv") ?: return null
                if (conv <= 0) return null
                val part = parsePart(root.obj("part") ?: return null) ?: return null
                Parsed.Skdm(from, to, conv, part)
            }
            else -> null
        }
    }.getOrNull()

    fun parseSkdmPlaintext(bytes: ByteArray): Pair<String, String>? = runCatching {
        val o = Reader(bytes.toString(Charsets.UTF_8)).readObject() ?: return null
        val ck = o.b64("ck") ?: return null
        val sp = o.b64("sp") ?: return null
        ck to sp
    }.getOrNull()

    private fun parsePart(o: Map<String, Any?>): Part? {
        val x = o["x3dh"] as? Map<*, *>
        val x3dh = if (x == null) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            val xm = x as Map<String, Any?>
            X3dh.HandshakeHeader(
                identityDhPubB64 = (xm.str("idh")?.takeIf { validB64(it) }) ?: return null,
                ephemeralPubB64 = (xm.str("ek")?.takeIf { validB64(it) }) ?: return null,
                signedKeyId = (xm.num("spk") ?: return null).toInt(),
                onetimeKeyId = (xm["otk"] as? Long)?.toInt(),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val dr = o["dr"] as? Map<String, Any?> ?: return null
        val header = DoubleRatchet.Header(
            ratchetPubB64 = (dr.str("rp")?.takeIf { validB64(it) }) ?: return null,
            prevLen = (dr.num("pl") ?: return null).toInt(),
            msgNo = (dr.num("n") ?: return null).toInt(),
        )
        if (header.prevLen < 0 || header.msgNo < 0) return null
        return Part(
            x3dh = x3dh,
            dr = header,
            nonceB64 = (o.str("nonce")?.takeIf { validB64(it) }) ?: return null,
            ctB64 = (o.str("ct")?.takeIf { validB64(it) }) ?: return null,
        )
    }

    // ------------------------------------------------- tie-break X3DH ganda

    /**
     * Dua pihak memulai X3DH bersamaan: tepat satu sesi yang dipakai.
     * Pihak dengan identity-DH lebih BESAR (byte leksikografis) yang
     * mengalah — deterministik dan sama di dua sisi.
     */
    fun replaceOnRemoteInit(myIdentityDhB64: String, remoteIdentityDhB64: String): Boolean =
        runCatching {
            val mine = b64d(myIdentityDhB64)
            val theirs = b64d(remoteIdentityDhB64)
            compareBytes(mine, theirs) > 0
        }.getOrDefault(false)

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    // ------------------------------------------------- JSON mini + validasi

    private const val MAX_PARTS = 64
    private const val MAX_STR = 20000
    private val DEVICE_RE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val B64_RE = Regex("[A-Za-z0-9+/]+=*")

    private fun validDevice(s: String): Boolean = DEVICE_RE.matches(s)

    private fun validB64(s: String): Boolean =
        s.length in 4..MAX_STR && s.length % 4 == 0 && B64_RE.matches(s) &&
            runCatching { b64d(s) }.isSuccess

    private fun Map<String, Any?>.str(k: String): String? = this[k] as? String
    private fun Map<String, Any?>.num(k: String): Long? = this[k] as? Long
    private fun Map<String, Any?>.obj(k: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return this[k] as? Map<String, Any?>
    }
    private fun Map<String, Any?>.b64(k: String): String? = str(k)?.takeIf { validB64(it) }
    private fun Map<String, Any?>.keys(): Set<String> = keys

    /** Parser JSON seperlunya: objek/string/angka/null. Array tidak dipakai. */
    private class Reader(private val s: String) {
        private var i = 0

        fun readObject(): Map<String, Any?>? {
            skipWs()
            if (!eat('{')) return null
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (eat('}')) return out
            while (true) {
                skipWs()
                val key = readString() ?: return null
                skipWs()
                if (!eat(':')) return null
                out[key] = readValue() ?: return null
                skipWs()
                if (eat('}')) return out
                if (!eat(',')) return null
            }
        }

        private fun readValue(): Any?? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '"' -> readString()
                '{' -> readObject()
                'n' -> if (s.startsWith("null", i)) {
                    i += 4
                    Null
                } else null
                '-', in '0'..'9' -> readNumber()
                else -> null
            }
        }

        private fun readString(): String? {
            if (!eat('"')) return null
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString().takeIf { it.length <= MAX_STR }
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun readNumber(): Long? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (start == i || (s[start] == '-' && start + 1 == i)) return null
            // Tolak desimal/eksponen: amplop hanya memakai integer.
            if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) return null
            return s.substring(start, i).toLongOrNull()
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun eat(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private object Null {
            override fun toString(): String = "null"
        }
    }
}
