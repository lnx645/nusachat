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
        // Permanen tak-terbaca langsung dibuang: habis install ulang chat
        // terlihat kosong & fresh, tanpa tulisan "tidak tersedia".
        val rows = messages.filter { it.id != 0L && !isDeadForDisplay(it) }
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

    /** Seluruh pesan cache (plaintext hasil dekrip) untuk backup terenkripsi. */
    suspend fun exportAll(limit: Int = 20000): List<Message> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Message>()
        var offset = 0
        val page = 1000
        while (out.size < limit) {
            val rows = runCatching { dao().allPaged(page, offset) }.getOrDefault(emptyList())
            if (rows.isEmpty()) break
            // Backup hanya isi bermakna: placeholder (mati/HEAL) tak ikut.
            rows.mapNotNull { it.toMessage() }.filter { isReadableForCache(it) }.forEach { out.add(it) }
            if (rows.size < page) break
            offset += page
        }
        out.take(limit)
    }

    /**
     * Sapu satu kali: hapus baris placeholder permanen peninggalan versi lama
     * (habis install ulang terlihat kosong & fresh). Dipanggil saat login.
     * @return jumlah baris terhapus.
     */
    suspend fun pruneDead(limit: Int = 20000): Int = withContext(Dispatchers.IO) {
        val dead = mutableListOf<Long>()
        var offset = 0
        val page = 1000
        while (dead.size + offset < limit) {
            val rows = runCatching { dao().allPaged(page, offset) }.getOrDefault(emptyList())
            if (rows.isEmpty()) break
            rows.mapNotNull { it.toMessage() }.filter { isDeadForDisplay(it) }.forEach { dead.add(it.id) }
            if (rows.size < page) break
            offset += page
        }
        if (dead.isEmpty()) return@withContext 0
        dead.distinct().chunked(500).forEach { runCatching { dao().deleteByIds(it) } }
        dead.size
    }

    /** Tulis hasil restore backup (overwrite per id). */
    suspend fun importAll(messages: List<Message>): Int = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext 0
        var n = 0
        messages.chunked(500).forEach { chunk ->
            val rows = chunk.filter { it.id != 0L }.map { it.toRow() }
            if (rows.isNotEmpty()) {
                dao().upsertAll(rows)
                n += rows.size
                chunk.filter { it.id > 0 }.groupBy { it.conversationId }
                    .forEach { (conv, list) ->
                        list.maxOfOrNull { it.id }?.let { saveCursor(conv, it) }
                    }
            }
        }
        n
    }

    suspend fun saveMessage(message: Message): Unit = withContext(Dispatchers.IO) {
        if (message.id != 0L && !isDeadForDisplay(message)) dao().upsert(message.toRow())
    }

    /**
     * Plaintext cache berdasarkan id. Dipakai pratinjau (daftar chat, pin)
     * agar TIDAK mendekrip ulang ciphertext yang kunci ratchet-nya sudah
     * terpakai — dekrip kedua selalu gagal (kunci sekali pakai).
     */
    suspend fun byIds(ids: List<Long>): List<Message> = withContext(Dispatchers.IO) {
        val clean = ids.filter { it > 0 }.distinct()
        if (clean.isEmpty()) return@withContext emptyList()
        dao().byIds(clean).mapNotNull { it.toMessage() }
    }

    /** Naikkan status pesan milikku ≤ messageId (monotonik, anti balik). */
    suspend fun markOwnUpTo(conversationId: Long, myId: Long, messageId: Long, status: String): Unit =
        withContext(Dispatchers.IO) {
            val mine = ownIdsUpTo(conversationId, myId, messageId)
            if (mine.isEmpty()) return@withContext
            runCatching {
                when (status) {
                    "read" -> dao().markRead(mine)
                    "delivered" -> dao().markDelivered(mine)
                }
            }
        }

    /**
     * Terapkan event read ke cache: [readIds] biru, milikku lain ≤ messageId
     * minimal abu (dibaca = diterima). Null = server lama: birukan semua.
     */
    suspend fun markReadUpTo(
        conversationId: Long,
        myId: Long,
        messageId: Long,
        readIds: Set<Long>?,
    ): Unit = withContext(Dispatchers.IO) {
        if (readIds == null) {
            markOwnUpTo(conversationId, myId, messageId, "read")
            return@withContext
        }
        val mine = ownIdsUpTo(conversationId, myId, messageId)
        if (mine.isEmpty()) return@withContext
        val (blue, grey) = mine.partition { it in readIds }
        runCatching {
            if (blue.isNotEmpty()) dao().markRead(blue)
            if (grey.isNotEmpty()) dao().markDelivered(grey)
        }
    }

    /** Id pesan milikku ≤ messageId (pengirim dibaca dari payload JSON). */
    private suspend fun ownIdsUpTo(conversationId: Long, myId: Long, messageId: Long): List<Long> {
        if (messageId <= 0) return emptyList()
        val rows = runCatching { dao().upTo(conversationId, messageId) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val senderId = runCatching {
                JSONObject(row.payload).optJSONObject("sender")?.optLong("id")
            }.getOrNull()
            if (senderId == myId) row.id else null
        }
    }

    /** Pesan terbaru dari cache (urut naik seperti tampilan). */
    suspend fun recent(conversationId: Long, limit: Int = 40): List<Message> =
        withContext(Dispatchers.IO) {
            dao().recent(conversationId, limit).mapNotNull { it.toMessage() }
                .filterNot { isDeadForDisplay(it) }.sortedBy { it.id }
        }

    suspend fun older(conversationId: Long, beforeId: Long, limit: Int = 30): List<Message> =
        withContext(Dispatchers.IO) {
            dao().older(conversationId, beforeId, limit).mapNotNull { it.toMessage() }
                .filterNot { isDeadForDisplay(it) }.sortedBy { it.id }
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
        /**
         * Permanen tak-terbaca (kunci hilang/rantai v2 mati): jangan tampil
         * atau simpan. Beda dengan SYNC ("membutuhkan waktu") yang bisa heal.
         */
        fun isDeadForDisplay(m: Message): Boolean {
            if (m.type != "text" || m.body == null) return false
            return id.nusachat.app.data.E2eeManager.isDeadBody(m.body)
        }

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
