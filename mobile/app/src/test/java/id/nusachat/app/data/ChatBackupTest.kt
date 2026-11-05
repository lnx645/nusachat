package id.nusachat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Backup lokal .updb: round-trip tanpa server, kunci salah wajib gagal. */
class ChatBackupTest {

    private fun payload(n: Int = 3): ChatBackup.Payload {
        val msgs = (1..n).map { i ->
            Message(
                id = i.toLong(),
                conversationId = 7L,
                sender = null,
                type = "text",
                body = "halo $i apa kabar?",
                attachmentUrl = null,
                status = "sent",
                createdAt = "2026-11-04T00:00:00Z",
            )
        }
        return ChatBackup.Payload(
            userId = 42L,
            exportedAt = "2026-11-04T00:00:00Z",
            conversations = listOf(ChatBackup.BackupConv(7L, "Sari", "direct")),
            messages = msgs,
            e2eeKeys = mapOf(7L to "a2tuY2k="),
        )
    }

    @Test
    fun digitsRoundTrip() {
        val digits = ChatBackup.generateDigits()
        assertTrue(ChatBackup.isValidDigits(digits))
        val blob = ChatBackup.createBlob(payload(), "digits", digitsInput = digits)
        // File .updb = base64 satu baris.
        assertTrue(blob.blobB64.isNotBlank())
        assertTrue(!blob.blobB64.contains("\n"))
        val back = ChatBackup.openBlob(blob.blobB64, digits)
        assertEquals(3, back.messages.size)
        assertEquals("halo 2 apa kabar?", back.messages[1].body)
        assertEquals("a2tuY2k=", back.e2eeKeys[7L])
        assertEquals(42L, back.userId)
    }

    @Test
    fun passphraseRoundTrip() {
        val blob = ChatBackup.createBlob(payload(2), "pass", passphrase = "kucing-garong-123")
        val back = ChatBackup.openBlob(blob.blobB64, "kucing-garong-123")
        assertEquals(2, back.messages.size)
        assertEquals("halo 1 apa kabar?", back.messages[0].body)
    }

    @Test
    fun wrongKeyFails() {
        val blob = ChatBackup.createBlob(payload(1), "digits")
        try {
            ChatBackup.openBlob(blob.blobB64, "0".repeat(64))
            fail("harus gagal dengan kunci salah")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Kunci salah") || e.message!!.contains("rusak"))
        }
    }

    @Test
    fun wrongPassphraseFails() {
        val blob = ChatBackup.createBlob(payload(1), "pass", passphrase = "benar-sekali")
        try {
            ChatBackup.openBlob(blob.blobB64, "salah-dikit")
            fail("harus gagal")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun digitsToKeyDeterministic() {
        val d = "1".repeat(64)
        assertEquals(
            ChatBackup.digitsToKey(d).toList(),
            ChatBackup.digitsToKey(d).toList(),
        )
    }

    @Test
    fun peekInfoNoKey() {
        val blob = ChatBackup.createBlob(payload(5), "digits")
        val (msg, conv, at) = ChatBackup.peekInfo(blob.blobB64)
        assertEquals(5, msg)
        assertEquals(1, conv)
        assertEquals("2026-11-04T00:00:00Z", at)
    }
}
