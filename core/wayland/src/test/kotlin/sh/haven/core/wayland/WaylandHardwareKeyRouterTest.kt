package sh.haven.core.wayland

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class WaylandHardwareKeyRouterTest {
    private data class EvdevEvent(val key: Int, val pressed: Int)

    private fun fixture(): Pair<WaylandHardwareKeyRouter, MutableList<EvdevEvent>> {
        val events = mutableListOf<EvdevEvent>()
        return WaylandHardwareKeyRouter { key, pressed ->
            events += EvdevEvent(key, pressed)
        } to events
    }

    @Test
    fun `physical right arrow is bounded without key up`() {
        val (router, events) = fixture()

        router.onKeyDown(106)

        assertEquals(listOf(EvdevEvent(106, 1), EvdevEvent(106, 0)), events)
    }

    @Test
    fun `physical left arrow is bounded without key up`() {
        val (router, events) = fixture()

        router.onKeyDown(105)

        assertEquals(listOf(EvdevEvent(105, 1), EvdevEvent(105, 0)), events)
    }

    @Test
    fun `physical space is bounded without key up`() {
        val (router, events) = fixture()

        router.onKeyDown(57)

        assertEquals(listOf(EvdevEvent(57, 1), EvdevEvent(57, 0)), events)
    }

    @Test
    fun `later non modifier key up is already represented by tap`() {
        val (router, events) = fixture()

        router.onKeyDown(106)
        router.onKeyUp(106)

        assertEquals(listOf(EvdevEvent(106, 1), EvdevEvent(106, 0)), events)
    }

    @Test
    fun `Android repeat downs become repeated bounded taps`() {
        val (router, events) = fixture()

        router.onKeyDown(57)
        router.onKeyDown(57)

        assertEquals(
            listOf(
                EvdevEvent(57, 1), EvdevEvent(57, 0),
                EvdevEvent(57, 1), EvdevEvent(57, 0),
            ),
            events,
        )
    }

    @Test
    fun `Ctrl J is one self contained chord`() {
        val (router, events) = fixture()

        router.onKeyDown(29) // Standalone Ctrl event is not forwarded.
        router.onKeyDown(36, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON) // J
        router.onKeyUp(36)
        router.onKeyUp(29)

        assertEquals(
            listOf(
                EvdevEvent(29, 1),
                EvdevEvent(36, 1), EvdevEvent(36, 0),
                EvdevEvent(29, 0),
            ),
            events,
        )
    }

    @Test
    fun `missing Ctrl key up cannot affect a later plain J`() {
        val (router, events) = fixture()

        router.onKeyDown(29) // Android/View never delivers its matching UP.
        router.onKeyDown(36, KeyEvent.META_CTRL_ON)
        router.onKeyDown(36)

        assertEquals(
            listOf(
                EvdevEvent(29, 1),
                EvdevEvent(36, 1), EvdevEvent(36, 0),
                EvdevEvent(29, 0),
                EvdevEvent(36, 1), EvdevEvent(36, 0),
            ),
            events,
        )
    }

    @Test
    fun `Ctrl arrow chord cannot leak Ctrl into the next arrow`() {
        val (router, events) = fixture()

        router.onKeyDown(105, KeyEvent.META_CTRL_ON) // Ctrl+Left
        router.onKeyDown(106) // plain Right

        assertEquals(
            listOf(
                EvdevEvent(29, 1),
                EvdevEvent(105, 1), EvdevEvent(105, 0),
                EvdevEvent(29, 0),
                EvdevEvent(106, 1), EvdevEvent(106, 0),
            ),
            events,
        )
    }

    @Test
    fun `plain I after Ctrl shortcut cannot become Tab`() {
        val (router, events) = fixture()

        router.onKeyDown(46, KeyEvent.META_CTRL_ON) // Ctrl+C
        router.onKeyDown(23) // I, not Ctrl+I (Tab)

        assertEquals(
            listOf(
                EvdevEvent(29, 1),
                EvdevEvent(46, 1), EvdevEvent(46, 0),
                EvdevEvent(29, 0),
                EvdevEvent(23, 1), EvdevEvent(23, 0),
            ),
            events,
        )
    }

    @Test
    fun `side specific modifiers wrap and release in reverse order`() {
        val (router, events) = fixture()

        router.onKeyDown(
            30,
            KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_ALT_LEFT_ON or
                KeyEvent.META_SHIFT_RIGHT_ON,
        )

        assertEquals(
            listOf(
                EvdevEvent(97, 1), EvdevEvent(56, 1), EvdevEvent(54, 1),
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(54, 0), EvdevEvent(56, 0), EvdevEvent(97, 0),
            ),
            events,
        )
    }

    @Test
    fun `standalone modifier events never enter Wayland state`() {
        val (router, events) = fixture()

        listOf(29, 97, 42, 54, 56, 100).forEach {
            router.onKeyDown(it)
            router.onKeyUp(it)
        }

        assertEquals(emptyList<EvdevEvent>(), events)
    }
}
