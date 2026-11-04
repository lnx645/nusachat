package id.nusachat.app.data

import android.content.Context
import id.nusachat.app.data.crypto.CryptoStore
import id.nusachat.app.data.crypto.DeviceIdentity
import id.nusachat.app.data.crypto.DoubleRatchet
import id.nusachat.app.data.crypto.EnvelopeV2
import id.nusachat.app.data.crypto.SenderKey
import id.nusachat.app.data.crypto.X3dh
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class SessionCryptoException(message: String) : Exception(message)

/**
 * Orkestrasi E2EE per-device (docs/E2EE-PROTOCOL.md) di atas primitif murni.
 *
 * - 1-1: sesi X3DH + Double Ratchet per pasangan device, amplop `pairwise`
 *   multi-part (siap multi-device Fase 3).
 * - Grup: SenderKey per device pengirim; distribusi (SKDM) lewat sesi
 *   pairwise sebagai pesan relay biasa yang disembunyikan penerima.
 * - Dekrip murni lokal (sinkron); enkripsi suspend (bisa ambil bundle).
 * - Fallback: peer tanpa device v2 → enkripsi legacy; badan non-amplop →
 *   dekrip legacy; amplop tak terbaca → placeholder (jangan bocorkan JSON).
 */
class SessionCrypto(
    context: Context,
    private val repository: ChatRepository,
    private val legacy: E2eeManager,
) {
    private val app = context.applicationContext
    val identity = DeviceIdentity(app)
    private val store = CryptoStore(app)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Plaintext kiriman sendiri sesi ini (gema + pratinjau). */
    private val plainSent = LinkedHashMap<Long, String>()

    // ------------------------------------------------------------- provision

    /** Daftarkan device + prekey; rotasi SPK dan isi ulang OTPK bila perlu. */
    suspend fun provision() {
        val deviceId = identity.deviceId()
        repository.registerDevice(
            deviceId, "android", identity.deviceName(),
            identity.identitySignPubB64(), identity.identityDhPubB64(),
        )
        val spk = identity.signedPrekey(identity.signedPrekeyNeedsRotation())
        val fresh = if (identity.otpkRemaining() < OTPK_REFILL_BELOW) {
            identity.mintOtpks(OTPK_BATCH)
        } else emptyList()
        repository.uploadPrekeys(
            deviceId, spk.keyId, spk.pubB64, spk.sigB64,
            fresh.map { it.keyId to it.pubB64 },
        )
    }

    /** Cabut device di server lalu buang semua materi v2 lokal (logout). */
    suspend fun revokeAndWipe() {
        runCatching { repository.revokeDevice(identity.deviceId()) }
        identity.wipeAll()
        store.wipeAll()
        synchronized(plainSent) { plainSent.clear() }
    }

    // ------------------------------------------------------------- enkripsi

    /**
     * Enkripsi teks untuk percakapan. Grup tanpa satu pun device v2
     * (dan 1-1 yang peer-nya belum provision) jatuh ke legacy.
     * @throws SessionCryptoException bila melebihi batas / SKDM gagal.
     */
    suspend fun encryptText(conv: Conversation, myUserId: Long, plain: String): String {
        if (plain.isEmpty()) return plain
        if (!conv.isGroup) {
            val peerId = conv.peer?.id
                ?: conv.members.firstOrNull { it.id != myUserId }?.id
                ?: return legacyFallback(conv.id, plain)
            val devices = peerDevices(peerId)
            if (devices.isEmpty()) return legacyFallback(conv.id, plain)
            val pt = plain.toByteArray(Charsets.UTF_8)
            val parts = mutableMapOf<String, EnvelopeV2.Part>()
            for (dev in devices) {
                encryptPairwise(peerId, dev, pt)?.let { parts[dev] = it }
            }
            if (parts.isEmpty()) return legacyFallback(conv.id, plain)
            return checkSize(EnvelopeV2.pairwise(identity.deviceId(), parts))
        }
        val members = conv.members.map { it.id }.filter { it != myUserId }.distinct()
            .ifEmpty {
                repository.conversationDetail(conv.id).members
                    .map { it.id }.filter { it != myUserId }.distinct()
            }
        val targets = mutableListOf<Pair<Long, String>>()
        for (uid in members) {
            peerDevices(uid).forEach { targets.add(uid to it) }
        }
        // Tanpa target v2 sama sekali: legacy (kompatibilitas).
        if (targets.isEmpty()) return legacyFallback(conv.id, plain)
        val want = targets.map { "${it.first}:${it.second}" }.toSet()
        var chain = store.loadSendChain(conv.id)
        if (chain == null || store.loadSkdmShared(conv.id) != want) {
            chain = SenderKey.create()
            store.saveSendChain(conv.id, chain)
            val delivered = distributeSkdm(conv.id, targets, chain)
            // Hanya yang terbukti terkirim; sisanya dicoba lagi kiriman berikut.
            store.saveSkdmShared(conv.id, delivered)
        }
        val res = SenderKey.encrypt(chain, identity.deviceId(), plain.toByteArray(Charsets.UTF_8))
        store.saveSendChain(conv.id, res.chain)
        val ct = res.ciphertext
        return checkSize(
            EnvelopeV2.senderkey(identity.deviceId(), ct.index, ct.nonceB64, ct.bodyB64, ct.sigB64)
        )
    }

    private suspend fun peerDevices(userId: Long): List<String> =
        runCatching {
            repository.userDevices(userId)
                .mapNotNull { it.optString("device_id").ifBlank { null } }
                .distinct()
        }.getOrDefault(emptyList())

    /** Satu part pairwise; null bila bundle/sesi gagal (penelepon fallback). */
    private suspend fun encryptPairwise(
        userId: Long,
        deviceId: String,
        plaintext: ByteArray,
    ): EnvelopeV2.Part? = runCatching {
        val existing = store.loadSession(userId, deviceId)
        if (existing != null && existing.sendChain != null) {
            val res = DoubleRatchet.encrypt(existing, plaintext)
            store.saveSession(userId, deviceId, res.state)
            return@runCatching EnvelopeV2.Part(
                null, res.ciphertext.header, res.ciphertext.nonceB64, res.ciphertext.bodyB64
            )
        }
        // Sesi baru (atau aku Bob-yang-belum-menerima → inisiasi juga): X3DH.
        val bundle = repository.deviceBundle(userId, deviceId)?.let { parseBundle(it) }
            ?: return@runCatching null
        val init = X3dh.initiator(identity.identityDhPrivB64(), bundle)
        val st = DoubleRatchet.initAlice(init.sharedKey, bundle.signedPubB64)
        val res = DoubleRatchet.encrypt(st, plaintext)
        store.saveSession(userId, deviceId, res.state)
        EnvelopeV2.Part(
            init.header, res.ciphertext.header, res.ciphertext.nonceB64, res.ciphertext.bodyB64
        )
    }.getOrNull()

    private fun parseBundle(o: JSONObject): X3dh.Bundle? = runCatching {
        val spk = o.optJSONObject("signed_prekey") ?: return null
        val otk = o.optJSONObject("onetime_prekey")
        X3dh.Bundle(
            identitySignPubB64 = o.getString("identity_sign_pub"),
            identityDhPubB64 = o.getString("identity_dh_pub"),
            signedKeyId = spk.getInt("key_id"),
            signedPubB64 = spk.getString("public_key"),
            signedSigB64 = spk.getString("signature"),
            onetimeKeyId = otk?.optInt("key_id"),
            onetimePubB64 = otk?.optString("public_key"),
        )
    }.getOrNull()

    /**
     * Kirim SKDM per device target sebagai pesan relay `silent`
     * (tetap event realtime, tanpa push). Gagal jaringan = exception
     * (fail closed: pesan grup batal plutôt than tak terbaca).
     * @return himpunan "userId:deviceId" yang terkirim.
     */
    private suspend fun distributeSkdm(
        conversationId: Long,
        targets: List<Pair<Long, String>>,
        chain: SenderKey.SendChain,
    ): Set<String> {
        val dist = SenderKey.distribution(chain)
        val pt = EnvelopeV2.skdmPlaintext(dist.chainKeyB64, dist.signPubB64)
            .toByteArray(Charsets.UTF_8)
        val delivered = mutableSetOf<String>()
        for ((uid, dev) in targets) {
            val part = encryptPairwise(uid, dev, pt) ?: continue
            val env = EnvelopeV2.skdm(identity.deviceId(), dev, conversationId, part)
            repository.sendMessage(conversationId, env, UUID.randomUUID().toString(), null, silent = true)
            delivered.add("$uid:$dev")
        }
        return delivered
    }

    /**
     * Fallback legacy yang anti-racun: kunci lokal BARU hanya boleh dibuat
     * bila server belum punya blob kanonis sama sekali (percakapan baru).
     * Bila kanonis ada di tempat lain (habis reinstall) → lempar
     * [KEY_SYNC_NEEDED]: pengirim harus heal dulu, JANGAN buat kunci tandingan
     * (K2) yang meracuni blob dan mengunci percakapan selamanya.
     */
    private suspend fun legacyFallback(convId: Long, plain: String): String {
        if (legacy.isEnabled(convId)) return legacy.encrypt(convId, plain)
        val keys = runCatching { repository.convKeys(convId) }.getOrNull()
        if (keys != null && (keys.devices.isNotEmpty() || keys.holders.isNotEmpty())) {
            throw SessionCryptoException(KEY_SYNC_NEEDED)
        }
        return legacy.encrypt(convId, plain)
    }

    private fun checkSize(env: String): String {
        if (env.length > MAX_ENVELOPE) {
            throw SessionCryptoException("Pesan terlalu panjang setelah enkripsi.")
        }
        return env
    }

    // ------------------------------------------------------------- dekripsi

    /**
     * Dekrip satu pesan (sinkron, murni lokal). Null = SKDM: sudah
     * diproses (atau bukan untukku) dan tidak boleh tampil/disimpan.
     */
    fun decryptOne(msg: Message, myUserId: Long): Message? {
        val body = msg.body
        if (msg.type != "text" || body == null || !EnvelopeV2.looksLikeEnvelope(body)) {
            return legacy.decryptMessage(msg)
        }
        val parsed = EnvelopeV2.parse(body) ?: return msg.copy(body = PLACEHOLDER_BROKEN)
        val myDev = identity.deviceId()
        return when (parsed) {
            is EnvelopeV2.Parsed.Pairwise -> {
                if (parsed.fromDevice == myDev) return selfEcho(msg)
                val part = parsed.parts[myDev] ?: return msg.copy(body = PLACEHOLDER_BROKEN)
                val pt = decryptPairwise(msg.sender?.id ?: 0L, parsed.fromDevice, part)
                    ?: return msg.copy(body = PLACEHOLDER_BROKEN)
                msg.copy(body = pt)
            }
            is EnvelopeV2.Parsed.Senderkey -> {
                if (parsed.fromDevice == myDev) return selfEcho(msg)
                msg.copy(
                    body = decryptSenderkey(msg.conversationId, parsed)
                        ?: return msg.copy(body = PLACEHOLDER_BROKEN)
                )
            }
            is EnvelopeV2.Parsed.Skdm -> {
                if (parsed.toDevice == myDev && parsed.conversationId == msg.conversationId) {
                    runCatching { installSkdm(msg.sender?.id ?: 0L, parsed) }
                }
                null
            }
        }
    }

    /** Saring + dekrip daftar terima (SKDM dibuang). */
    fun processIncoming(list: List<Message>, myUserId: Long): List<Message> =
        list.mapNotNull { decryptOne(it, myUserId) }

    /** Gema kiriman sendiri: sesi kirim tak bisa dibuka; pakai memori sesi. */
    private fun selfEcho(msg: Message): Message {
        val known = synchronized(plainSent) { plainSent[msg.id] }
        return if (known != null) msg.copy(body = known) else msg.copy(body = PLACEHOLDER_SELF)
    }

    fun rememberPlain(messageId: Long, plain: String) {
        if (messageId <= 0 || plain.isEmpty()) return
        synchronized(plainSent) {
            if (plainSent.size >= MAX_REMEMBERED) {
                val oldest = plainSent.keys.firstOrNull()
                if (oldest != null) plainSent.remove(oldest)
            }
            plainSent[messageId] = plain
        }
    }

    private fun decryptPairwise(senderUserId: Long, senderDevice: String, part: EnvelopeV2.Part): String? =
        runCatching {
            var st = store.loadSession(senderUserId, senderDevice)
            val x = part.x3dh
            if (x != null && (st == null || shouldReplace(st, x.identityDhPubB64))) {
                val fresh = responderCreate(x)
                store.saveSession(senderUserId, senderDevice, fresh)
                st = fresh
            }
            st ?: return@runCatching null
            val res = DoubleRatchet.decrypt(
                st, DoubleRatchet.Ciphertext(part.dr, part.nonceB64, part.ctB64)
            )
            store.saveSession(senderUserId, senderDevice, res.state)
            if (x != null) refillIfLow()
            res.plaintext.toString(Charsets.UTF_8)
        }.getOrNull()

    /**
     * Inisiasi ganda: aku sudah mengirim tapi belum menerima dari sesi
     * ini → tie-break identity-DH (tepat satu pihak mengalah).
     */
    private fun shouldReplace(st: DoubleRatchet.State, remoteIdhB64: String): Boolean {
        if (st.sendCount <= 0 || st.recvCount > 0) return false
        return EnvelopeV2.replaceOnRemoteInit(identity.identityDhPubB64(), remoteIdhB64)
    }

    private fun responderCreate(x: X3dh.HandshakeHeader): DoubleRatchet.State {
        val spkPriv = identity.signedPrekeyPriv(x.signedKeyId)
            ?: error("signed-prekey tak dikenal: ${x.signedKeyId}")
        val otpkPriv = x.onetimeKeyId?.let { identity.consumeOtpk(it) }
        val res = X3dh.responder(identity.identityDhPrivB64(), spkPriv, otpkPriv, x)
        return DoubleRatchet.initBob(res.sharedKey, spkPriv)
    }

    private fun decryptSenderkey(conversationId: Long, sk: EnvelopeV2.Parsed.Senderkey): String? =
        runCatching {
            val chain = store.loadRecvChain(conversationId, sk.fromDevice) ?: return@runCatching null
            val res = SenderKey.decrypt(
                chain,
                SenderKey.GroupCiphertext(sk.fromDevice, sk.index, sk.nonceB64, sk.ctB64, sk.sigB64),
            )
            store.saveRecvChain(conversationId, sk.fromDevice, res.chain)
            res.plaintext.toString(Charsets.UTF_8)
        }.getOrNull()

    private fun installSkdm(senderUserId: Long, skdm: EnvelopeV2.Parsed.Skdm) {
        val pt = decryptPairwise(senderUserId, skdm.fromDevice, skdm.part) ?: return
        val (ck, sp) = EnvelopeV2.parseSkdmPlaintext(pt.toByteArray(Charsets.UTF_8)) ?: return
        store.saveRecvChain(
            skdm.conversationId, skdm.fromDevice,
            SenderKey.receive(SenderKey.Distribution(ck, sp)),
        )
    }

    private fun refillIfLow() {
        if (identity.otpkRemaining() >= OTPK_REFILL_BELOW) return
        io.launch { runCatching { provision() } }
    }

    companion object {
        /** v2 tak terbuka permanen (forward secrecy): tanpa kurung, rapi. */
        const val PLACEHOLDER_BROKEN = "Pesan ini tidak tersedia."
        /** Gema kiriman sendiri di sesi lain: tanpa kurung, rapi. */
        const val PLACEHOLDER_SELF = "Pesan terkirim."
        /** Kunci kanonis ada di pihak lain; heal dulu sebelum kirim legacy. */
        const val KEY_SYNC_NEEDED = "KEY_SYNC_NEEDED"
        private const val OTPK_BATCH = 100
        private const val OTPK_REFILL_BELOW = 20
        private const val MAX_ENVELOPE = 8000
        private const val MAX_REMEMBERED = 500
    }
}
