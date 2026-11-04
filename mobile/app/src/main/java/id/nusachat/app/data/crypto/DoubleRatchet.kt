package id.nusachat.app.data.crypto

import id.nusachat.app.data.crypto.CryptoPrimitives.aesDecrypt
import id.nusachat.app.data.crypto.CryptoPrimitives.aesEncrypt
import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import id.nusachat.app.data.crypto.CryptoPrimitives.hkdf
import id.nusachat.app.data.crypto.CryptoPrimitives.hmacSha256
import id.nusachat.app.data.crypto.CryptoPrimitives.wipe
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Agree
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519KeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Public

/**
 * Double Ratchet per spesifikasi Signal (implementasi sendiri, murni).
 *
 * - Root chain: HKDF(root, dh_out, "NusaRKv1") → root' || chain.
 * - Message chain: msgKey=HMAC(ck,0x01), chain'=HMAC(ck,0x02).
 * - Cipher: AES-256-GCM, nonce acak 96-bit, AAD = byte header kanonis.
 * - DH ratchet tiap `ratchet_pub` lawan berubah; kunci terlewati
 *   disimpan (maks [MAX_SKIPPED]) untuk pesan out-of-order.
 * - Gagal dekrip TIDAK mengubah state (pure-copy, commit saat sukses).
 */
object DoubleRatchet {
    const val MAX_SKIP = 1000
    const val MAX_SKIPPED = 1000
    private val RK_INFO = "NusaRKv1".toByteArray(Charsets.UTF_8)
    private val MSG_KEY = byteArrayOf(0x01)
    private val CHAIN_KEY = byteArrayOf(0x02)

    data class Header(val ratchetPubB64: String, val prevLen: Int, val msgNo: Int) {
        /** Byte kanonis: field order tetap, dipakai sebagai AAD. */
        fun canonical(): ByteArray =
            "{\"rp\":\"$ratchetPubB64\",\"pl\":$prevLen,\"n\":$msgNo}"
                .toByteArray(Charsets.UTF_8)

        companion object {
            // Parser ketat untuk bentuk yang kita emit (b64 aman quote).
            private val PATTERN =
                Regex("\\{\"rp\":\"([A-Za-z0-9+/=]+)\",\"pl\":(\\d+),\"n\":(\\d+)\\}")
            fun parse(bytes: ByteArray): Header? {
                val m = PATTERN.matchEntire(bytes.toString(Charsets.UTF_8)) ?: return null
                return Header(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        }
    }

    data class Ciphertext(val header: Header, val nonceB64: String, val bodyB64: String)

    data class State(
        val rootKey: ByteArray,
        val sendChain: ByteArray?,
        val recvChain: ByteArray?,
        val dhSelfPrivB64: String,
        val dhRemotePubB64: String?,
        val sendCount: Int = 0,
        val recvCount: Int = 0,
        val prevSendCount: Int = 0,
        /** Kunci terlewati: "b64pub:n" → kunci 32 byte. */
        val skipped: Map<String, ByteArray> = emptyMap(),
    ) {
        override fun equals(other: Any?): Boolean = other is State &&
            rootKey.contentEquals(other.rootKey) &&
            (sendChain?.contentEquals(other.sendChain ?: ByteArray(0)) ?: (other.sendChain == null)) &&
            (recvChain?.contentEquals(other.recvChain ?: ByteArray(0)) ?: (other.recvChain == null)) &&
            dhSelfPrivB64 == other.dhSelfPrivB64 && dhRemotePubB64 == other.dhRemotePubB64 &&
            sendCount == other.sendCount && recvCount == other.recvCount &&
            prevSendCount == other.prevSendCount
        override fun hashCode(): Int = dhSelfPrivB64.hashCode() * 31 + sendCount
    }

    /** Inisiator: sudah tahu pubkey ratchet awal lawan. */
    fun initAlice(sharedKey32: ByteArray, remoteRatchetPubB64: String): State {
        val (selfPriv, selfPub) = x25519KeyPair()
        val dh = x25519Agree(selfPriv, b64d(remoteRatchetPubB64))
        val (root, send) = kdfRk(sharedKey32, dh)
        wipe(dh)
        return State(
            rootKey = root, sendChain = send, recvChain = null,
            dhSelfPrivB64 = b64e(selfPriv), dhRemotePubB64 = remoteRatchetPubB64,
        )
    }

    /**
     * Responder: pubkey ratchet awal lawan datang di pesan pertama.
     * `selfPrivB64` HARUS privkey signed-prekey (kunci DH awal Bob,
     * sesuai spek — diganti keypair baru saat DH ratchet pertama).
     */
    fun initBob(sharedKey32: ByteArray, signedPrekeyPrivB64: String): State =
        State(
            rootKey = sharedKey32, sendChain = null, recvChain = null,
            dhSelfPrivB64 = signedPrekeyPrivB64, dhRemotePubB64 = null,
        )

    data class EncryptResult(val state: State, val ciphertext: Ciphertext)

    fun encrypt(state: State, plaintext: ByteArray): EncryptResult {
        val chain = state.sendChain ?: error("ratchet belum punya send chain")
        val msgKey = hmacSha256(chain, MSG_KEY)
        val nextChain = hmacSha256(chain, CHAIN_KEY)
        val header = Header(
            ratchetPubB64 = b64e(x25519Public(b64d(state.dhSelfPrivB64))),
            prevLen = state.prevSendCount,
            msgNo = state.sendCount,
        )
        val ct = aesEncrypt(msgKey, plaintext, header.canonical())
        wipe(msgKey)
        return EncryptResult(
            state.copy(sendChain = nextChain, sendCount = state.sendCount + 1),
            Ciphertext(header, b64e(ct.nonce12), b64e(ct.bytes)),
        )
    }

    /** Varian deterministik untuk tes (nonce eksplisit). */
    internal fun encryptWithNonce(state: State, plaintext: ByteArray, nonce12: ByteArray): EncryptResult {
        val chain = state.sendChain ?: error("ratchet belum punya send chain")
        val msgKey = hmacSha256(chain, MSG_KEY)
        val nextChain = hmacSha256(chain, CHAIN_KEY)
        val header = Header(
            ratchetPubB64 = b64e(x25519Public(b64d(state.dhSelfPrivB64))),
            prevLen = state.prevSendCount,
            msgNo = state.sendCount,
        )
        val ct = aesEncrypt(msgKey, plaintext, header.canonical(), nonce12)
        wipe(msgKey)
        return EncryptResult(
            state.copy(sendChain = nextChain, sendCount = state.sendCount + 1),
            Ciphertext(header, b64e(ct.nonce12), b64e(ct.bytes)),
        )
    }

    data class DecryptResult(val state: State, val plaintext: ByteArray)

    /** Gagal = exception, state asal utuh (jangan commit parsial). */
    fun decrypt(state: State, ct: Ciphertext): DecryptResult {
        // 1. Coba kunci terlewati dulu.
        skippedGet(state, ct.header)?.let { (key, after) ->
            val pt = aesDecrypt(key, b64d(ct.nonceB64), b64d(ct.bodyB64), ct.header.canonical())
            wipe(key)
            return DecryptResult(after, pt)
        }
        // 2. DH ratchet bila pubkey baru.
        var st = state
        if (ct.header.ratchetPubB64 != state.dhRemotePubB64) {
            st = dhRatchet(state, ct.header)
        }
        // 3. Lewati sampai msg_no (batas anti-DoS).
        val jump = ct.header.msgNo - st.recvCount
        if (jump < 0 || jump > MAX_SKIP) error("msg_no di luar jangkauan: $jump")
        if (st.skipped.size + jump > MAX_SKIPPED) error("cache kunci penuh")
        st = skipTo(st, ct.header.msgNo)
        // 4. Kunci pesan ini + dekrip.
        val chain = st.recvChain ?: error("ratchet belum punya recv chain")
        val msgKey = hmacSha256(chain, MSG_KEY)
        val pt = try {
            aesDecrypt(msgKey, b64d(ct.nonceB64), b64d(ct.bodyB64), ct.header.canonical())
        } finally {
            wipe(msgKey)
        }
        val next = st.copy(
            recvChain = hmacSha256(chain, CHAIN_KEY),
            recvCount = st.recvCount + 1,
        )
        return DecryptResult(next, pt)
    }

    private fun dhRatchet(state: State, header: Header): State {
        var st = state
        // Simpan sisa rantai terima lama (prev_len) sebagai terlewati.
        st = skipTo(st, header.prevLen)
        st = st.copy(
            prevSendCount = st.sendCount,
            sendCount = 0, recvCount = 0,
            dhRemotePubB64 = header.ratchetPubB64,
        )
        // Rantai terima baru dari DH(lawan_baru, saya_lama).
        val dhRecv = x25519Agree(b64d(st.dhSelfPrivB64), b64d(header.ratchetPubB64))
        val (root1, recv) = kdfRk(st.rootKey, dhRecv)
        wipe(dhRecv)
        // Keypair DH baru + rantai kirim baru.
        val (newPriv, _) = x25519KeyPair()
        val dhSend = x25519Agree(newPriv, b64d(header.ratchetPubB64))
        val (root2, send) = kdfRk(root1, dhSend)
        wipe(dhSend)
        return st.copy(
            rootKey = root2, recvChain = recv, sendChain = send,
            dhSelfPrivB64 = b64e(newPriv),
        )
    }

    private fun skipTo(state: State, until: Int): State {
        var st = state
        var chain = st.recvChain
        // Bila belum ada rantai terima (Bob sebelum pesan pertama dengan
        // pubkey yang sudah dikenal), tak ada yang dilewati.
        while (st.recvCount < until) {
            val c = chain ?: break
            val msgKey = hmacSha256(c, MSG_KEY)
            val key = "${st.dhRemotePubB64}:${st.recvCount}"
            st = st.copy(skipped = st.skipped + (key to msgKey))
            chain = hmacSha256(c, CHAIN_KEY)
            st = st.copy(recvChain = chain, recvCount = st.recvCount + 1)
        }
        return st
    }

    private fun skippedGet(state: State, header: Header): Pair<ByteArray, State>? {
        val key = "${header.ratchetPubB64}:${header.msgNo}"
        val msgKey = state.skipped[key] ?: return null
        return msgKey to state.copy(skipped = state.skipped - key)
    }

    private fun kdfRk(rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(dhOut, rootKey, RK_INFO, 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    /** Keypair DH segar (dipakai handshake X3DH sebagai ephemeral). */
    fun freshKeyPair(): Pair<String, String> {
        val (priv, pub) = x25519KeyPair()
        return b64e(priv) to b64e(pub)
    }
}
