package id.nusachat.app.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import org.json.JSONObject

/**
 * Penyimpanan state ratchet + sender key (encrypted prefs).
 *
 * Isinya rahasia (root key, chain key, skipped keys) — tidak pernah
 * keluar HP. JSON manual via org.json (konsisten repo); serialisasi
 * simetris: save* ↔ load* di bawah, dicover test round-trip? TIDAK —
 * lapisan ini butuh Android (prefs); logika kripto murninya yang
 * di-unit-test. Jaga fungsi ini tetap bodoh (get/put string saja).
 */
class CryptoStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "nusa.ratchet",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ------------------------------------------------------------- sesi

    private fun sessKey(userId: Long, deviceId: String) = "sess:$userId:$deviceId"

    fun saveSession(userId: Long, deviceId: String, state: DoubleRatchet.State) {
        val skipped = JSONObject()
        state.skipped.forEach { (k, v) -> skipped.put(k, b64e(v)) }
        val o = JSONObject()
            .put("rk", b64e(state.rootKey))
            .put("sc", state.sendChain?.let { b64e(it) })
            .put("rc", state.recvChain?.let { b64e(it) })
            .put("dhs", state.dhSelfPrivB64)
            .put("dhr", state.dhRemotePubB64)
            .put("ns", state.sendCount)
            .put("nr", state.recvCount)
            .put("pns", state.prevSendCount)
            .put("skipped", skipped)
        prefs.edit().putString(sessKey(userId, deviceId), o.toString()).apply()
    }

    fun loadSession(userId: Long, deviceId: String): DoubleRatchet.State? {
        val raw = prefs.getString(sessKey(userId, deviceId), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val skipped = mutableMapOf<String, ByteArray>()
            val sk = o.optJSONObject("skipped")
            if (sk != null) {
                val it = sk.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    skipped[k] = b64d(sk.getString(k))
                }
            }
            DoubleRatchet.State(
                rootKey = b64d(o.getString("rk")),
                sendChain = o.optString("sc").ifBlank { null }?.let { b64d(it) },
                recvChain = o.optString("rc").ifBlank { null }?.let { b64d(it) },
                dhSelfPrivB64 = o.getString("dhs"),
                dhRemotePubB64 = o.optString("dhr").ifBlank { null },
                sendCount = o.optInt("ns"),
                recvCount = o.optInt("nr"),
                prevSendCount = o.optInt("pns"),
                skipped = skipped,
            )
        }.getOrNull()
    }

    fun deleteSession(userId: Long, deviceId: String) {
        prefs.edit().remove(sessKey(userId, deviceId)).apply()
    }

    // ------------------------------------------------------------ sender key

    private fun sendKey(conversationId: Long) = "sksend:$conversationId"
    private fun recvKey(conversationId: Long, senderDevice: String) = "skrecv:$conversationId:$senderDevice"

    fun saveSendChain(conversationId: Long, chain: SenderKey.SendChain) {
        val o = JSONObject()
            .put("ck", b64e(chain.chainKey))
            .put("sp", chain.signPrivB64)
            .put("sb", chain.signPubB64)
            .put("i", chain.index)
        prefs.edit().putString(sendKey(conversationId), o.toString()).apply()
    }

    fun loadSendChain(conversationId: Long): SenderKey.SendChain? {
        val raw = prefs.getString(sendKey(conversationId), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            SenderKey.SendChain(b64d(o.getString("ck")), o.getString("sp"), o.getString("sb"), o.optInt("i"))
        }.getOrNull()
    }

    fun saveRecvChain(conversationId: Long, senderDevice: String, chain: SenderKey.RecvChain) {
        val skipped = JSONObject()
        chain.skipped.forEach { (k, v) -> skipped.put(k.toString(), b64e(v)) }
        val o = JSONObject()
            .put("ck", b64e(chain.chainKey))
            .put("sb", chain.signPubB64)
            .put("i", chain.index)
            .put("skipped", skipped)
        prefs.edit().putString(recvKey(conversationId, senderDevice), o.toString()).apply()
    }

    fun loadRecvChain(conversationId: Long, senderDevice: String): SenderKey.RecvChain? {
        val raw = prefs.getString(recvKey(conversationId, senderDevice), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val skipped = mutableMapOf<Int, ByteArray>()
            val sk = o.optJSONObject("skipped")
            if (sk != null) {
                val it = sk.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    k.toIntOrNull()?.let { skipped[it] = b64d(sk.getString(k)) }
                }
            }
            SenderKey.RecvChain(b64d(o.getString("ck")), o.getString("sb"), o.optInt("i"), skipped)
        }.getOrNull()
    }

    /**
     * Jejak distribusi SKDM: "userId:deviceId" yang sudah menerima rantai
     * kirimku saat ini. Beda himpunan anggota → rotasi + distribusi ulang.
     */
    fun saveSkdmShared(conversationId: Long, members: Set<String>) {
        prefs.edit().putStringSet("skshared:$conversationId", members).apply()
    }

    fun loadSkdmShared(conversationId: Long): Set<String> =
        runCatching {
            prefs.getStringSet("skshared:$conversationId", emptySet()) ?: emptySet()
        }.getOrDefault(emptySet())

    /** Buang semua sender key grup (anggota keluar → rotasi, sesuai spek). */
    fun dropGroupKeys(conversationId: Long) {
        val edit = prefs.edit().remove(sendKey(conversationId)).remove("skshared:$conversationId")
        prefs.all.keys
            .filter { it.startsWith("skrecv:$conversationId:") }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    fun wipeAll() {
        prefs.edit().clear().apply()
    }
}
