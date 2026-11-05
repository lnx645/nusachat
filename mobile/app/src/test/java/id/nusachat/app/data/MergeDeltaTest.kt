package id.nusachat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Delta server harus MENANG atas cache lokal (status segar), kalau tidak
 * centang macet di status basi setiap buka ulang chat.
 */
class MergeDeltaTest {
    private fun msg(id: Long, status: String, body: String = "x") = Message(
        id = id,
        conversationId = 1L,
        sender = null,
        type = "text",
        body = body,
        attachmentUrl = null,
        status = status,
        createdAt = "",
    )

    @Test
    fun serverWinsStatus() {
        val base = listOf(msg(1, "sent"), msg(2, "sent"))
        val delta = listOf(msg(2, "read"), msg(3, "delivered"))
        val merged = mergeServerDelta(base, delta)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("read", merged.first { it.id == 2L }.status)
        assertEquals("delivered", merged.first { it.id == 3L }.status)
    }

    @Test
    fun keepsReadableBodyWithFreshStatus() {
        val base = listOf(msg(2, "sent", "halo"))
        val delta = listOf(msg(2, "read", "cipher?"))
        val merged = mergeServerDelta(base, delta) { _, _ -> true }
        val m = merged.single()
        assertEquals("halo", m.body)
        assertEquals("read", m.status)
    }

    @Test
    fun pendingAppended() {
        val pend = msg(-5, "sending").copy(pending = true)
        val merged = mergeServerDelta(listOf(msg(1, "read")), emptyList(), listOf(pend))
        assertEquals(listOf(-5L, 1L), merged.map { it.id })
    }
}
