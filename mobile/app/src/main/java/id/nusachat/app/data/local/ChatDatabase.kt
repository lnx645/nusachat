package id.nusachat.app.data.local

import android.content.Context
import android.util.Base64
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Satu baris pesan cache. Payload = [id.nusachat.app.data.Message] versi
 * JSON (isi sudah didekrip E2EE); kolom lain untuk query + antre offline.
 * Seluruh file DB dienkripsi SQLCipher (kunci acak di Keystore).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversation_id", "id"]),
        Index(value = ["client_id"], unique = true),
    ],
)
data class CachedMessage(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "client_id") val clientId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "pending") val pending: Boolean,
    @ColumnInfo(name = "payload") val payload: String,
)

/** Cursor sinkronisasi per percakapan: id pesan server terbesar yang cocok. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "last_id") val lastId: Long,
)

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CachedMessage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedMessage)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(conversationId: Long, limit: Int): List<CachedMessage>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun older(conversationId: Long, beforeId: Long, limit: Int): List<CachedMessage>

    @Query("SELECT * FROM messages WHERE pending = 1 AND status = 'queued' ORDER BY id ASC")
    suspend fun queued(): List<CachedMessage>

    @Query("SELECT * FROM messages WHERE client_id = :clientId LIMIT 1")
    suspend fun byClientId(clientId: String): CachedMessage?

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<CachedMessage>

    @Query("SELECT COUNT(*) FROM messages WHERE id > 0")
    suspend fun totalCount(): Int

    @Query("DELETE FROM messages WHERE client_id = :clientId")
    suspend fun deleteByClientId(clientId: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId")
    suspend fun allForConversation(conversationId: Long): List<CachedMessage>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND id > 0 AND id <= :messageId")
    suspend fun upTo(conversationId: Long, messageId: Long): List<CachedMessage>

    /** Monotonik: sent→delivered; read tak pernah turun. */
    @Query("UPDATE messages SET status = 'delivered' WHERE id IN (:ids) AND status = 'sent'")
    suspend fun markDelivered(ids: List<Long>)

    @Query("UPDATE messages SET status = 'read' WHERE id IN (:ids) AND status IN ('sent', 'delivered')")
    suspend fun markRead(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE id > 0 ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun allPaged(limit: Int, offset: Int): List<CachedMessage>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM sync_state WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun cursor(conversationId: Long): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(state: SyncState)

    @Query("DELETE FROM sync_state WHERE conversation_id = :conversationId")
    suspend fun deleteCursor(conversationId: Long)
}

@Database(entities = [CachedMessage::class, SyncState::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        private const val DB_NAME = "nusachat-chat.db"
        private const val KEY_PREFS = "nusachat.dbkey"
        private const val KEY_ALIAS = "db_passphrase"

        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Tutup + hapus file DB (dipakai saat logout agar chat tak tertinggal). */
        fun delete(context: Context) {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
                context.deleteDatabase(DB_NAME)
                context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply()
                runCatching {
                    context.getSharedPreferences(KEY_PREFS + ".enc", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                }
            }
        }

        private fun build(context: Context): ChatDatabase {
            val passphrase = SQLiteDatabase.getBytes(dbPassphrase(context).toCharArray())
            val factory = SupportFactory(passphrase)
            fun create() = Room.databaseBuilder(context, ChatDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .build()
            val db = create()
            // Validasi kunci segera: file lama dengan kunci salah/rusak (mis.
            // hasil restore backup beda device) → buang dan buat baru, karena
            // cache memang disposable dan server sumber kebenaran.
            return try {
                db.openHelper.writableDatabase.version
                db
            } catch (e: Exception) {
                runCatching { db.close() }
                context.deleteDatabase(DB_NAME)
                create()
            }
        }

        /** Frasa sandi acak 32 byte, dibuat sekali dan disimpan terenkripsi. */
        private fun dbPassphrase(context: Context): String {
            val prefs = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "$KEY_PREFS.enc",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull() ?: context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_ALIAS, null)?.let { return it }
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val fresh = Base64.encodeToString(bytes, Base64.NO_WRAP)
            prefs.edit().putString(KEY_ALIAS, fresh).apply()
            return fresh
        }
    }
}
