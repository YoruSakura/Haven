package sh.haven.core.wayland

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
    fun `modifier remains held across a normal key tap`() {
        val (router, events) = fixture()

        router.onKeyDown(29) // Ctrl
        router.onKeyDown(46) // C
        router.onKeyUp(46)
        router.onKeyUp(29)

        assertEquals(
            listOf(
                EvdevEvent(29, 1),
                EvdevEvent(46, 1), EvdevEvent(46, 0),
                EvdevEvent(29, 0),
            ),
            events,
        )
    }

    @Test
    fun `repeated modifier down is not forwarded twice`() {
        val (router, events) = fixture()

        router.onKeyDown(42)
        router.onKeyDown(42)
        router.onKeyUp(42)

        assertEquals(listOf(EvdevEvent(42, 1), EvdevEvent(42, 0)), events)
    }

    @Test
    fun `focus loss releases every held modifier`() {
        val (router, events) = fixture()

        router.onKeyDown(29)
        router.onKeyDown(56)
        router.releaseAllModifiers()

        assertEquals(
            listOf(
                EvdevEvent(29, 1), EvdevEvent(56, 1),
                EvdevEvent(29, 0), EvdevEvent(56, 0),
            ),
            events,
        )
    }
}
