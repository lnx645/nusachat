package id.nusachat.app.ui.chats.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Render event sistem: nama live menang, snapshot cadangan, legacy apa adanya. */
class SystemRenderTest {
    private val live = mapOf(1L to "Budi Baru", 2L to "Sari")
    private val snap = "\"names\":{\"1\":\"Budi Lama\",\"2\":\"Sari\",\"3\":\"Dewi\"}"

    @Test
    fun memberAddUsesLiveNames() {
        val body = "{\"sys\":\"member_add\",\"by\":1,\"targets\":[2,3],$snap}"
        assertEquals("Budi Baru menambahkan Sari dan Dewi", renderSystemText(body, live))
    }

    @Test
    fun memberAddLabelVariants() {
        val one = "{\"sys\":\"member_add\",\"by\":1,\"targets\":[3],$snap}"
        assertEquals("Budi Baru menambahkan Dewi", renderSystemText(one, live))
        val four = "{\"sys\":\"member_add\",\"by\":1,\"targets\":[2,3,4,5],$snap}"
        assertEquals(
            "Budi Baru menambahkan Sari, Dewi dan 2 lainnya",
            renderSystemText(four, live),
        )
    }

    @Test
    fun snapshotFallbackForUnknown() {
        // Dewi (3) tak ada di live → snapshot; 9 tak dikenal → #9.
        val body = "{\"sys\":\"member_add\",\"by\":9,\"targets\":[3],$snap}"
        assertEquals("#9 menambahkan Dewi", renderSystemText(body, live))
    }

    @Test
    fun allCodesRender() {
        val base = "\"by\":1,\"targets\":[2],$snap"
        val cases = mapOf(
            "{\"sys\":\"member_leave\",$base}" to "Budi Baru keluar",
            "{\"sys\":\"member_remove\",$base}" to "Budi Baru mengeluarkan Sari",
            "{\"sys\":\"owner_transfer\",$base}" to "Budi Baru mengalihkan kepemilikan grup ke Sari",
            "{\"sys\":\"join_link\",$base}" to "Budi Baru bergabung via tautan undangan",
            "{\"sys\":\"approve_join\",$base}" to "Budi Baru menyetujui Sari bergabung",
            "{\"sys\":\"info_update\",$base}" to "Budi Baru mengubah info grup",
            "{\"sys\":\"settings_update\",$base}" to "Budi Baru mengubah pengaturan grup",
        )
        for ((body, expected) in cases) {
            assertEquals(expected, renderSystemText(body, live))
        }
        assertEquals(
            "Budi Baru membuat grup \"Ngopi\"",
            renderSystemText("{\"sys\":\"group_create\",\"by\":1,\"targets\":[],$snap,\"title\":\"Ngopi\"}", live),
        )
        assertEquals(
            "Budi Baru mengangkat Sari menjadi admin",
            renderSystemText("{\"sys\":\"role_change\",$base,\"role\":\"admin\"}", live),
        )
        assertEquals(
            "Budi Baru mencabut admin Sari",
            renderSystemText("{\"sys\":\"role_change\",$base,\"role\":\"member\"}", live),
        )
    }

    @Test
    fun legacyAndMalformedPassthrough() {
        assertEquals("Budi Lama keluar", renderSystemText("Budi Lama keluar", live))
        assertEquals("", renderSystemText(null, live))
        assertEquals("", renderSystemText("  ", live))
        // JSON rusak → badan mentah (jangan blank).
        val broken = "{\"sys\":\"member_add\","
        assertEquals(broken, renderSystemText(broken, live))
        // Kode masa depan → generik, bukan JSON mentah.
        val future = "{\"sys\":\"poll_open\",\"by\":1,\"targets\":[],$snap}"
        assertEquals("Budi Baru memperbarui grup", renderSystemText(future, live))
    }
}
