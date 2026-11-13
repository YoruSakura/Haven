package sh.haven.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

class LauncherShortcutManagerTest {
    private fun conn(id: String, lastConnected: Long? = null, type: String = "SSH") =
        ConnectionProfile(id = id, label = id, host = "$id.example.com", username = "u", connectionType = type, lastConnected = lastConnected)

    @Test
    fun mostRecentlyConnectedFirstAndNeverConnectedAfter() {
        val shortcuts = LauncherShortcutManager.selectShortcuts(listOf(
            conn("old", 100), conn("new", 300), conn("mid", 200), conn("never"),
        ))
        assertEquals(listOf("new", "mid", "old", "never"), shortcuts.map { it.profileId })
    }

    @Test
    fun removedAndFileOnlyProfilesNeverBecomeLauncherConnections() {
        val retired = listOf("OPENAI", "EMAIL", "RCLONE", "SPICE", "GLKVM", "LOCAL", "SAF", "ROOT", "RETICULUM")
        val conns = retired.map { conn(it, 100, it) } +
            conn("usb", 100).copy(usbDriveSerial = "legacy") +
            listOf(conn("ssh"), conn("rdp", type = "RDP"), conn("vnc", type = "VNC"), conn("smb", type = "SMB"))
        assertEquals(listOf("ssh", "rdp", "vnc", "smb"), LauncherShortcutManager.selectShortcuts(conns).map { it.profileId })
    }

    @Test
    fun shortcutBudgetHonorsPlatformLimit() {
        assertEquals(4, LauncherShortcutManager.maxDynamicShortcuts(5, 1))
        assertEquals(3, LauncherShortcutManager.maxDynamicShortcuts(4, 1))
        assertEquals(5, LauncherShortcutManager.maxDynamicShortcuts(15, 0))
        assertEquals(0, LauncherShortcutManager.maxDynamicShortcuts(0, 1))
        val conns = (1..8).map { conn("c$it", it.toLong()) }
        assertEquals(listOf("c8", "c7", "c6", "c5"), LauncherShortcutManager.selectShortcuts(conns, 4).map { it.profileId })
        assertTrue(LauncherShortcutManager.selectShortcuts(conns, 0).isEmpty())
        assertTrue(LauncherShortcutManager.selectShortcuts(conns, -1).isEmpty())
    }

    @Test
    fun namesAndIdsRemainUsableForPinnedConnections() {
        val spec = LauncherShortcutManager.selectShortcuts(listOf(conn("p1").copy(label = "Web server"))).single()
        assertEquals("connection-p1", spec.shortcutId)
        assertEquals("Web server", spec.label)
    }
}
