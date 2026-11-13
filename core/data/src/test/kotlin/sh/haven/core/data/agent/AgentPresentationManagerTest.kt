package sh.haven.core.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus/background behaviour for app windows. Pure Kotlin — APP_WINDOW entries
 * carry no backing file, so no Robolectric/Android stubs are needed.
 */
class AgentPresentationManagerTest {

    private fun mgr() = AgentPresentationManager()

    /** The single app window currently shown full-overlay (host's render rule). */
    private fun AgentPresentationManager.focused(): PresentedMedia? =
        pending.value.firstOrNull { it.id !in minimizedIds.value }

    @Test
    fun secondAppWindowAutoBackgroundsTheFirst() {
        val m = mgr()
        val a = m.presentAppWindow("127.0.0.1", 5901, "appwin-a", "A")
        assertEquals(a, m.focused()?.id)

        val b = m.presentAppWindow("127.0.0.1", 5902, "appwin-b", "B")
        // Exactly one non-minimized, and it's the newest.
        assertEquals(b, m.focused()?.id)
        assertTrue(a in m.minimizedIds.value)
        assertFalse(b in m.minimizedIds.value)
        assertEquals(2, m.pending.value.size)
    }

    @Test
    fun restoreSwapsFocusAndBackgroundsTheCurrent() {
        val m = mgr()
        val a = m.presentAppWindow("127.0.0.1", 5901, "appwin-a", "A")
        val b = m.presentAppWindow("127.0.0.1", 5902, "appwin-b", "B")
        assertEquals(b, m.focused()?.id)

        m.restore(a)
        assertEquals(a, m.focused()?.id)
        assertTrue(b in m.minimizedIds.value)
        assertFalse(a in m.minimizedIds.value)
        // Restored item is moved to the front of the queue.
        assertEquals(a, m.pending.value.first().id)
    }

    @Test
    fun minimizeBackgroundsAndDismissClearsIt() {
        val m = mgr()
        val a = m.presentAppWindow("127.0.0.1", 5901, "appwin-a", "A")
        m.minimize(a)
        assertTrue(a in m.minimizedIds.value)
        assertNull(m.focused())

        m.dismiss(a)
        assertFalse(a in m.minimizedIds.value)
        assertTrue(m.pending.value.isEmpty())
    }

    @Test
    fun imageStaysFocusedAndIsNeverMinimized() {
        val m = mgr()
        // An app window is up and focused.
        m.presentAppWindow("127.0.0.1", 5901, "appwin-a", "A")
        // An image arrives — it does not get minimized, and (being a non-app,
        // earlier-or-later non-minimized entry) it's a valid focus target.
        val img = m.present(
            kind = PresentedMediaKind.IMAGE,
            filePath = "/tmp/does-not-exist.png",
            mimeType = "image/png",
            caption = "shot",
        )
        assertFalse(img in m.minimizedIds.value)
    }

    private fun assertNull(value: Any?) = assertTrue(value == null)
}
