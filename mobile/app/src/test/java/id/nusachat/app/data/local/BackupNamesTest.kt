package id.nusachat.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aturan folder tetap Download/NusaChat — murni, tanpa Android. */
class BackupNamesTest {

    @Test
    fun stampedFormat() {
        val name = BackupNames.stampedNow(1_792_000_000_000L)
        assertTrue(name.startsWith("nusachat-backup-"))
        assertTrue(name.endsWith(".updb"))
        assertTrue(BackupNames.isBackupName(name))
        assertTrue(BackupNames.isStamped(name))
    }

    @Test
    fun latestIsBackupNotStamped() {
        assertTrue(BackupNames.isBackupName(BackupNames.LATEST))
        assertFalse(BackupNames.isStamped(BackupNames.LATEST))
    }

    @Test
    fun rejectsForeignNames() {
        assertFalse(BackupNames.isBackupName("foto.jpg"))
        assertFalse(BackupNames.isBackupName("nusachat-backup-.txt"))
        assertFalse(BackupNames.isBackupName("nusachat-backup-latest.updb.bak"))
        assertFalse(BackupNames.isBackupName(""))
    }

    @Test
    fun pruneKeepsNewestSeven() {
        val names = (1..10).map { i -> "nusachat-backup-202611%02d-1200.updb".format(i) }
        val drop = BackupNames.prune(names)
        assertEquals(3, drop.size)
        // Yang dibuang = 3 tertua.
        assertTrue(drop.contains("nusachat-backup-20261101-1200.updb"))
        assertTrue(drop.contains("nusachat-backup-20261103-1200.updb"))
        assertFalse(drop.contains("nusachat-backup-20261110-1200.updb"))
    }

    @Test
    fun pruneKeepAllWhenFew() {
        val names = listOf("nusachat-backup-20261101-1200.updb")
        assertTrue(BackupNames.prune(names).isEmpty())
    }
}
