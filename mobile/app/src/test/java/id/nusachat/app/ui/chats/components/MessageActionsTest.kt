package id.nusachat.app.ui.chats.components

import id.nusachat.app.data.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ketersediaan aksi pesan per kondisi (sumber tunggal untuk contextual bar:
 * ikon app bar, menu overflow ⋮, dan tombol bawah).
 */
class MessageActionsTest {
    private fun msg(
        id: Long = 7L,
        type: String = "text",
        body: String? = "halo",
        attachmentUrl: String? = null,
        status: String = "sent",
        revoked: Boolean = false,
        clientId: String? = null,
        lat: Double? = null,
        lng: Double? = null,
    ) = Message(
        id = id,
        conversationId = 1L,
        sender = null,
        type = type,
        body = body,
        attachmentUrl = attachmentUrl,
        status = status,
        createdAt = "2026-11-04T00:00:00Z",
        clientId = clientId,
        revoked = revoked,
        lat = lat,
        lng = lng,
    )

    @Test
    fun ownTextHasFullActions() {
        val actions = availableMsgActions(msg(), mine = true)
        assertTrue(actions.containsAll(setOf(MsgAction.REACT, MsgAction.REPLY, MsgAction.EDIT, MsgAction.FORWARD, MsgAction.PIN, MsgAction.COPY, MsgAction.STAR, MsgAction.SHARE, MsgAction.HIDE, MsgAction.REVOKE, MsgAction.INFO)))
        assertFalse(actions.contains(MsgAction.SAVE))
        assertFalse(actions.contains(MsgAction.REPORT))
        assertFalse(actions.contains(MsgAction.RETRY))
    }

    @Test
    fun peerTextCanReportButNotEditOrRevoke() {
        val actions = availableMsgActions(msg(), mine = false)
        assertTrue(actions.contains(MsgAction.REPORT))
        assertFalse(actions.contains(MsgAction.EDIT))
        assertFalse(actions.contains(MsgAction.REVOKE))
    }

    @Test
    fun imageWithAttachmentCanSaveButNotCopyOrEdit() {
        val actions = availableMsgActions(msg(type = "image", body = null, attachmentUrl = "https://x/y.jpg"), mine = true)
        assertTrue(actions.contains(MsgAction.SAVE))
        assertTrue(actions.contains(MsgAction.FORWARD))
        assertFalse(actions.contains(MsgAction.COPY))
        assertFalse(actions.contains(MsgAction.SHARE))
        assertFalse(actions.contains(MsgAction.EDIT))
    }

    @Test
    fun locationHasCopyTextButNoEdit() {
        val actions = availableMsgActions(msg(type = "location", lat = -6.2, lng = 106.8), mine = false)
        assertTrue(actions.contains(MsgAction.COPY))
        assertFalse(actions.contains(MsgAction.EDIT))
    }

    @Test
    fun failedMessageOnlyRetryReplyInfo() {
        val actions = availableMsgActions(
            msg(id = -11L, status = "failed", clientId = "c1"),
            mine = true,
        )
        assertTrue(actions.contains(MsgAction.RETRY))
        assertTrue(actions.contains(MsgAction.REPLY))
        assertTrue(actions.contains(MsgAction.INFO))
        assertFalse(actions.contains(MsgAction.FORWARD))
        assertFalse(actions.contains(MsgAction.REACT))
        assertFalse(actions.contains(MsgAction.REVOKE))
    }

    @Test
    fun revokedMessageKeepsCopyAndShare() {
        val actions = availableMsgActions(msg(revoked = true), mine = false)
        assertTrue(actions.contains(MsgAction.COPY))
        assertTrue(actions.contains(MsgAction.SHARE))
        assertTrue(actions.contains(MsgAction.REPLY))
        assertTrue(actions.contains(MsgAction.INFO))
        assertFalse(actions.contains(MsgAction.FORWARD))
        assertFalse(actions.contains(MsgAction.REACT))
    }
}
