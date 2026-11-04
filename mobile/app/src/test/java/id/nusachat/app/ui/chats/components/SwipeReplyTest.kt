package id.nusachat.app.ui.chats.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Arbitrasi swipe-to-reply: dievaluasi kontinu setiap gerakan jari.
 * Arah reply tergantung sisi bubble — milik sendiri (kanan) hanya
 * kiri→kanan, pesan lawan (kiri) hanya kanan→kiri di LTR.
 */
class SwipeReplyTest {
    private val slop = 18f

    @Test
    fun allowedDirectionClaims() {
        assertEquals(SwipeArbitration.CLAIM, arbitrateSwipe(40f, 2f, slop, 1f))
        assertEquals(SwipeArbitration.CLAIM, arbitrateSwipe(-40f, 2f, slop, -1f))
    }

    @Test
    fun slightDiagonalStillClaims() {
        assertEquals(SwipeArbitration.CLAIM, arbitrateSwipe(40f, 15f, slop, 1f))
        assertEquals(SwipeArbitration.CLAIM, arbitrateSwipe(-40f, -15f, slop, -1f))
    }

    @Test
    fun oppositeDirectionAborts() {
        assertEquals(SwipeArbitration.ABORT, arbitrateSwipe(-40f, 3f, slop, 1f))
        assertEquals(SwipeArbitration.ABORT, arbitrateSwipe(40f, 3f, slop, -1f))
    }

    @Test
    fun verticalScrollAborts() {
        assertEquals(SwipeArbitration.ABORT, arbitrateSwipe(5f, 60f, slop, 1f))
        assertEquals(SwipeArbitration.ABORT, arbitrateSwipe(20f, 40f, slop, 1f))
        assertEquals(SwipeArbitration.ABORT, arbitrateSwipe(-5f, -60f, slop, -1f))
    }

    @Test
    fun tinyMovementWaits() {
        assertEquals(SwipeArbitration.WAIT, arbitrateSwipe(5f, 5f, slop, 1f))
        assertEquals(SwipeArbitration.WAIT, arbitrateSwipe(0f, 0f, slop, -1f))
    }
}
