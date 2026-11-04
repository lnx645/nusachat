package id.nusachat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jendela memori daftar pesan chat: hanya N terbaru + semua pending
 * yang ditahan di RAM. Pesan lama yang dibuang tetap ada di cache/server
 * dan bisa dimuat lagi lewat beforeId (loadOlderMessages).
 */
class ChatWindowTest {
    private fun msg(id: Long, pending: Boolean = false) = Message(
        id = id,
        conversationId = 1L,
        sender = null,
        type = "text",
        body = "m$id",
        attachmentUrl = null,
        status = if (pending) "sending" else "sent",
        createdAt = "2026-11-04T00:00:00Z",
        pending = pending,
    )

    @Test
    fun keepsNewestWhenOverCap() {
        val all = (1L..500L).map { msg(it) }
        val capped = all.capChatWindow(max = 300)
        assertEquals(300, capped.size)
        assertEquals((201L..500L).toList(), capped.map { it.id })
    }

    @Test
    fun preservesPendingBeyondCap() {
        val all = (1L..500L).map { msg(it) } + listOf(msg(-11L, pending = true), msg(-12L, pending = true))
        val capped = all.capChatWindow(max = 300)
        // 300 terbaru + 2 pending (id negatif diurut di depan).
        assertEquals(302, capped.size)
        assertTrue(capped.any { it.id == -11L })
        assertTrue(capped.any { it.id == -12L })
        assertEquals((201L..500L).toList(), capped.filterNot { it.pending }.map { it.id })
    }

    @Test
    fun smallListUnchanged() {
        val all = (1L..40L).map { msg(it) }
        assertEquals(all, all.capChatWindow(max = 300))
    }

    @Test
    fun emptyListOk() {
        assertEquals(emptyList<Message>(), emptyList<Message>().capChatWindow())
    }
}
