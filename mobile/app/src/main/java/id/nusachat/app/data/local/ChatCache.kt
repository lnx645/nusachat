package id.nusachat.app.data.local

import android.content.Context
import id.nusachat.app.data.Message
import id.nusachat.app.data.toJson
import id.nusachat.app.data.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cache chat lokal (Room + SQLCipher).
 *
 * Aturan main:
 * - yang disimpan = pesan versi tampil (sudah didekrip E2EE),
 * - server tetap sumber kebenaran: cache hanya mempercepat buka chat,
 *   melayani baca offline, dan menampung antre kirim,
 * - pesan antre offline berstatus "queued" + pending=true.
 */
class ChatCache(context: Context) {
    private val app = context.applicationContext

    /** Resolve per operasi: instance DB bisa diganti (logout menghapus file). */
    private fun dao() = ChatDatabase.get(app).chatDao()

    suspend fun saveMessages(messages: List<Message>): Unit = withContext(Dispatchers.IO) {
        val rows = messages.filter { it.id != 0L }
        if (rows.isEmpty()) return@withContext
        // Hasil dekrip gagal (placeholder/cipher) tak boleh menimpa cache
        // terbaca — cegah trauma reinstall menular ke cache bagus.
        val (ok, suspect) = rows.partition { isReadableForCache(it) }
        val skip = if (suspect.isEmpty()) {
            emptySet()
        } else {
            val existing = dao().byIds(suspect.map { it.id })
                .mapNotNull { it.toMessage() }.associateBy { it.id }
            suspect.filter { shouldKeepExisting(existing[it.id], it) }
                .map { it.id }.toSet()
        }
        val final = (ok + suspect.filter { it.id !in skip }).map { it.toRow() }
        if (final.isNotEmpty()) dao().upsertAll(final)
    }

    /** Jumlah pesan server di cache (pendeteksi device baru untuk restore). */
    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        runCatching { dao().totalCount() }.getOrDefault(0)
    }

    suspend fun saveMessage(message: Message): Unit = withContext(Dispatchers.IO) {
        if (message.id != 0L) dao().upsert(message.toRow())
    }

    /** Pesan terbaru dari cache (urut naik seperti tampilan). */
    suspend fun recent(conversationId: Long, limit: Int = 40): List<Message> =
        withContext(Dispatchers.IO) {
            dao().recent(conversationId, limit).mapNotNull { it.toMessage() }.sortedBy { it.id }
        }

    suspend fun older(conversationId: Long, beforeId: Long, limit: Int = 30): List<Message> =
        withContext(Dispatchers.IO) {
            dao().older(conversationId, beforeId, limit).mapNotNull { it.toMessage() }.sortedBy { it.id }
        }

    /** Antre kirim offline di semua percakapan. */
    suspend fun queued(): List<Message> = withContext(Dispatchers.IO) {
        dao().queued().mapNotNull { it.toMessage() }
    }

    /** Tandai pesan antre sebagai terkirim: hapus baris id-sementara, simpan versi server. */
    suspend fun markSent(clientId: String, sent: Message): Unit = withContext(Dispatchers.IO) {
        if (clientId.isNotBlank()) dao().deleteByClientId(clientId)
        if (sent.id > 0) dao().upsert(sent.copy(pending = false).toRow())
    }

    /** Simpan status terbaru pesan antre (mis. gagal total → tetap queued untuk retry manual). */
    suspend fun updateQueued(message: Message): Unit = withContext(Dispatchers.IO) {
        if (message.clientId != null) dao().upsert(message.toRow())
    }

    suspend fun deleteConversation(conversationId: Long): Unit = withContext(Dispatchers.IO) {
        dao().deleteConversation(conversationId)
        dao().deleteCursor(conversationId)
    }

    suspend fun deleteMessage(messageId: Long): Unit = withContext(Dispatchers.IO) {
        dao().deleteMessage(messageId)
    }

    suspend fun cursor(conversationId: Long): Long? = withContext(Dispatchers.IO) {
        dao().cursor(conversationId)?.lastId
    }

    /** Cursor hanya maju (pesan realtime bisa tiba tak urut). */
    suspend fun saveCursor(conversationId: Long, lastId: Long): Unit = withContext(Dispatchers.IO) {
        if (lastId <= 0) return@withContext
        val current = dao().cursor(conversationId)?.lastId ?: 0L
        if (lastId > current) dao().saveCursor(SyncState(conversationId, lastId))
    }

    companion object {
        /** Baris layak simpan tanpa syarat (terbaca / non-teks). */
        fun isReadableForCache(m: Message): Boolean {
            if (m.type != "text" || m.body == null) return true
            val b = m.body
            if (b == id.nusachat.app.data.SessionCrypto.PLACEHOLDER_BROKEN) return false
            if (b == id.nusachat.app.data.SessionCrypto.PLACEHOLDER_SELF) return false
            return id.nusachat.app.data.E2eeManager.isReadableBody(b)
        }

        /**
         * True = jangan timpa [existing] dengan [incoming] (masuk tak terbaca,
         * lama terbaca). Tanpa existing selalu simpan.
         */
        fun shouldKeepExisting(existing: Message?, incoming: Message): Boolean {
            if (existing == null) return false
            if (incoming.type != "text" || incoming.body == null) return false
            if (isReadableForCache(incoming)) return false
            return isReadableForCache(existing)
        }
    }

    private fun Message.toRow(): CachedMessage = CachedMessage(
        id = id,
        conversationId = conversationId,
        createdAt = createdAt,
        clientId = clientId,
        status = status,
        pending = pending,
        payload = runCatching { toJson().toString() }.getOrDefault("{}"),
    )

    private fun CachedMessage.toMessage(): Message? {
        if (payload.isBlank()) return null
        return runCatching {
            JSONObject(payload).toMessage().copy(
                id = id,
                conversationId = conversationId,
                status = status,
                pending = pending,
            )
        }.getOrNull()
    }

    /**
     * Pangkas cache pesan sementara: baris lebih tua dari jendela grup
     * dihapus. System tidak ikut (cermin server); antre pending tidak
     * pernah dihapus (pesan belum terkirim = milik pengguna).
     */
    suspend fun pruneDisappearing(conversationId: Long, days: Int): Int =
        withContext(Dispatchers.IO) {
            if (days <= 0) return@withContext 0
            val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))
            val stale = dao().allForConversation(conversationId).filter { row ->
                row.createdAt < cutoff && !row.pending && row.toMessage()?.type != "system"
            }
            if (stale.isNotEmpty()) dao().deleteByIds(stale.map { it.id })
            stale.size
        }
}
